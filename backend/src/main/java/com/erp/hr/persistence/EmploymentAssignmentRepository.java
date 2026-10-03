package com.erp.hr.persistence;

import static com.erp.db.hr.Tables.EMPLOYMENT_ASSIGNMENTS;

import com.erp.db.hr.tables.records.EmploymentAssignmentsRecord;
import com.erp.hr.application.AssignmentView;
import com.erp.hr.application.HrCommands;
import com.erp.hr.application.HrListings;
import com.erp.hr.domain.EffectivePeriod;
import com.erp.hr.domain.ReportingLines;
import com.erp.platform.jooq.KeysetPaginator;
import com.erp.platform.jooq.ListBinding;
import com.erp.platform.web.paging.ListQuery;
import com.erp.platform.web.paging.PageResponse;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;

/** Effective-dated employment assignments (DATABASE.md §5.9); never overlapping per employee (HR-1). */
@Repository
public class EmploymentAssignmentRepository {

    private static final ListBinding BINDING = ListBinding.builder(HrListings.ASSIGNMENTS)
            .field("effectiveFrom", EMPLOYMENT_ASSIGNMENTS.EFFECTIVE_FROM)
            .field("createdAt", EMPLOYMENT_ASSIGNMENTS.CREATED_AT)
            .field("employeeId", EMPLOYMENT_ASSIGNMENTS.EMPLOYEE_ID)
            .field("branchId", EMPLOYMENT_ASSIGNMENTS.BRANCH_ID)
            .field("departmentId", EMPLOYMENT_ASSIGNMENTS.DEPARTMENT_ID)
            .field("positionId", EMPLOYMENT_ASSIGNMENTS.POSITION_ID)
            .field("managerEmployeeId", EMPLOYMENT_ASSIGNMENTS.MANAGER_EMPLOYEE_ID)
            .field("employmentType", EMPLOYMENT_ASSIGNMENTS.EMPLOYMENT_TYPE)
            .tiebreaker(EMPLOYMENT_ASSIGNMENTS.ID)
            .build();

    private final DSLContext dsl;
    private final KeysetPaginator paginator;

    public EmploymentAssignmentRepository(DSLContext dsl, KeysetPaginator paginator) {
        this.dsl = dsl;
        this.paginator = paginator;
    }

    /** @param c assignment with a resolved {@code effectiveFrom} */
    public UUID insert(UUID companyId, UUID employeeId, HrCommands.Assignment c, UUID actor) {
        return dsl.insertInto(EMPLOYMENT_ASSIGNMENTS)
                .set(EMPLOYMENT_ASSIGNMENTS.COMPANY_ID, companyId)
                .set(EMPLOYMENT_ASSIGNMENTS.EMPLOYEE_ID, employeeId)
                .set(EMPLOYMENT_ASSIGNMENTS.BRANCH_ID, c.branchId())
                .set(EMPLOYMENT_ASSIGNMENTS.DEPARTMENT_ID, c.departmentId())
                .set(EMPLOYMENT_ASSIGNMENTS.POSITION_ID, c.positionId())
                .set(EMPLOYMENT_ASSIGNMENTS.MANAGER_EMPLOYEE_ID, c.managerEmployeeId())
                .set(EMPLOYMENT_ASSIGNMENTS.EMPLOYMENT_TYPE, c.employmentType())
                .set(EMPLOYMENT_ASSIGNMENTS.FTE, c.fte())
                .set(EMPLOYMENT_ASSIGNMENTS.EFFECTIVE_FROM, c.effectiveFrom())
                .set(EMPLOYMENT_ASSIGNMENTS.EFFECTIVE_TO, c.effectiveTo())
                .set(EMPLOYMENT_ASSIGNMENTS.CREATED_BY, actor)
                .set(EMPLOYMENT_ASSIGNMENTS.UPDATED_BY, actor)
                .returning(EMPLOYMENT_ASSIGNMENTS.ID)
                .fetchOne(EMPLOYMENT_ASSIGNMENTS.ID);
    }

    public Optional<AssignmentView> find(UUID companyId, UUID employeeId, UUID id) {
        return dsl.selectFrom(EMPLOYMENT_ASSIGNMENTS)
                .where(EMPLOYMENT_ASSIGNMENTS.COMPANY_ID.eq(companyId))
                .and(EMPLOYMENT_ASSIGNMENTS.EMPLOYEE_ID.eq(employeeId))
                .and(EMPLOYMENT_ASSIGNMENTS.ID.eq(id))
                .fetchOptional()
                .map(EmploymentAssignmentRepository::toView);
    }

