package com.erp.payroll.application;

import static com.erp.platform.web.paging.FilterOperator.EQ;
import static com.erp.platform.web.paging.FilterOperator.GTE;
import static com.erp.platform.web.paging.FilterOperator.IN;
import static com.erp.platform.web.paging.FilterOperator.LIKE;
import static com.erp.platform.web.paging.FilterOperator.LTE;

import com.erp.platform.web.paging.ListDefinition;
import com.erp.platform.web.paging.SortOrder;
import com.erp.platform.web.paging.ValueType;
import java.util.Set;

/** List contracts of the Payroll endpoints (API.md §17.10). */
public final class PayrollListings {

    public static final ListDefinition COMPONENTS = ListDefinition.builder("payroll.pay_components")
            .sortable("sequence", "code", "createdAt")
            .defaultSort(SortOrder.asc("sequence"))
            .filter("code", ValueType.STRING, EQ, IN, LIKE)
            .enumFilter("kind", Set.of("EARNING", "DEDUCTION", "EMPLOYER_CONTRIBUTION"), EQ, IN)
            .filter("isActive", ValueType.BOOLEAN, EQ)
            .searchable()
            .build();

    public static final ListDefinition STRUCTURES = ListDefinition.builder("payroll.salary_structures")
            .sortable("code", "createdAt")
            .defaultSort(SortOrder.asc("code"))
            .filter("code", ValueType.STRING, EQ, IN, LIKE)
            .filter("isActive", ValueType.BOOLEAN, EQ)
            .searchable()
            .build();

    public static final ListDefinition SCHEDULES = ListDefinition.builder("payroll.pay_schedules")
            .sortable("code", "createdAt")
            .defaultSort(SortOrder.asc("code"))
            .filter("code", ValueType.STRING, EQ, IN, LIKE)
            .filter("isActive", ValueType.BOOLEAN, EQ)
            .build();

    public static final ListDefinition PERIODS = ListDefinition.builder("payroll.payroll_periods")
            .sortable("startDate", "createdAt")
            .defaultSort(SortOrder.asc("startDate"))
            .filter("payScheduleId", ValueType.UUID, EQ, IN)
            .enumFilter("status", Set.of("OPEN", "PROCESSED", "CLOSED"), EQ, IN)
            .filter("startDate", ValueType.DATE, EQ, GTE, LTE)
            .build();

    public static final ListDefinition RUNS = ListDefinition.builder("payroll.payroll_runs")
            .sortable("accountingDate", "createdAt")
            .defaultSort(SortOrder.desc("createdAt"))
            .filter("payrollPeriodId", ValueType.UUID, EQ, IN)
            .enumFilter(
                    "status",
                    Set.of("DRAFT", "CALCULATING", "CALCULATED", "APPROVED", "POSTED", "PAID", "CANCELLED"),
                    EQ,
                    IN)
            .enumFilter("runType", Set.of("REGULAR", "OFF_CYCLE", "FINAL_SETTLEMENT"), EQ, IN)
            .filter("accountingDate", ValueType.DATE, EQ, GTE, LTE)
            .build();

    public static final ListDefinition PAYSLIPS = ListDefinition.builder("payroll.payslips")
            .sortable("employeeNumber", "createdAt")
            .defaultSort(SortOrder.asc("employeeNumber"))
            .filter("employeeId", ValueType.UUID, EQ, IN)
            .filter("departmentId", ValueType.UUID, EQ, IN)
            .filter("branchId", ValueType.UUID, EQ, IN)
            .build();

    private PayrollListings() {}
}
