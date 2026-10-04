package com.erp.payroll.application;

import com.erp.hr.api.HrFacade;
import com.erp.payroll.domain.Calculation;
import com.erp.payroll.domain.ComponentKind;
import com.erp.payroll.domain.PayrollCalculator;
import com.erp.payroll.domain.RunStatus;
import com.erp.payroll.persistence.CompensationRepository;
import com.erp.payroll.persistence.InputRepository;
import com.erp.payroll.persistence.PayrollConfigRepository;
import com.erp.payroll.persistence.PayslipRepository;
import com.erp.payroll.persistence.PeriodRepository;
import com.erp.payroll.persistence.RunRepository;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditPort;
import com.erp.platform.money.RoundingPolicy;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pays one queued run (PRODUCT_SPEC.md §11.2): loads the period's compensations, assignments,
 * inputs and components in bulk, builds one {@link PayrollCalculator.Request} per employee (in
 * employee-number order) and stores the payslips in batches. The whole run is calculated in the
 * claiming transaction, so its payslips appear together or not at all (ADR-039).
 *
 * <p>PAY-1: an employee is paid for the days of the period on which they are employed, have an
 * assignment and a compensation on the period's schedule; those days form one segment per
 * compensation. Employees with a compensation but no assignment, or with a negative net, become run
 * issues instead of payslips.
 */
@Service
public class PayrollEngine {

    static final int BATCH = 250;

    private final RunRepository runs;
    private final PeriodRepository periods;
    private final PayrollConfigRepository config;
    private final CompensationRepository compensations;
    private final InputRepository inputs;
    private final PayslipRepository payslips;
    private final PayrollCalculator calculator;
    private final HrFacade hr;
    private final PayrollContext context;
    private final AuditPort audit;

    PayrollEngine(
            RunRepository runs,
            PeriodRepository periods,
            PayrollConfigRepository config,
            CompensationRepository compensations,
            InputRepository inputs,
            PayslipRepository payslips,
            PayrollCalculator calculator,
            HrFacade hr,
            PayrollContext context,
            AuditPort audit) {
        this.runs = runs;
        this.periods = periods;
        this.config = config;
        this.compensations = compensations;
        this.inputs = inputs;
        this.payslips = payslips;
        this.calculator = calculator;
        this.hr = hr;
        this.context = context;
        this.audit = audit;
    }

    /** The company's oldest queued run, if any (not locked). */
    @Transactional(readOnly = true)
    public @Nullable UUID nextQueued() {
        return runs.queued(context.companyId()).orElse(null);
    }

