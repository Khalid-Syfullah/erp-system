package com.erp.payroll.application;

import com.erp.payroll.domain.Calculation;
import com.erp.payroll.domain.ComponentKind;
import com.erp.payroll.domain.PayPeriods;
import com.erp.payroll.domain.statutory.StatutoryRule;
import com.erp.payroll.domain.statutory.StatutoryRules;
import com.erp.payroll.persistence.PayrollConfigRepository;
import com.erp.payroll.persistence.PeriodRepository;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditPort;
import com.erp.platform.web.ApiException;
import com.erp.platform.web.EntityTags;
import com.erp.platform.web.FieldViolation;
import com.erp.platform.web.MergePatch;
import com.erp.platform.web.PlatformErrorCode;
import com.erp.platform.web.paging.ListQuery;
import com.erp.platform.web.paging.PageResponse;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * Payroll configuration (PRODUCT_SPEC.md §11.1): the proration setting, pay components, salary
 * structures, pay schedules and their periods. Changes apply to calculations from then on; posted
 * payslips keep their snapshot. Pay schedules are in the company's base currency (ADR-039).
 */
@Service
public class PayrollSetupService {

    static final Set<String> COMPONENT_PATCHABLE =
            Set.of("name", "defaultRate", "defaultAmount", "isTaxable", "statutoryRuleCode", "sequence", "isActive");
    private static final BigDecimal MAX_RATE = new BigDecimal("1000000");
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("999999999999999");

    private final PayrollConfigRepository config;
    private final PeriodRepository periods;
    private final StatutoryRules rules;
    private final PayrollContext context;
    private final AuditPort audit;

    PayrollSetupService(
            PayrollConfigRepository config,
            PeriodRepository periods,
            StatutoryRules rules,
            PayrollContext context,
            AuditPort audit) {
        this.config = config;
        this.periods = periods;
        this.rules = rules;
        this.context = context;
        this.audit = audit;
    }

    // ------------------------------------------------------------------------------ settings

    @Transactional(readOnly = true)
    public PayrollViews.Settings settings() {
        return config.settings(context.companyId()).orElse(new PayrollViews.Settings("CALENDAR_DAYS", 0));
    }

    @Transactional
    public PayrollViews.Settings replaceSettings(@Nullable String ifMatch, String prorationBasis) {
        UUID companyId = context.companyId();
        if (!prorationBasis.equals("CALENDAR_DAYS") && !prorationBasis.equals("WORKING_DAYS")) {
            throw invalid("/prorationBasis", "INVALID_VALUE", "must be CALENDAR_DAYS or WORKING_DAYS");
        }
        config.ensureSettings(companyId);
        PayrollViews.Settings current = config.settings(companyId).orElseThrow();
        EntityTags.requireMatch(ifMatch, current.version());
        if (!config.updateSettings(companyId, current.version(), context.actor(), prorationBasis)) {
            throw new ApiException(PlatformErrorCode.VERSION_CONFLICT, "The settings were modified concurrently.");
        }
        audit.record(AuditEvent.builder("UPDATE", "payroll")
                .entity("payroll_settings", companyId, null)
                .change("prorationBasis", current.prorationBasis(), prorationBasis)
                .build());
        return settings();
    }

    /** The registered statutory rules (codes for STATUTORY components). */
    public List<StatutoryRule> statutoryRules() {
        return rules.all();
    }

    // ---------------------------------------------------------------------------- components

    @Transactional(readOnly = true)
    public PageResponse<PayrollViews.Component> components(ListQuery query) {
        return config.listComponents(context.companyId(), query);
    }

    @Transactional(readOnly = true)
    public PayrollViews.Component component(UUID id) {
        return config.component(context.companyId(), id).orElseThrow(ApiException::notFound);
    }

    @Transactional
    public PayrollViews.Component createComponent(PayrollCommands.Component c) {
        validateComponent(c);
        UUID id = config.insertComponent(context.companyId(), c, context.actor());
        audit.record(AuditEvent.builder("CREATE", "payroll")
                .entity("pay_component", id, c.code())
                .detail("kind", c.kind())
                .detail("calculation", c.calculation())
                .detail("statutoryRuleCode", c.statutoryRuleCode())
                .build());
        return component(id);
    }

