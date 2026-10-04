package com.erp.payroll.application;

import com.erp.hr.api.HrFacade;
import com.erp.payroll.domain.RunStatus;
import com.erp.payroll.persistence.InputRepository;
import com.erp.payroll.persistence.PayrollConfigRepository;
import com.erp.payroll.persistence.PeriodRepository;
import com.erp.payroll.persistence.RunRepository;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditPort;
import com.erp.platform.web.ApiException;
import com.erp.platform.web.EntityTags;
import com.erp.platform.web.FieldViolation;
import com.erp.platform.web.PlatformErrorCode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Period inputs (overtime hours, bonuses …) for INPUT components (PRODUCT_SPEC.md §11.1). An input
 * belongs to the period's regular run, or to one off-cycle run. Changing inputs sends a calculated
 * run back to DRAFT (its payslips are removed); inputs of an approved or later run are frozen.
 * Inputs are never negative (PAY-4).
 */
@Service
public class InputService {

    private final InputRepository inputs;
    private final PeriodRepository periods;
    private final RunRepository runs;
    private final PayrollConfigRepository config;
    private final RunService runService;
    private final HrFacade hr;
    private final PayrollContext context;
    private final AuditPort audit;

    InputService(
            InputRepository inputs,
            PeriodRepository periods,
            RunRepository runs,
            PayrollConfigRepository config,
            RunService runService,
            HrFacade hr,
            PayrollContext context,
            AuditPort audit) {
        this.inputs = inputs;
        this.periods = periods;
        this.runs = runs;
        this.config = config;
        this.runService = runService;
        this.hr = hr;
        this.context = context;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<PayrollViews.Input> list(UUID periodId) {
        period(periodId);
        return inputs.forPeriod(context.companyId(), periodId);
    }

    @Transactional
    public PayrollViews.Input create(UUID periodId, PayrollCommands.Input c) {
        UUID companyId = context.companyId();
        PayrollViews.Period period = lockPeriod(periodId);
        validate(c);
        HrFacade.EmployeeInfo employee = hr.employee(c.employeeId())
                .orElseThrow(
                        () -> PayrollSetupService.invalid("/employeeId", "UNKNOWN_EMPLOYEE", "is not an employee"));
        unfreeze(period, c.runId());
        UUID id = inputs.insert(companyId, periodId, c, context.actor());
        audit.record(AuditEvent.builder("CREATE", "payroll")
                .entity("payroll_input", id, employee.employeeNumber())
                .detail("periodId", periodId)
                .detail("componentId", c.componentId())
                .detail("runId", c.runId())
                .build());
        return inputs.find(companyId, periodId, id).orElseThrow();
    }

    @Transactional
    public PayrollViews.Input update(
            UUID periodId,
            UUID id,
            @Nullable String ifMatch,
            @Nullable BigDecimal quantity,
            @Nullable BigDecimal amount,
            @Nullable String note) {
        UUID companyId = context.companyId();
        PayrollViews.Period period = lockPeriod(periodId);
        PayrollViews.Input current = inputs.find(companyId, periodId, id).orElseThrow(ApiException::notFound);
        EntityTags.requireMatch(ifMatch, current.version());
        validate(new PayrollCommands.Input(
                current.employeeId(), current.componentId(), current.runId(), quantity, amount, note));
        unfreeze(period, current.runId());
        if (!inputs.update(companyId, id, current.version(), context.actor(), quantity, amount, note)) {
            throw new ApiException(PlatformErrorCode.VERSION_CONFLICT, "The input was modified concurrently.");
        }
        audit.record(AuditEvent.builder("UPDATE", "payroll")
                .entity("payroll_input", id, null)
                .change("quantity", current.quantity(), quantity)
                .change("amount", current.amount(), amount)
                .build());
        return inputs.find(companyId, periodId, id).orElseThrow();
    }

    @Transactional
    public void delete(UUID periodId, UUID id) {
        UUID companyId = context.companyId();
        PayrollViews.Period period = lockPeriod(periodId);
        PayrollViews.Input current = inputs.find(companyId, periodId, id).orElseThrow(ApiException::notFound);
        unfreeze(period, current.runId());
        inputs.delete(companyId, id);
        audit.record(AuditEvent.builder("DELETE", "payroll")
                .entity("payroll_input", id, null)
                .detail("employeeId", current.employeeId())
                .build());
    }

    private void validate(PayrollCommands.Input c) {
        List<FieldViolation> violations = new ArrayList<>();
        PayrollViews.Component component =
                config.component(context.companyId(), c.componentId()).orElse(null);
        if (component == null || !component.active() || !component.calculation().equals("INPUT")) {
            violations.add(
                    FieldViolation.atPointer("/componentId", "NOT_AN_INPUT", "must be an active INPUT component"));
        }
        if ((c.quantity() == null) == (c.amount() == null)) {
            violations.add(FieldViolation.atPointer("/amount", "INVALID_VALUE", "give either a quantity or an amount"));
        }
        if (c.amount() != null
                && (c.amount().signum() <= 0
                        || c.amount().stripTrailingZeros().scale()
                                > context.rounding().minorUnits())) {
            violations.add(FieldViolation.atPointer("/amount", "INVALID_VALUE", "must be positive in currency units"));
        }
        if (c.quantity() != null
                && (c.quantity().signum() <= 0
                        || c.quantity().stripTrailingZeros().scale() > 6)) {
            violations.add(FieldViolation.atPointer("/quantity", "INVALID_VALUE", "must be positive"));
        }
        if (!violations.isEmpty()) {
            throw ApiException.validationFailed("The input is invalid.", violations);
        }
    }

    /** The run the input belongs to must still be open; a calculated one goes back to DRAFT. */
    private void unfreeze(PayrollViews.Period period, @Nullable UUID runId) {
        UUID companyId = context.companyId();
        for (PayrollViews.Run run : runs.forPeriod(companyId, period.id())) {
            boolean concerned =
                    runId == null ? run.runType().equals("REGULAR") : run.id().equals(runId);
            if (!concerned || run.status() == RunStatus.CANCELLED) {
                continue;
            }
            switch (run.status()) {
                case DRAFT -> {}
                case CALCULATED -> runService.reset(run, "Inputs changed");
                default ->
                    throw new ApiException(
                            PlatformErrorCode.INVALID_STATE,
                            "The " + run.runType().toLowerCase(java.util.Locale.ROOT) + " run is " + run.status()
                                    + "; its inputs can no longer change.");
            }
        }
        if (runId != null) {
            PayrollViews.Run run = runs.find(companyId, runId)
                    .filter(r -> r.periodId().equals(period.id()) && r.runType().equals("OFF_CYCLE"))
                    .orElseThrow(() -> PayrollSetupService.invalid(
                            "/runId", "UNKNOWN_RUN", "must be an off-cycle run of the period"));
            if (run.status() != RunStatus.DRAFT && run.status() != RunStatus.CALCULATED) {
                throw new ApiException(PlatformErrorCode.INVALID_STATE, "The off-cycle run is " + run.status() + ".");
            }
        }
    }

    private PayrollViews.Period period(UUID periodId) {
        return periods.find(context.companyId(), periodId).orElseThrow(ApiException::notFound);
    }

    private PayrollViews.Period lockPeriod(UUID periodId) {
        return periods.lockForUse(context.companyId(), periodId).orElseThrow(ApiException::notFound);
    }
}