    /**
     * Calculates the run if it is still queued and no other worker holds it; returns whether it did.
     * Runs in its own transaction.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean calculate(UUID runId) {
        UUID companyId = context.companyId();
        PayrollViews.Run run = runs.claim(companyId, runId).orElse(null);
        if (run == null || run.status() != RunStatus.CALCULATING) {
            return false;
        }
        RunRepository.Totals totals = calculate(run);
        boolean done = runs.transition(
                companyId,
                run.id(),
                run.version(),
                RunStatus.CALCULATED,
                run.calculationRequestedBy(),
                concat(
                        RunRepository.totals(totals),
                        RunRepository.field("calculatedAt"),
                        OffsetDateTime.now(context.clock()),
                        RunRepository.field("calculatedBy"),
                        run.calculationRequestedBy()));
        if (!done) {
            throw new IllegalStateException("Payroll run " + run.id() + " changed during its calculation");
        }
        audit.record(AuditEvent.builder("CALCULATE", "payroll")
                .entity("payroll_run", run.id(), null)
                .transition(RunStatus.CALCULATING.name(), RunStatus.CALCULATED.name())
                .detail("employees", totals.employees())
                .detail("issues", runs.issues(companyId, run.id()).size())
                .build());
        return true;
    }

    /** Records a failed calculation: the run goes back to DRAFT with the reason as an issue. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(UUID runId, String message) {
        UUID companyId = context.companyId();
        PayrollViews.Run run = runs.lock(companyId, runId).orElse(null);
        if (run == null || run.status() != RunStatus.CALCULATING) {
            return;
        }
        runs.deleteIssues(companyId, runId);
        runs.insertIssue(companyId, runId, null, "CALCULATION_FAILED", message);
        runs.transition(companyId, runId, run.version(), RunStatus.DRAFT, run.calculationRequestedBy());
        audit.record(AuditEvent.builder("CALCULATE", "payroll")
                .entity("payroll_run", runId, null)
                .transition(RunStatus.CALCULATING.name(), RunStatus.DRAFT.name())
                .detail("error", message)
                .build());
    }

    RunRepository.Totals calculate(PayrollViews.Run run) {
        UUID companyId = context.companyId();
        PayrollViews.Period period = periods.find(companyId, run.periodId()).orElseThrow();
        boolean offCycle = run.runType().equals("OFF_CYCLE");
        boolean workingDays = config.settings(companyId)
                .map(s -> s.prorationBasis().equals("WORKING_DAYS"))
                .orElse(false);
        RoundingPolicy rounding = context.rounding();
        String country = context.countryCode();

        List<PayrollViews.Input> runInputs = inputs.forRun(companyId, period.id(), offCycle ? run.id() : null);
        Set<UUID> inputEmployees = new LinkedHashSet<>();
        runInputs.forEach(i -> inputEmployees.add(i.employeeId()));
        List<PayrollViews.Compensation> comps = compensations.forPeriod(
                companyId,
                period.payScheduleId(),
                period.startDate(),
                period.endDate(),
                offCycle ? inputEmployees : null);
        Map<UUID, List<PayrollViews.Compensation>> byEmployee = new LinkedHashMap<>();
        comps.forEach(c -> byEmployee
                .computeIfAbsent(c.employeeId(), k -> new ArrayList<>())
                .add(c));
        Set<UUID> employeeIds = new LinkedHashSet<>(byEmployee.keySet());
        if (offCycle) {
            employeeIds.addAll(inputEmployees);
        }

        Map<UUID, HrFacade.EmployeeInfo> employees = hr.employees(employeeIds);
        Map<UUID, List<HrFacade.AssignmentSpan>> assignments = new HashMap<>();
        for (HrFacade.AssignmentSpan a : hr.assignments(employeeIds, period.startDate(), period.endDate())) {
            assignments.computeIfAbsent(a.employeeId(), k -> new ArrayList<>()).add(a);
        }
        Map<UUID, List<PayrollViews.Input>> inputsByEmployee = new HashMap<>();
        runInputs.forEach(i -> inputsByEmployee
                .computeIfAbsent(i.employeeId(), k -> new ArrayList<>())
                .add(i));

        Set<UUID> structureIds = new HashSet<>();
        comps.forEach(c -> structureIds.add(c.salaryStructureId()));
        Map<UUID, List<PayrollViews.StructureComponent>> structures =
                config.structureComponents(companyId, structureIds);
        Set<UUID> componentIds = new HashSet<>();
        structures.values().forEach(list -> list.forEach(sc -> componentIds.add(sc.componentId())));
        runInputs.forEach(i -> componentIds.add(i.componentId()));
        Map<UUID, PayrollViews.Component> components = config.components(companyId, componentIds);

        int calendarDays = (int) ChronoUnit.DAYS.between(period.startDate(), period.endDate()) + 1;
        List<PayslipRepository.NewPayslip> batch = new ArrayList<>();
        int paid = 0;
        BigDecimal gross = BigDecimal.ZERO;
        BigDecimal deductions = BigDecimal.ZERO;
        BigDecimal contributions = BigDecimal.ZERO;
        List<UUID> ordered = new ArrayList<>(employeeIds);
        ordered.sort(Comparator.comparing(
                id -> employees.containsKey(id) ? employees.get(id).employeeNumber() : id.toString()));
        for (UUID employeeId : ordered) {
            HrFacade.EmployeeInfo employee = employees.get(employeeId);
            if (employee == null) {
                continue;
            }
            List<PayrollViews.Compensation> employeeComps = byEmployee.getOrDefault(employeeId, List.of());
            if (employeeComps.isEmpty()) {
                runs.insertIssue(
                        companyId,
                        run.id(),
                        employeeId,
                        "NO_COMPENSATION",
                        employee.employeeNumber() + " has inputs but no compensation on the period's schedule.");
                continue;
            }
            LocalDate from = max(period.startDate(), employee.hireDate());
            LocalDate to = employee.terminationDate() == null
                    ? period.endDate()
                    : min(period.endDate(), employee.terminationDate());
            if (from.isAfter(to)) {
                continue;
            }
            List<HrFacade.AssignmentSpan> spans = assignments.getOrDefault(employeeId, List.of()).stream()
                    .sorted(Comparator.comparing(HrFacade.AssignmentSpan::from))
                    .toList();
            HrFacade.AssignmentSpan last = null;
            UUID lastBranch = spans.isEmpty() ? null : spans.getLast().branchId();
            int periodDays =
                    workingDays ? hr.workingDays(lastBranch, period.startDate(), period.endDate()) : calendarDays;
            List<PayrollCalculator.Segment> segments = new ArrayList<>();
            for (PayrollViews.Compensation comp : employeeComps) {
                LocalDate compFrom = max(from, comp.effectiveFrom());
                LocalDate compTo = comp.effectiveTo() == null ? to : min(to, comp.effectiveTo());
                int days = 0;
                for (HrFacade.AssignmentSpan span : spans) {
                    LocalDate a = max(compFrom, span.from());
                    LocalDate b = span.to() == null ? compTo : min(compTo, span.to());
                    if (!a.isAfter(b)) {
                        days += workingDays
                                ? hr.workingDays(span.branchId(), a, b)
                                : (int) ChronoUnit.DAYS.between(a, b) + 1;
                        if (last == null || !b.isBefore(lastEnd(last, to))) {
                            last = span;
                        }
                    }
                }
                if (days > 0) {
                    segments.add(new PayrollCalculator.Segment(
                            days, comp.baseAmount(), values(comp, structures, components)));
                }
            }
            if (segments.isEmpty() || last == null) {
                runs.insertIssue(
                        companyId,
                        run.id(),
                        employeeId,
                        "NO_ASSIGNMENT",
                        employee.employeeNumber() + " has a compensation but no employment assignment in the period.");
                continue;
            }
            if (periodDays <= 0) {
                runs.insertIssue(
                        companyId,
                        run.id(),
                        employeeId,
                        "NO_WORKING_DAYS",
                        "The period has no working days for " + employee.employeeNumber() + ".");
                continue;
            }
            List<PayrollViews.Input> employeeInputs = inputsByEmployee.getOrDefault(employeeId, List.of());
            Map<UUID, PayrollCalculator.Component> involved = new TreeMap<>();
            for (PayrollViews.Compensation comp : employeeComps) {
                for (PayrollViews.StructureComponent sc :
                        structures.getOrDefault(comp.salaryStructureId(), List.of())) {
                    PayrollViews.Component c = components.get(sc.componentId());
                    if (c != null && c.active()) {
                        involved.put(c.id(), engineComponent(c));
                    }
                }
            }
            Map<UUID, BigDecimal> inputRates = new HashMap<>();
            for (PayrollViews.Input in : employeeInputs) {
                PayrollViews.Component c = components.get(in.componentId());
                if (c == null) {
                    continue;
                }
                involved.put(c.id(), engineComponent(c));
                PayrollCalculator.Value v = segments.getLast().values().get(c.id());
                BigDecimal rate = v != null && v.rate() != null ? v.rate() : c.defaultRate();
                if (rate != null) {
                    inputRates.put(c.id(), rate);
                }
            }
            PayrollCalculator.Result result = calculator.calculate(new PayrollCalculator.Request(
                    employeeId,
                    period.startDate(),
                    period.endDate(),
                    workingDays ? periodDays : calendarDays,
                    segments,
                    List.copyOf(involved.values()),
                    employeeInputs.stream()
                            .map(i -> new PayrollCalculator.Input(i.componentId(), i.quantity(), i.amount()))
                            .toList(),
                    inputRates,
                    offCycle,
                    run.currencyCode(),
                    country,
                    rounding));
            if (result.negativeNet()) {
                runs.insertIssue(
                        companyId,
                        run.id(),
                        employeeId,
                        "NEGATIVE_NET",
                        employee.employeeNumber() + " would be paid "
                                + result.net().toPlainString() + "; deductions exceed the gross.");
                continue;
            }
            if (result.lines().isEmpty()) {
                continue;
            }
            UUID payslipId = payslips.newId();
            batch.add(new PayslipRepository.NewPayslip(
                    new PayrollViews.Payslip(
                            payslipId,
                            run.id(),
                            employeeId,
                            employee.employeeNumber(),
                            employee.displayName(),
                            last.branchId(),
                            last.departmentId(),
                            last.positionTitle(),
                            result.daysPaid(),
                            workingDays ? periodDays : calendarDays,
                            result.base(),
                            result.taxableGross(),
                            result.gross(),
                            result.deductions(),
                            result.contributions(),
                            result.net(),
                            run.currencyCode(),
                            null,
                            0),
                    result.lines().stream()
                            .map(l -> new PayrollViews.PayslipLine(
                                    l.component().id(),
                                    l.component().code(),
                                    l.component().name(),
                                    l.component().kind().name(),
                                    l.component().taxable(),
                                    l.quantity(),
                                    l.rate(),
                                    l.amount(),
                                    l.component().sequence()))
                            .toList()));
            paid++;
            gross = gross.add(result.gross());
            deductions = deductions.add(result.deductions());
            contributions = contributions.add(result.contributions());
            if (batch.size() >= BATCH) {
                payslips.insert(companyId, run.id(), batch, run.calculationRequestedBy());
                batch.clear();
            }
        }
        payslips.insert(companyId, run.id(), batch, run.calculationRequestedBy());
        return new RunRepository.Totals(paid, gross, deductions, contributions, gross.subtract(deductions));
    }

    /** Resolved values of the compensation's structure: override, else structure, else component default. */
    private static Map<UUID, PayrollCalculator.Value> values(
            PayrollViews.Compensation comp,
            Map<UUID, List<PayrollViews.StructureComponent>> structures,
            Map<UUID, PayrollViews.Component> components) {
        Map<UUID, PayrollViews.Override> overrides = new HashMap<>();
        comp.overrides().forEach(o -> overrides.put(o.componentId(), o));
        Map<UUID, PayrollCalculator.Value> values = new HashMap<>();
        for (PayrollViews.StructureComponent sc : structures.getOrDefault(comp.salaryStructureId(), List.of())) {
            PayrollViews.Component c = components.get(sc.componentId());
            if (c == null || !c.active()) {
                continue;
            }
            PayrollViews.Override o = overrides.get(c.id());
            BigDecimal rate =
                    o != null && o.rate() != null ? o.rate() : sc.rate() != null ? sc.rate() : c.defaultRate();
            BigDecimal amount = o != null && o.amount() != null
                    ? o.amount()
                    : sc.amount() != null ? sc.amount() : c.defaultAmount();
            values.put(c.id(), new PayrollCalculator.Value(rate, amount));
        }
        return values;
    }

    static PayrollCalculator.Component engineComponent(PayrollViews.Component c) {
        return new PayrollCalculator.Component(
                c.id(),
                c.code(),
                c.name(),
                ComponentKind.valueOf(c.kind()),
                Calculation.valueOf(c.calculation()),
                c.taxable(),
                c.statutoryRuleCode(),
                c.sequence());
    }

    private static LocalDate lastEnd(HrFacade.AssignmentSpan span, LocalDate cap) {
        return span.to() == null ? cap : min(span.to(), cap);
    }

    private static LocalDate max(LocalDate a, LocalDate b) {
        return a.isAfter(b) ? a : b;
    }

    private static LocalDate min(LocalDate a, LocalDate b) {
        return a.isBefore(b) ? a : b;
    }

    private static Object[] concat(Object[] first, Object... second) {
        Object[] all = java.util.Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, all, first.length, second.length);
        return all;
    }
}