    @Transactional
    public PayrollViews.Component patchComponent(UUID id, @Nullable String ifMatch, JsonNode document) {
        UUID companyId = context.companyId();
        PayrollViews.Component current = config.lockComponent(companyId, id).orElseThrow(ApiException::notFound);
        EntityTags.requireMatch(ifMatch, current.version());
        MergePatch patch = MergePatch.of(document, COMPONENT_PATCHABLE);
        var name = patch.text("name", true, 100);
        var rate = patch.decimal("defaultRate", BigDecimal.ZERO, MAX_RATE, 6);
        var amount = patch.decimal("defaultAmount", BigDecimal.ZERO, MAX_AMOUNT, 4);
        var taxable = patch.bool("isTaxable");
        var rule = patch.text("statutoryRuleCode", false, 40);
        var sequence = patch.integer("sequence", 1, 9999);
        var active = patch.bool("isActive");
        patch.throwIfInvalid();
        PayrollCommands.Component next = new PayrollCommands.Component(
                current.code(),
                name.orElse(current.name()),
                current.kind(),
                current.calculation(),
                rate.orElse(current.defaultRate()),
                amount.orElse(current.defaultAmount()),
                Boolean.TRUE.equals(taxable.orElse(current.taxable())),
                rule.orElse(current.statutoryRuleCode()),
                sequence.orElse(current.sequence()),
                Boolean.TRUE.equals(active.orElse(current.active())));
        validateComponent(next);
        if (!config.updateComponent(companyId, id, current.version(), context.actor(), next)) {
            throw new ApiException(PlatformErrorCode.VERSION_CONFLICT, "The component was modified concurrently.");
        }
        PayrollViews.Component after = component(id);
        audit.record(AuditEvent.builder("UPDATE", "payroll")
                .entity("pay_component", id, current.code())
                .change("name", current.name(), after.name())
                .change("defaultRate", current.defaultRate(), after.defaultRate())
                .change("defaultAmount", current.defaultAmount(), after.defaultAmount())
                .change("isTaxable", current.taxable(), after.taxable())
                .change("statutoryRuleCode", current.statutoryRuleCode(), after.statutoryRuleCode())
                .change("sequence", current.sequence(), after.sequence())
                .change("isActive", current.active(), after.active())
                .build());
        return after;
    }

    private void validateComponent(PayrollCommands.Component c) {
        List<FieldViolation> violations = new ArrayList<>();
        ComponentKind kind = parse(ComponentKind.class, c.kind());
        Calculation calculation = parse(Calculation.class, c.calculation());
        if (kind == null) {
            violations.add(FieldViolation.atPointer("/kind", "INVALID_VALUE", "is not a component kind"));
        }
        if (calculation == null) {
            violations.add(FieldViolation.atPointer("/calculation", "INVALID_VALUE", "is not a calculation"));
        }
        if (kind != null && calculation != null && !calculation.allowedFor(kind)) {
            violations.add(FieldViolation.atPointer(
                    "/calculation", "INVALID_VALUE", "an earning is FIXED, PERCENT_OF_BASE or INPUT"));
        }
        if (calculation == Calculation.STATUTORY) {
            if (c.statutoryRuleCode() == null
                    || rules.find(c.statutoryRuleCode()).isEmpty()) {
                violations.add(FieldViolation.atPointer(
                        "/statutoryRuleCode",
                        "UNKNOWN_STATUTORY_RULE",
                        "must be one of "
                                + rules.all().stream().map(StatutoryRule::code).toList()));
            }
        } else if (c.statutoryRuleCode() != null) {
            violations.add(FieldViolation.atPointer(
                    "/statutoryRuleCode", "INVALID_VALUE", "is only for STATUTORY components"));
        }
        if ((calculation == Calculation.PERCENT_OF_BASE || calculation == Calculation.PERCENT_OF_GROSS)
                && c.defaultRate() != null
                && c.defaultRate().compareTo(BigDecimal.valueOf(1000)) > 0) {
            violations.add(
                    FieldViolation.atPointer("/defaultRate", "INVALID_VALUE", "must be a percentage up to 1000"));
        }
        if (!violations.isEmpty()) {
            throw ApiException.validationFailed("The pay component is invalid.", violations);
        }
    }