    public List<AssignmentView> forEmployee(UUID companyId, UUID employeeId) {
        return dsl.selectFrom(EMPLOYMENT_ASSIGNMENTS)
                .where(EMPLOYMENT_ASSIGNMENTS.COMPANY_ID.eq(companyId))
                .and(EMPLOYMENT_ASSIGNMENTS.EMPLOYEE_ID.eq(employeeId))
                .orderBy(EMPLOYMENT_ASSIGNMENTS.EFFECTIVE_FROM)
                .fetch(EmploymentAssignmentRepository::toView);
    }

    public PageResponse<AssignmentView> list(
            UUID companyId, @Nullable Set<UUID> branchScope, @Nullable LocalDate asOf, ListQuery query) {
        Condition scope = EMPLOYMENT_ASSIGNMENTS.COMPANY_ID.eq(companyId);
        if (branchScope != null) {
            scope = scope.and(EMPLOYMENT_ASSIGNMENTS.BRANCH_ID.in(branchScope));
        }
        if (asOf != null) {
            scope = scope.and(effectiveOn(asOf));
        }
        return paginator.fetch(
                dsl, EMPLOYMENT_ASSIGNMENTS, scope, query, BINDING, EmploymentAssignmentRepository::toView);
    }

    /** Assignments of the employees effective on {@code date}, by employee. */
    public Map<UUID, AssignmentView> effectiveOn(UUID companyId, Collection<UUID> employeeIds, LocalDate date) {
        if (employeeIds.isEmpty()) {
            return Map.of();
        }
        return dsl
                .selectFrom(EMPLOYMENT_ASSIGNMENTS)
                .where(EMPLOYMENT_ASSIGNMENTS.COMPANY_ID.eq(companyId))
                .and(EMPLOYMENT_ASSIGNMENTS.EMPLOYEE_ID.in(employeeIds))
                .and(effectiveOn(date))
                .fetch(EmploymentAssignmentRepository::toView)
                .stream()
                .collect(Collectors.toMap(AssignmentView::employeeId, Function.identity()));
    }

    /** Other assignments of the employee overlapping the period. */
    public List<AssignmentView> overlapping(
            UUID companyId, UUID employeeId, EffectivePeriod period, @Nullable UUID excludeId) {
        return dsl.selectFrom(EMPLOYMENT_ASSIGNMENTS)
                .where(EMPLOYMENT_ASSIGNMENTS.COMPANY_ID.eq(companyId))
                .and(EMPLOYMENT_ASSIGNMENTS.EMPLOYEE_ID.eq(employeeId))
                .and(overlaps(period))
                .and(excludeId == null ? DSL.noCondition() : EMPLOYMENT_ASSIGNMENTS.ID.ne(excludeId))
                .fetch(EmploymentAssignmentRepository::toView);
    }

    /** Reporting lines of an employee overlapping the period (for {@link ReportingLines}). */
    public List<ReportingLines.ManagerSpan> managerSpans(UUID companyId, UUID employeeId, EffectivePeriod period) {
        return dsl.selectFrom(EMPLOYMENT_ASSIGNMENTS)
                .where(EMPLOYMENT_ASSIGNMENTS.COMPANY_ID.eq(companyId))
                .and(EMPLOYMENT_ASSIGNMENTS.EMPLOYEE_ID.eq(employeeId))
                .and(EMPLOYMENT_ASSIGNMENTS.MANAGER_EMPLOYEE_ID.isNotNull())
                .and(overlaps(period))
                .fetch(r -> new ReportingLines.ManagerSpan(
                        r.getManagerEmployeeId(), new EffectivePeriod(r.getEffectiveFrom(), r.getEffectiveTo())));
    }

    /** Serializes reporting-line changes per company, so concurrent changes cannot form a cycle together. */
    public void lockReportingLines(UUID companyId) {
        dsl.execute("SELECT pg_advisory_xact_lock(hashtext('hr.reporting_lines'), hashtext(?))", companyId.toString());
    }

    public boolean update(
            UUID companyId,
            UUID id,
            int expectedVersion,
            UUID actor,
            @Nullable UUID positionId,
            @Nullable UUID managerEmployeeId,
            String employmentType,
            java.math.BigDecimal fte,
            @Nullable LocalDate effectiveTo) {
        return dsl.update(EMPLOYMENT_ASSIGNMENTS)
                        .set(EMPLOYMENT_ASSIGNMENTS.POSITION_ID, positionId)
                        .set(EMPLOYMENT_ASSIGNMENTS.MANAGER_EMPLOYEE_ID, managerEmployeeId)
                        .set(EMPLOYMENT_ASSIGNMENTS.EMPLOYMENT_TYPE, employmentType)
                        .set(EMPLOYMENT_ASSIGNMENTS.FTE, fte)
                        .set(EMPLOYMENT_ASSIGNMENTS.EFFECTIVE_TO, effectiveTo)
                        .set(EMPLOYMENT_ASSIGNMENTS.UPDATED_AT, OffsetDateTime.now())
                        .set(EMPLOYMENT_ASSIGNMENTS.UPDATED_BY, actor)
                        .set(EMPLOYMENT_ASSIGNMENTS.VERSION, expectedVersion + 1)
                        .where(EMPLOYMENT_ASSIGNMENTS.COMPANY_ID.eq(companyId))
                        .and(EMPLOYMENT_ASSIGNMENTS.ID.eq(id))
                        .and(EMPLOYMENT_ASSIGNMENTS.VERSION.eq(expectedVersion))
                        .execute()
                == 1;
    }

