package com.erp.hr.application;

import com.erp.hr.persistence.DepartmentHeadRepository;
import com.erp.hr.persistence.EmploymentAssignmentRepository;
import com.erp.hr.persistence.PositionRepository;
import com.erp.org.api.OrganizationUsage;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Tells Org which branches and departments HR still uses (PRODUCT_SPEC.md §4.2): current or future
 * employment assignments, active positions and current or future department heads.
 */
@Component
class HrOrganizationUsage implements OrganizationUsage {

    private final EmploymentAssignmentRepository assignments;
    private final PositionRepository positions;
    private final DepartmentHeadRepository heads;
    private final HrCalendar calendar;

    HrOrganizationUsage(
            EmploymentAssignmentRepository assignments,
            PositionRepository positions,
            DepartmentHeadRepository heads,
            HrCalendar calendar) {
        this.assignments = assignments;
        this.positions = positions;
        this.heads = heads;
        this.calendar = calendar;
    }

    @Override
    public List<String> branchUsage(UUID companyId, UUID branchId) {
        int count = assignments.countCurrentOrFutureInBranch(companyId, branchId, calendar.today(companyId));
        return count == 0 ? List.of() : List.of(count + " current or future employment assignment(s)");
    }

    @Override
    public List<String> departmentUsage(UUID companyId, UUID departmentId) {
        LocalDate today = calendar.today(companyId);
        List<String> uses = new ArrayList<>();
        int count = assignments.countCurrentOrFutureInDepartment(companyId, departmentId, today);
        if (count > 0) {
            uses.add(count + " current or future employment assignment(s)");
        }
        List<String> positionCodes = positions.activeCodesInDepartment(companyId, departmentId);
        if (!positionCodes.isEmpty()) {
            uses.add("active positions " + String.join(", ", positionCodes));
        }
        int headships = heads.countCurrentOrFutureInDepartment(companyId, departmentId, today);
        if (headships > 0) {
            uses.add(headships + " current or future department head assignment(s)");
        }
        return uses;
    }
}