    // ---------------------------------------------------------------------------- structures

    @Transactional(readOnly = true)
    public PageResponse<PayrollViews.Structure> structures(ListQuery query) {
        return config.listStructures(context.companyId(), query);
    }

    @Transactional(readOnly = true)
    public PayrollViews.Structure structure(UUID id) {
        return config.structure(context.companyId(), id).orElseThrow(ApiException::notFound);
    }

    @Transactional
    public PayrollViews.Structure createStructure(String code, String name, List<PayrollCommands.StructureLine> lines) {
        UUID companyId = context.companyId();
        List<PayrollViews.StructureComponent> components = structureLines(lines);
        UUID id = config.insertStructure(companyId, code, name.strip(), context.actor());
        config.replaceStructureComponents(companyId, id, components);
        audit.record(AuditEvent.builder("CREATE", "payroll")
                .entity("salary_structure", id, code)
                .detail(
                        "components",
                        components.stream()
                                .map(PayrollViews.StructureComponent::componentCode)
                                .toList())
                .build());
        return structure(id);
    }

    @Transactional
    public PayrollViews.Structure updateStructure(
            UUID id, @Nullable String ifMatch, String name, boolean active, List<PayrollCommands.StructureLine> lines) {
        UUID companyId = context.companyId();
        PayrollViews.Structure current = config.lockStructure(companyId, id).orElseThrow(ApiException::notFound);
        EntityTags.requireMatch(ifMatch, current.version());
        List<PayrollViews.StructureComponent> components = structureLines(lines);
        if (!config.updateStructure(companyId, id, current.version(), context.actor(), name.strip(), active)) {
            throw new ApiException(PlatformErrorCode.VERSION_CONFLICT, "The structure was modified concurrently.");
        }
        config.replaceStructureComponents(companyId, id, components);
        audit.record(AuditEvent.builder("UPDATE", "payroll")
                .entity("salary_structure", id, current.code())
                .change("name", current.name(), name.strip())
                .change("isActive", current.active(), active)
                .change(
                        "components",
                        current.components().stream()
                                .map(PayrollViews.StructureComponent::componentCode)
                                .toList(),
                        components.stream()
                                .map(PayrollViews.StructureComponent::componentCode)
                                .toList())
                .build());
        return structure(id);
    }

