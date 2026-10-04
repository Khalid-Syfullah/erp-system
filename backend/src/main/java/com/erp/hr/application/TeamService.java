package com.erp.hr.application;

import com.erp.hr.domain.ReportingLines;
import com.erp.hr.persistence.EmployeeRepository;
import com.erp.hr.persistence.EmploymentAssignmentRepository;
import com.erp.platform.web.ApiException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A manager's direct and indirect reports on a date (HR-3), followed through the assignments
 * effective that day. Managers see their reports' basic data and decide their leave requests.
 */
@Service
public class TeamService {

    private final EmployeeRepository employees;
    private final EmploymentAssignmentRepository assignments;
    private final HrContext context;

    TeamService(EmployeeRepository employees, EmploymentAssignmentRepository assignments, HrContext context) {
        this.employees = employees;
        this.assignments = assignments;
        this.context = context;
    }

    /** The current user's employee record, or {@code 404 NOT_AN_EMPLOYEE}. */
    @Transactional(readOnly = true)
    public EmployeeView me() {
        return employees
                .findByUser(context.companyId(), context.actor())
                .orElseThrow(() -> new ApiException(
                        HrErrorCode.NOT_AN_EMPLOYEE, "You are not linked to an employee record in this company."));
    }

    /** Everyone reporting to the manager, directly or indirectly, on the date. */
    public Set<UUID> reportsOf(UUID companyId, UUID managerEmployeeId, LocalDate date) {
        Set<UUID> reports = new LinkedHashSet<>();
        Set<UUID> frontier = Set.of(managerEmployeeId);
        for (int depth = 0; depth < ReportingLines.MAX_DEPTH && !frontier.isEmpty(); depth++) {
            Set<UUID> next = new HashSet<>();
            for (AssignmentView a : assignments.reportsOn(companyId, frontier, date)) {
                if (!a.employeeId().equals(managerEmployeeId) && reports.add(a.employeeId())) {
                    next.add(a.employeeId());
                }
            }
            frontier = next;
        }
        return reports;
    }

    /** Whether {@code managerEmployeeId} is above {@code employeeId} in the reporting line on the date. */
    public boolean manages(UUID companyId, UUID managerEmployeeId, UUID employeeId, LocalDate date) {
        UUID current = employeeId;
        for (int depth = 0; depth < ReportingLines.MAX_DEPTH; depth++) {
            AssignmentView a =
                    assignments.effectiveOn(companyId, List.of(current), date).get(current);
            if (a == null || a.managerEmployeeId() == null) {
                return false;
            }
            if (a.managerEmployeeId().equals(managerEmployeeId)) {
                return true;
            }
            current = a.managerEmployeeId();
        }
        return false;
    }

    /** The current user's team: basic data of their reports, no sensitive fields or pay (HR-3). */
    @Transactional(readOnly = true)
    public List<HrViews.TeamMember> team() {
        UUID companyId = context.companyId();
        LocalDate today = context.today();
        EmployeeView manager = me();
        Set<UUID> ids = reportsOf(companyId, manager.id(), today);
        Map<UUID, AssignmentView> current = assignments.effectiveOn(companyId, ids, today);
        List<HrViews.TeamMember> team = new ArrayList<>();
        for (EmployeeView e : employees.findAll(companyId, ids)) {
            AssignmentView a = current.get(e.id());
            if (a == null) {
                continue;
            }
            team.add(new HrViews.TeamMember(
                    e.id(),
                    e.employeeNumber(),
                    e.displayName(),
                    e.status().name(),
                    a.managerEmployeeId(),
                    a.branchId(),
                    a.departmentId(),
                    a.positionId(),
                    e.workEmail()));
        }
        team.sort(Comparator.comparing(HrViews.TeamMember::employeeNumber));
        return team;
    }
}
