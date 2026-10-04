package com.erp.payroll.application;

import com.erp.hr.api.HrFacade;
import com.erp.payroll.domain.RunStatus;
import com.erp.payroll.events.PayrollRunPaid;
import com.erp.payroll.events.PayrollRunPosted;
import com.erp.payroll.persistence.PayrollConfigRepository;
import com.erp.payroll.persistence.PayslipRepository;
import com.erp.payroll.persistence.PeriodRepository;
import com.erp.payroll.persistence.RunRepository;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditPort;
import com.erp.platform.events.DomainEvents;
import com.erp.platform.numbering.DocumentNumberService;
import com.erp.platform.numbering.FiscalYears;
import com.erp.platform.web.ApiException;
import com.erp.platform.web.EntityTags;
import com.erp.platform.web.PlatformErrorCode;
import com.erp.platform.web.paging.ListQuery;
import com.erp.platform.web.paging.PageResponse;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Payroll runs (PRODUCT_SPEC.md §11.1): every transition goes through {@link RunStatus}, checks
 * If-Match, locks the run and is audited.
 *
 * <ul>
 *   <li>Calculating queues the run ({@code CALCULATING}, {@code 202}); the calculation job pays it.
 *   <li>Approval needs a run without issues and an approver other than the user who requested the
 *       calculation, and not paid in it ({@code 403 SOD_VIOLATION}).
 *   <li>Posting numbers the run ({@code PR-{FY}-}), freezes its payslips, marks a regular run's period
 *       PROCESSED and publishes {@code payroll.run.posted}; Accounting books it in the same
 *       transaction, so a closed accounting period or a missing mapping refuses the posting.
 *   <li>Marking paid publishes {@code payroll.run.paid} with the Accounting bank account.
 * </ul>
 */
@Service
public class RunService {

    public static final String DOCUMENT_TYPE = "PAYROLL_RUN";

    /** A run with its calculation issues. */
    public record Detail(PayrollViews.Run run, List<PayrollViews.Issue> issues) {}

    private final RunRepository runs;
    private final PeriodRepository periods;
    private final PayrollConfigRepository config;
    private final PayslipRepository payslips;
    private final DocumentNumberService numbering;
    private final HrFacade hr;
    private final ApplicationEventPublisher events;
    private final PayrollContext context;
    private final AuditPort audit;