    private List<PayrollViews.StructureComponent> structureLines(List<PayrollCommands.StructureLine> lines) {
        UUID companyId = context.companyId();
        Map<UUID, PayrollViews.Component> found = config.components(
                companyId,
                lines.stream().map(PayrollCommands.StructureLine::componentId).toList());
        List<FieldViolation> violations = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        List<PayrollViews.StructureComponent> result = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            PayrollCommands.StructureLine line = lines.get(i);
            PayrollViews.Component c = found.get(line.componentId());
            if (c == null || !c.active()) {
                violations.add(FieldViolation.atPointer(
                        "/components/" + i + "/componentId", "UNKNOWN_COMPONENT", "must be an active pay component"));
            } else if (!seen.add(c.id())) {
                violations.add(
                        FieldViolation.atPointer("/components/" + i + "/componentId", "DUPLICATE", "appears twice"));
            } else {
                result.add(new PayrollViews.StructureComponent(c.id(), c.code(), line.rate(), line.amount()));
            }
        }
        if (!violations.isEmpty()) {
            throw ApiException.validationFailed("The salary structure is invalid.", violations);
        }
        return result;
    }

    // ----------------------------------------------------------------------------- schedules

    @Transactional(readOnly = true)
    public PageResponse<PayrollViews.Schedule> schedules(ListQuery query) {
        return config.listSchedules(context.companyId(), query);
    }

    @Transactional(readOnly = true)
    public PayrollViews.Schedule schedule(UUID id) {
        return config.schedule(context.companyId(), id).orElseThrow(ApiException::notFound);
    }

    @Transactional
    public PayrollViews.Schedule createSchedule(PayrollCommands.Schedule c) {
        if (!c.currencyCode().equals(context.profile().baseCurrency())) {
            throw invalid("/currencyCode", "CURRENCY_NOT_SUPPORTED", "must be the company's base currency");
        }
        boolean anchored = c.frequency().equals("WEEKLY") || c.frequency().equals("BIWEEKLY");
        if (anchored != (c.anchorDate() != null)) {
            throw invalid("/anchorDate", "INVALID_VALUE", "is required for WEEKLY and BIWEEKLY schedules only");
        }
        UUID id = config.insertSchedule(context.companyId(), c, context.actor());
        audit.record(AuditEvent.builder("CREATE", "payroll")
                .entity("pay_schedule", id, c.code())
                .detail("frequency", c.frequency())
                .build());
        return schedule(id);
    }

    @Transactional
    public PayrollViews.Schedule patchSchedule(UUID id, @Nullable String ifMatch, JsonNode document) {
        UUID companyId = context.companyId();
        PayrollViews.Schedule current = schedule(id);
        EntityTags.requireMatch(ifMatch, current.version());
        MergePatch patch = MergePatch.of(document, Set.of("name", "payDayOffset", "isActive"));
        var name = patch.text("name", true, 100);
        var offset = patch.integer("payDayOffset", -31, 31);
        var active = patch.bool("isActive");
        patch.throwIfInvalid();
        String nextName = name.orElse(current.name());
        int nextOffset = offset.orElse(current.payDayOffset());
        boolean nextActive = Boolean.TRUE.equals(active.orElse(current.active()));
        if (!config.updateSchedule(
                companyId, id, current.version(), context.actor(), nextName, nextOffset, nextActive)) {
            throw new ApiException(PlatformErrorCode.VERSION_CONFLICT, "The schedule was modified concurrently.");
        }
        audit.record(AuditEvent.builder("UPDATE", "payroll")
                .entity("pay_schedule", id, current.code())
                .change("name", current.name(), nextName)
                .change("payDayOffset", current.payDayOffset(), nextOffset)
                .change("isActive", current.active(), nextActive)
                .build());
        return schedule(id);
    }

    /** Creates the schedule's periods of the year that do not exist yet. */
    @Transactional
    public List<PayrollViews.Period> generatePeriods(UUID scheduleId, int year) {
        UUID companyId = context.companyId();
        PayrollViews.Schedule schedule = schedule(scheduleId);
        if (!schedule.active()) {
            throw new ApiException(PlatformErrorCode.INVALID_STATE, "The pay schedule is inactive.");
        }
        int created = 0;
        for (PayPeriods.Period p :
                PayPeriods.forYear(schedule.frequency(), schedule.anchorDate(), year, schedule.payDayOffset())) {
            if (periods.insert(companyId, scheduleId, p.start(), p.end(), p.payDate(), context.actor())) {
                created++;
            }
        }
        audit.record(AuditEvent.builder("CREATE", "payroll")
                .entity("payroll_period", scheduleId, schedule.code())
                .detail("year", year)
                .detail("created", created)
                .build());
        return periods.forScheduleYear(companyId, scheduleId, year);
    }

    @Transactional(readOnly = true)
    public PageResponse<PayrollViews.Period> periods(ListQuery query) {
        return periods.list(context.companyId(), query);
    }

    @Transactional(readOnly = true)
    public PayrollViews.Period period(UUID id) {
        return periods.find(context.companyId(), id).orElseThrow(ApiException::notFound);
    }

    private static <E extends Enum<E>> @Nullable E parse(Class<E> type, String value) {
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static ApiException invalid(String pointer, String code, String message) {
        return ApiException.validationFailed(
                "The request is invalid.", List.of(FieldViolation.atPointer(pointer, code, message)));
    }
}
