package com.erp.hr.application;

import static com.erp.platform.web.paging.FilterOperator.EQ;
import static com.erp.platform.web.paging.FilterOperator.GTE;
import static com.erp.platform.web.paging.FilterOperator.IN;
import static com.erp.platform.web.paging.FilterOperator.IS_NULL;
import static com.erp.platform.web.paging.FilterOperator.LIKE;
import static com.erp.platform.web.paging.FilterOperator.LTE;

import com.erp.platform.web.paging.ListDefinition;
import com.erp.platform.web.paging.SortOrder;
import com.erp.platform.web.paging.ValueType;
import java.util.Set;

/** List contracts of the HR endpoints (API.md §17.9). */
public final class HrListings {

    public static final ListDefinition POSITIONS = ListDefinition.builder("hr.positions")
            .sortable("code", "title", "createdAt")
            .defaultSort(SortOrder.asc("code"))
            .filter("code", ValueType.STRING, EQ, IN, LIKE)
            .filter("departmentId", ValueType.UUID, EQ, IN, IS_NULL)
            .filter("grade", ValueType.STRING, EQ, IN)
            .filter("isActive", ValueType.BOOLEAN, EQ)
            .searchable()
            .build();

    public static final ListDefinition EMPLOYEES = ListDefinition.builder("hr.employees")
            .sortable("employeeNumber", "lastName", "hireDate", "createdAt")
            .defaultSort(SortOrder.asc("employeeNumber"))
            .filter("employeeNumber", ValueType.STRING, EQ, IN, LIKE)
            .enumFilter("status", Set.of("ONBOARDING", "ACTIVE", "ON_LEAVE", "TERMINATED"), EQ, IN)
            .filter("hireDate", ValueType.DATE, EQ, GTE, LTE)
            .filter("workEmail", ValueType.STRING, EQ, LIKE)
            .searchable()
            .build();

    /** Assignments; {@code asOf=yyyy-MM-dd} (outside the filter syntax) narrows to those effective that day. */
    public static final ListDefinition ASSIGNMENTS = ListDefinition.builder("hr.employment_assignments")
            .sortable("effectiveFrom", "createdAt")
            .defaultSort(SortOrder.asc("effectiveFrom"))
            .filter("employeeId", ValueType.UUID, EQ, IN)
            .filter("branchId", ValueType.UUID, EQ, IN)
            .filter("departmentId", ValueType.UUID, EQ, IN)
            .filter("positionId", ValueType.UUID, EQ, IN, IS_NULL)
            .filter("managerEmployeeId", ValueType.UUID, EQ, IN, IS_NULL)
            .enumFilter("employmentType", Set.of("FULL_TIME", "PART_TIME", "CONTRACT", "INTERN", "TEMPORARY"), EQ, IN)
            .filter("effectiveFrom", ValueType.DATE, EQ, GTE, LTE)
            .build();

    public static final ListDefinition DEPARTMENT_HEADS = ListDefinition.builder("hr.department_heads")
            .sortable("effectiveFrom", "createdAt")
            .defaultSort(SortOrder.asc("effectiveFrom"))
            .filter("departmentId", ValueType.UUID, EQ, IN)
            .filter("employeeId", ValueType.UUID, EQ, IN)
            .filter("effectiveFrom", ValueType.DATE, EQ, GTE, LTE)
            .build();

    private HrListings() {}
}