    public boolean delete(UUID companyId, UUID id, int expectedVersion) {
        return dsl.deleteFrom(EMPLOYMENT_ASSIGNMENTS)
                        .where(EMPLOYMENT_ASSIGNMENTS.COMPANY_ID.eq(companyId))
                        .and(EMPLOYMENT_ASSIGNMENTS.ID.eq(id))
                        .and(EMPLOYMENT_ASSIGNMENTS.VERSION.eq(expectedVersion))
                        .execute()
                == 1;
    }

    // ------------------------------------------------------------------ usage (current or future)

    public int countCurrentOrFutureInBranch(UUID companyId, UUID branchId, LocalDate today) {
        return dsl.fetchCount(
                EMPLOYMENT_ASSIGNMENTS,
                EMPLOYMENT_ASSIGNMENTS
                        .COMPANY_ID
                        .eq(companyId)
                        .and(EMPLOYMENT_ASSIGNMENTS.BRANCH_ID.eq(branchId))
                        .and(currentOrFuture(today)));
    }

    public int countCurrentOrFutureInDepartment(UUID companyId, UUID departmentId, LocalDate today) {
        return dsl.fetchCount(
                EMPLOYMENT_ASSIGNMENTS,
                EMPLOYMENT_ASSIGNMENTS
                        .COMPANY_ID
                        .eq(companyId)
                        .and(EMPLOYMENT_ASSIGNMENTS.DEPARTMENT_ID.eq(departmentId))
                        .and(currentOrFuture(today)));
    }

    /** Current or future assignments using the position, optionally only those outside a department. */
    public int countCurrentOrFutureWithPosition(
            UUID companyId, UUID positionId, @Nullable UUID outsideDepartmentId, LocalDate today) {
        return dsl.fetchCount(
                EMPLOYMENT_ASSIGNMENTS,
                EMPLOYMENT_ASSIGNMENTS
                        .COMPANY_ID
                        .eq(companyId)
                        .and(EMPLOYMENT_ASSIGNMENTS.POSITION_ID.eq(positionId))
                        .and(
                                outsideDepartmentId == null
                                        ? DSL.noCondition()
                                        : EMPLOYMENT_ASSIGNMENTS.DEPARTMENT_ID.ne(outsideDepartmentId))
                        .and(currentOrFuture(today)));
    }

    /** Assignments that report to the manager after {@code date}. */
    public int countReportsAfter(UUID companyId, UUID managerEmployeeId, LocalDate date) {
        return dsl.fetchCount(
                EMPLOYMENT_ASSIGNMENTS,
                EMPLOYMENT_ASSIGNMENTS
                        .COMPANY_ID
                        .eq(companyId)
                        .and(EMPLOYMENT_ASSIGNMENTS.MANAGER_EMPLOYEE_ID.eq(managerEmployeeId))
                        .and(currentOrFuture(date.plusDays(1))));
    }

    private static Condition effectiveOn(LocalDate date) {
        return EMPLOYMENT_ASSIGNMENTS
                .EFFECTIVE_FROM
                .le(date)
                .and(EMPLOYMENT_ASSIGNMENTS.EFFECTIVE_TO.isNull().or(EMPLOYMENT_ASSIGNMENTS.EFFECTIVE_TO.ge(date)));
    }

    private static Condition currentOrFuture(LocalDate today) {
        return EMPLOYMENT_ASSIGNMENTS.EFFECTIVE_TO.isNull().or(EMPLOYMENT_ASSIGNMENTS.EFFECTIVE_TO.ge(today));
    }

    private static Condition overlaps(EffectivePeriod period) {
        Condition startsBeforeEnd =
                period.to() == null ? DSL.noCondition() : EMPLOYMENT_ASSIGNMENTS.EFFECTIVE_FROM.le(period.to());
        return startsBeforeEnd.and(currentOrFuture(period.from()));
    }

    static AssignmentView toView(EmploymentAssignmentsRecord r) {
        return new AssignmentView(
                r.getId(),
                r.getCompanyId(),
                r.getEmployeeId(),
                r.getBranchId(),
                r.getDepartmentId(),
                r.getPositionId(),
                r.getManagerEmployeeId(),
                r.getEmploymentType(),
                r.getFte(),
                r.getEffectiveFrom(),
                r.getEffectiveTo(),
                r.getCreatedAt(),
                r.getUpdatedAt(),
                r.getVersion());
    }
}