    RunService(
            RunRepository runs,
            PeriodRepository periods,
            PayrollConfigRepository config,
            PayslipRepository payslips,
            DocumentNumberService numbering,
            HrFacade hr,
            ApplicationEventPublisher events,
            PayrollContext context,
            AuditPort audit) {
        this.runs = runs;
        this.periods = periods;
        this.config = config;
        this.payslips = payslips;
        this.numbering = numbering;
        this.hr = hr;
        this.events = events;
        this.context = context;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public PageResponse<PayrollViews.Run> list(ListQuery query) {
        return runs.list(context.companyId(), query);
    }

    @Transactional(readOnly = true)
    public Detail get(UUID id) {
        UUID companyId = context.companyId();
        PayrollViews.Run run = runs.find(companyId, id).orElseThrow(ApiException::notFound);
        return new Detail(run, runs.issues(companyId, id));
    }

    @Transactional
    public Detail create(
            UUID periodId, String runType, @Nullable String description, @Nullable LocalDate accountingDate) {
        UUID companyId = context.companyId();
        PayrollViews.Period period = periods.lockForUse(companyId, periodId)
                .orElseThrow(() ->
                        PayrollSetupService.invalid("/payrollPeriodId", "UNKNOWN_PERIOD", "is not a payroll period"));
        if (!runType.equals("REGULAR") && !runType.equals("OFF_CYCLE")) {
            throw PayrollSetupService.invalid(
                    "/runType", "INVALID_VALUE", "must be REGULAR or OFF_CYCLE (final settlement runs: ADR-039)");
        }
        if (runType.equals("REGULAR") && !period.status().equals("OPEN")) {
            throw new ApiException(PlatformErrorCode.INVALID_STATE, "The period is " + period.status() + ".");
        }
        PayrollViews.Schedule schedule =
                config.schedule(companyId, period.payScheduleId()).orElseThrow();
        UUID id = runs.insert(
                companyId,
                periodId,
                runType,
                description == null || description.isBlank() ? null : description.strip(),
                accountingDate == null ? period.payDate() : accountingDate,
                schedule.currencyCode(),
                context.actor());
        audit.record(AuditEvent.builder("CREATE", "payroll")
                .entity("payroll_run", id, null)
                .detail("periodId", periodId)
                .detail("runType", runType)
                .build());
        return get(id);
    }

    /** Queues the (re)calculation; previous payslips and issues are removed. */
    @Transactional
    public Detail calculate(UUID id, @Nullable String ifMatch) {
        UUID companyId = context.companyId();
        PayrollViews.Run run = lock(id, ifMatch, RunStatus.Action.CALCULATE);
        payslips.deleteForRun(companyId, id);
        runs.deleteIssues(companyId, id);
        Object[] columns = concat(
                RunRepository.totals(RunRepository.Totals.ZERO),
                RunRepository.field("calculationRequestedBy"),
                context.actor(),
                RunRepository.field("calculatedAt"),
                null);
        transition(run, RunStatus.Action.CALCULATE, columns);
        return get(id);
    }

    @Transactional
    public Detail approve(UUID id, @Nullable String ifMatch) {
        UUID companyId = context.companyId();
        PayrollViews.Run run = lock(id, ifMatch, RunStatus.Action.APPROVE);
        if (!runs.issues(companyId, id).isEmpty()) {
            throw new ApiException(
                    PayrollErrorCode.RUN_HAS_ISSUES, "The run has calculation issues; resolve them and recalculate.");
        }
        UUID actor = context.actor();
        if (actor.equals(run.calculationRequestedBy())) {
            throw new ApiException(
                    PlatformErrorCode.SOD_VIOLATION, "The run is approved by someone other than who calculated it.");
        }
        hr.employeeOfUser(actor).ifPresent(employeeId -> {
            if (payslips.forRun(companyId, id).stream()
                    .anyMatch(p -> p.employeeId().equals(employeeId))) {
                throw new ApiException(PlatformErrorCode.SOD_VIOLATION, "You cannot approve a run that pays you.");
            }
        });
        transition(
                run,
                RunStatus.Action.APPROVE,
                RunRepository.field("approvedAt"),
                OffsetDateTime.now(context.clock()),
                RunRepository.field("approvedBy"),
                actor);
        return get(id);
    }

    @Transactional
    public Detail unapprove(UUID id, @Nullable String ifMatch) {
        PayrollViews.Run run = lock(id, ifMatch, RunStatus.Action.UNAPPROVE);
        transition(
                run,
                RunStatus.Action.UNAPPROVE,
                RunRepository.field("approvedAt"),
                null,
                RunRepository.field("approvedBy"),
                null);
        return get(id);
    }

    @Transactional
    public Detail post(UUID id, @Nullable String ifMatch) {
        UUID companyId = context.companyId();
        PayrollViews.Run run = lock(id, ifMatch, RunStatus.Action.POST);
        PayrollViews.Period period =
                periods.lockForUse(companyId, run.periodId()).orElseThrow();
        String number = numbering.next(
                companyId,
                DOCUMENT_TYPE,
                FiscalYears.label(run.accountingDate(), context.profile().fiscalYearStartMonth()));
        UUID actor = context.actor();
        transition(
                run,
                RunStatus.Action.POST,
                RunRepository.field("number"),
                number,
                RunRepository.field("postedAt"),
                OffsetDateTime.now(context.clock()),
                RunRepository.field("postedBy"),
                actor);
        if (run.runType().equals("REGULAR")) {
            periods.setStatus(companyId, period.id(), "PROCESSED", actor);
        }
        List<PayrollRunPosted.Line> lines = payslips.aggregate(companyId, id).stream()
                .map(a -> new PayrollRunPosted.Line(
                        a.componentId(), a.componentCode(), a.kind(), a.departmentId(), a.branchId(), a.amount()))
                .toList();
        events.publishEvent(new PayrollRunPosted(
                DomainEvents.metadata(
                        PayrollRunPosted.TYPE, PayrollRunPosted.SCHEMA_VERSION, companyId, context.clock()),
                id,
                number,
                run.periodId(),
                run.runType(),
                run.accountingDate(),
                run.currencyCode(),
                lines,
                run.grossTotal(),
                run.deductionTotal(),
                run.employerContributionTotal(),
                run.netTotal()));
        return get(id);
    }

    @Transactional
    public Detail markPaid(UUID id, @Nullable String ifMatch, UUID bankAccountId, LocalDate paymentDate) {
        UUID companyId = context.companyId();
        PayrollViews.Run run = lock(id, ifMatch, RunStatus.Action.PAY);
        transition(
                run,
                RunStatus.Action.PAY,
                RunRepository.field("paidAt"),
                OffsetDateTime.now(context.clock()),
                RunRepository.field("paidBy"),
                context.actor(),
                RunRepository.field("paymentDate"),
                paymentDate,
                RunRepository.field("paymentBankAccountId"),
                bankAccountId);
        events.publishEvent(new PayrollRunPaid(
                DomainEvents.metadata(PayrollRunPaid.TYPE, PayrollRunPaid.SCHEMA_VERSION, companyId, context.clock()),
                id,
                run.number(),
                bankAccountId,
                paymentDate,
                run.currencyCode(),
                run.netTotal()));
        return get(id);
    }

    @Transactional
    public Detail cancel(UUID id, @Nullable String ifMatch) {
        UUID companyId = context.companyId();
        PayrollViews.Run run = lock(id, ifMatch, RunStatus.Action.CANCEL);
        payslips.deleteForRun(companyId, id);
        transition(
                run, RunStatus.Action.CANCEL, RunRepository.field("cancelledAt"), OffsetDateTime.now(context.clock()));
        return get(id);
    }

    /** CALCULATED → DRAFT when its inputs change; payslips and issues are removed. */
    @Transactional
    public void reset(PayrollViews.Run run, String reason) {
        UUID companyId = context.companyId();
        payslips.deleteForRun(companyId, run.id());
        runs.deleteIssues(companyId, run.id());
        transition(run, RunStatus.Action.RESET, RunRepository.totals(RunRepository.Totals.ZERO));
        audit.record(AuditEvent.builder("UPDATE", "payroll")
                .entity("payroll_run", run.id(), run.number())
                .detail("reason", reason)
                .build());
    }

    private PayrollViews.Run lock(UUID id, @Nullable String ifMatch, RunStatus.Action action) {
        PayrollViews.Run run = runs.lock(context.companyId(), id).orElseThrow(ApiException::notFound);
        EntityTags.requireMatch(ifMatch, run.version());
        if (!run.status().allows(action)) {
            throw new ApiException(
                    PlatformErrorCode.INVALID_STATE,
                    "A " + run.status() + " payroll run does not allow "
                            + action.name().toLowerCase(Locale.ROOT) + ".");
        }
        return run;
    }

    private void transition(PayrollViews.Run run, RunStatus.Action action, Object... columns) {
        RunStatus target = run.status().apply(action);
        if (!runs.transition(context.companyId(), run.id(), run.version(), target, context.actorOrNull(), columns)) {
            throw new ApiException(PlatformErrorCode.VERSION_CONFLICT, "The payroll run was modified concurrently.");
        }
        audit.record(AuditEvent.builder("STATE_CHANGE", "payroll")
                .entity("payroll_run", run.id(), run.number())
                .transition(run.status().name(), target.name())
                .build());
    }

    private static Object[] concat(Object[] first, Object... second) {
        Object[] all = java.util.Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, all, first.length, second.length);
        return all;
    }
}
