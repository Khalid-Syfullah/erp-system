package com.erp.payroll.application;

import com.erp.payroll.persistence.PayslipRepository;
import com.erp.payroll.persistence.RunRepository;
import com.erp.platform.web.ApiException;
import com.erp.platform.web.FieldViolation;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Payroll reports as JSON (PAY-6): run summaries and component totals are aggregates
 * ({@code payroll.report.read}); the register lists each employee's pay and therefore also needs
 * {@code payroll.payslip.read}.
 */
@Service
public class PayrollReportService {

    /** Totals of a run, by department and by component. */
    public record RunSummary(
            PayrollViews.Run run, List<DepartmentTotal> departments, List<ComponentTotal> components) {}

    public record DepartmentTotal(
            UUID departmentId,
            int employees,
            BigDecimal gross,
            BigDecimal deductions,
            BigDecimal contributions,
            BigDecimal net) {}

    public record ComponentTotal(UUID componentId, String componentCode, String kind, BigDecimal amount) {}

    /** Every payslip of the run with its lines. */
    public record Register(PayrollViews.Run run, List<PayslipService.Detail> payslips) {}

    public record ComponentTotals(LocalDate from, LocalDate to, List<ComponentTotal> components) {}

    private final RunRepository runs;
    private final PayslipRepository payslips;
    private final PayrollContext context;

    PayrollReportService(RunRepository runs, PayslipRepository payslips, PayrollContext context) {
        this.runs = runs;
        this.payslips = payslips;
        this.context = context;
    }

    @Transactional(readOnly = true)
    public RunSummary summary(UUID runId) {
        UUID companyId = context.companyId();
        PayrollViews.Run run = runs.find(companyId, runId).orElseThrow(ApiException::notFound);
        Map<UUID, BigDecimal[]> byDepartment = new LinkedHashMap<>();
        Map<UUID, int[]> heads = new LinkedHashMap<>();
        for (PayrollViews.Payslip p : payslips.forRun(companyId, runId)) {
            BigDecimal[] t = byDepartment.computeIfAbsent(p.departmentId(), k ->
                    new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
            t[0] = t[0].add(p.grossAmount());
            t[1] = t[1].add(p.deductionAmount());
            t[2] = t[2].add(p.employerContributionAmount());
            t[3] = t[3].add(p.netAmount());
            heads.computeIfAbsent(p.departmentId(), k -> new int[1])[0]++;
        }
        List<DepartmentTotal> departments = new ArrayList<>();
        byDepartment.forEach(
                (id, t) -> departments.add(new DepartmentTotal(id, heads.get(id)[0], t[0], t[1], t[2], t[3])));
        Map<UUID, ComponentTotal> components = new LinkedHashMap<>();
        for (PayslipRepository.Aggregate a : payslips.aggregate(companyId, runId)) {
            components.merge(
                    a.componentId(),
                    new ComponentTotal(a.componentId(), a.componentCode(), a.kind(), a.amount()),
                    (x, y) -> new ComponentTotal(
                            x.componentId(),
                            x.componentCode(),
                            x.kind(),
                            x.amount().add(y.amount())));
        }
        return new RunSummary(run, departments, List.copyOf(components.values()));
    }

    @Transactional(readOnly = true)
    public Register register(UUID runId) {
        UUID companyId = context.companyId();
        PayrollViews.Run run = runs.find(companyId, runId).orElseThrow(ApiException::notFound);
        List<PayrollViews.Payslip> slips = payslips.forRun(companyId, runId);
        var lines = payslips.lines(
                companyId, slips.stream().map(PayrollViews.Payslip::id).toList());
        return new Register(
                run,
                slips.stream()
                        .map(p -> new PayslipService.Detail(p, lines.getOrDefault(p.id(), List.of()), run.status()))
                        .toList());
    }

    /** Component totals of posted runs with an accounting date in the range. */
    @Transactional(readOnly = true)
    public ComponentTotals componentTotals(LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            throw ApiException.validationFailed(
                    "The range is invalid.",
                    List.of(FieldViolation.atParameter("to", "INVALID_VALUE", "must not be before from")));
        }
        return new ComponentTotals(
                from,
                to,
                payslips.postedComponentTotals(context.companyId(), from, to).stream()
                        .map(a -> new ComponentTotal(a.componentId(), a.componentCode(), a.kind(), a.amount()))
                        .toList());
    }
}
