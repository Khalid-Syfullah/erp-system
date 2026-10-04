package com.erp.payroll.application;

import com.erp.hr.api.HrFacade;
import com.erp.hr.events.EmployeeTerminated;
import com.erp.payroll.persistence.CompensationRepository;
import com.erp.payroll.persistence.PayrollConfigRepository;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditPort;
import com.erp.platform.web.ApiException;
import com.erp.platform.web.EntityTags;
import com.erp.platform.web.FieldViolation;
import com.erp.platform.web.PlatformErrorCode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Effective-dated employee compensations (PRODUCT_SPEC.md §11.1): pay schedule, salary structure,
 * base pay per period and component overrides. Compensations of one employee never overlap; a new
 * one ends the open-ended one before it. Readable only with {@code payroll.compensation.read}: salary
 * data never appears in HR responses.
 */
@Service
public class CompensationService {

    private final CompensationRepository compensations;
    private final PayrollConfigRepository config;
    private final HrFacade hr;
    private final PayrollContext context;
    private final AuditPort audit;

    CompensationService(
            CompensationRepository compensations,
            PayrollConfigRepository config,
            HrFacade hr,
            PayrollContext context,
            AuditPort audit) {
        this.compensations = compensations;
        this.config = config;
        this.hr = hr;
        this.context = context;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<PayrollViews.Compensation> forEmployee(UUID employeeId) {
        hr.employee(employeeId).orElseThrow(ApiException::notFound);
        return compensations.forEmployee(context.companyId(), employeeId);
    }

    @Transactional
    public PayrollViews.Compensation create(PayrollCommands.Compensation c) {
        UUID companyId = context.companyId();
        HrFacade.EmployeeInfo employee = hr.employee(c.employeeId()).orElseThrow(ApiException::notFound);
        List<FieldViolation> violations = new ArrayList<>();
        PayrollViews.Schedule schedule =
                config.schedule(companyId, c.payScheduleId()).orElse(null);
        if (schedule == null || !schedule.active()) {
            violations.add(
                    FieldViolation.atPointer("/payScheduleId", "UNKNOWN_SCHEDULE", "must be an active pay schedule"));
        }
        PayrollViews.Structure structure =
                config.structure(companyId, c.salaryStructureId()).orElse(null);
        if (structure == null || !structure.active()) {
            violations.add(FieldViolation.atPointer(
                    "/salaryStructureId", "UNKNOWN_STRUCTURE", "must be an active salary structure"));
        }
        if (c.effectiveTo() != null && c.effectiveTo().isBefore(c.effectiveFrom())) {
            violations.add(
                    FieldViolation.atPointer("/effectiveTo", "BEFORE_START", "must not be before effectiveFrom"));
        }
        if (c.effectiveFrom().isBefore(employee.hireDate())) {
            violations.add(
                    FieldViolation.atPointer("/effectiveFrom", "BEFORE_HIRE", "must not be before the hire date"));
        }
        if (employee.terminationDate() != null && c.effectiveFrom().isAfter(employee.terminationDate())) {
            violations.add(FieldViolation.atPointer(
                    "/effectiveFrom", "AFTER_TERMINATION", "must not be after the termination date"));
        }
        if (c.baseAmount().signum() < 0
                || c.baseAmount().stripTrailingZeros().scale()
                        > context.rounding().minorUnits()) {
            violations.add(FieldViolation.atPointer(
                    "/baseAmount",
                    "INVALID_VALUE",
                    "must be ≥ 0 with at most " + context.rounding().minorUnits() + " decimals"));
        }
        Map<UUID, PayrollViews.Component> components = config.components(
                companyId,
                c.overrides().stream()
                        .map(PayrollCommands.StructureLine::componentId)
                        .toList());
        Set<UUID> inStructure = new HashSet<>();
        if (structure != null) {
            structure.components().forEach(sc -> inStructure.add(sc.componentId()));
        }
        Set<UUID> seen = new HashSet<>();
        for (int i = 0; i < c.overrides().size(); i++) {
            PayrollCommands.StructureLine o = c.overrides().get(i);
            if (!components.containsKey(o.componentId()) || !inStructure.contains(o.componentId())) {
                violations.add(FieldViolation.atPointer(
                        "/overrides/" + i + "/componentId",
                        "NOT_IN_STRUCTURE",
                        "must be a component of the structure"));
            } else if (!seen.add(o.componentId())) {
                violations.add(
                        FieldViolation.atPointer("/overrides/" + i + "/componentId", "DUPLICATE", "appears twice"));
            } else if (o.rate() == null && o.amount() == null) {
                violations.add(FieldViolation.atPointer("/overrides/" + i, "REQUIRED", "needs a rate or an amount"));
            }
        }
        if (!violations.isEmpty()) {
            throw ApiException.validationFailed("The compensation is invalid.", violations);
        }
        // A new compensation ends the open-ended one that started before it.
        for (PayrollViews.Compensation existing : compensations.lockForEmployee(companyId, c.employeeId())) {
            if (existing.effectiveTo() == null && existing.effectiveFrom().isBefore(c.effectiveFrom())) {
                LocalDate end = c.effectiveFrom().minusDays(1);
                if (!compensations.updateEnd(companyId, existing.id(), existing.version(), context.actor(), end)) {
                    throw new ApiException(
                            PlatformErrorCode.VERSION_CONFLICT, "A compensation was modified concurrently.");
                }
                audit.record(AuditEvent.builder("UPDATE", "payroll")
                        .entity("employee_compensation", existing.id(), employee.employeeNumber())
                        .change("effectiveTo", null, end.toString())
                        .build());
            }
        }
        UUID id = compensations.insert(
                companyId, c, Objects.requireNonNull(schedule).currencyCode(), context.actor());
        audit.record(AuditEvent.builder("CREATE", "payroll")
                .entity("employee_compensation", id, employee.employeeNumber())
                .detail("effectiveFrom", c.effectiveFrom().toString())
                .detail("payScheduleId", c.payScheduleId())
                .detail("salaryStructureId", c.salaryStructureId())
                .redactedChange("baseAmount")
                .build());
        return compensations.find(companyId, id).orElseThrow();
    }

    /** Sets or clears the end date. */
    @Transactional
    public PayrollViews.Compensation end(UUID employeeId, UUID id, @Nullable String ifMatch, @Nullable LocalDate end) {
        UUID companyId = context.companyId();
        HrFacade.EmployeeInfo employee = hr.employee(employeeId).orElseThrow(ApiException::notFound);
        PayrollViews.Compensation current = compensations.lockForEmployee(companyId, employeeId).stream()
                .filter(x -> x.id().equals(id))
                .findFirst()
                .orElseThrow(ApiException::notFound);
        EntityTags.requireMatch(ifMatch, current.version());
        if (end != null && end.isBefore(current.effectiveFrom())) {
            throw PayrollSetupService.invalid("/effectiveTo", "BEFORE_START", "must not be before effectiveFrom");
        }
        if (!compensations.updateEnd(companyId, id, current.version(), context.actor(), end)) {
            throw new ApiException(PlatformErrorCode.VERSION_CONFLICT, "The compensation was modified concurrently.");
        }
        audit.record(AuditEvent.builder("UPDATE", "payroll")
                .entity("employee_compensation", id, employee.employeeNumber())
                .change("effectiveTo", Objects.toString(current.effectiveTo(), null), Objects.toString(end, null))
                .build());
        return compensations.find(companyId, id).orElseThrow();
    }

    /** Deletes a compensation that has not started yet. */
    @Transactional
    public void delete(UUID employeeId, UUID id, @Nullable String ifMatch) {
        UUID companyId = context.companyId();
        HrFacade.EmployeeInfo employee = hr.employee(employeeId).orElseThrow(ApiException::notFound);
        PayrollViews.Compensation current = compensations.lockForEmployee(companyId, employeeId).stream()
                .filter(x -> x.id().equals(id))
                .findFirst()
                .orElseThrow(ApiException::notFound);
        EntityTags.requireMatch(ifMatch, current.version());
        if (!current.effectiveFrom().isAfter(context.today())) {
            throw new ApiException(
                    PlatformErrorCode.INVALID_STATE, "A compensation that has started is ended, not deleted.");
        }
        compensations.delete(companyId, id);
        audit.record(AuditEvent.builder("DELETE", "payroll")
                .entity("employee_compensation", id, employee.employeeNumber())
                .detail("effectiveFrom", current.effectiveFrom().toString())
                .build());
    }

    /**
     * {@code hr.employee.terminated}: compensations end at the termination date, and those starting
     * after it are removed, so the last regular run pays the prorated final period.
     */
    @EventListener
    @Transactional
    public void on(EmployeeTerminated event) {
        UUID companyId = context.companyId();
        for (PayrollViews.Compensation c : compensations.lockForEmployee(companyId, event.employeeId())) {
            if (c.effectiveFrom().isAfter(event.terminationDate())) {
                compensations.delete(companyId, c.id());
            } else if (c.effectiveTo() == null || c.effectiveTo().isAfter(event.terminationDate())) {
                compensations.updateEnd(companyId, c.id(), c.version(), context.actorOrNull(), event.terminationDate());
            } else {
                continue;
            }
            audit.record(AuditEvent.builder("UPDATE", "payroll")
                    .entity("employee_compensation", c.id(), event.employeeNumber())
                    .detail("reason", "TERMINATION")
                    .detail("terminationDate", event.terminationDate().toString())
                    .build());
        }
    }
}
