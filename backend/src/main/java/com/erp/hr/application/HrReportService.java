package com.erp.hr.application;

import com.erp.hr.persistence.EmployeeRepository;
import com.erp.hr.persistence.EmploymentAssignmentRepository;
import com.erp.platform.context.CurrentContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** HR reports as JSON (headcount); the reporting views follow with Phase 10. */
@Service
public class HrReportService {

    private final EmployeeRepository employees;
    private final EmploymentAssignmentRepository assignments;
    private final HrContext context;

    HrReportService(EmployeeRepository employees, EmploymentAssignmentRepository assignments, HrContext context) {
        this.employees = employees;
        this.assignments = assignments;
        this.context = context;
    }

    /** Employees employed on the date, by branch and department of their assignment then, in the branch scope. */
    @Transactional(readOnly = true)
    public HrViews.Headcount headcount(LocalDate asOf) {
        UUID companyId = context.companyId();
        Set<UUID> scope = CurrentContext.require().branchScope();
        Map<UUID, EmployeeView> employed = new HashMap<>();
        employees.employedBetween(companyId, asOf, asOf).forEach(e -> employed.put(e.id(), e));
        Map<List<UUID>, int[]> counts = new HashMap<>();
        Map<List<UUID>, BigDecimal> fte = new HashMap<>();
        Map<String, Integer> byStatus = new TreeMap<>();
        int total = 0;
        for (AssignmentView a : assignments.allEffectiveOn(companyId, asOf)) {
            EmployeeView e = employed.get(a.employeeId());
            if (e == null || (scope != null && !scope.contains(a.branchId()))) {
                continue;
            }
            List<UUID> key = List.of(a.branchId(), a.departmentId());
            counts.computeIfAbsent(key, k -> new int[1])[0]++;
            fte.merge(key, a.fte(), BigDecimal::add);
            byStatus.merge(e.status().name(), 1, Integer::sum);
            total++;
        }
        List<HrViews.HeadcountRow> rows = new ArrayList<>();
        counts.forEach(
                (key, count) -> rows.add(new HrViews.HeadcountRow(key.get(0), key.get(1), count[0], fte.get(key))));
        rows.sort(Comparator.comparing((HrViews.HeadcountRow r) -> r.branchId().toString())
                .thenComparing(r -> r.departmentId().toString()));
        return new HrViews.Headcount(asOf, total, rows, byStatus);
    }
}
