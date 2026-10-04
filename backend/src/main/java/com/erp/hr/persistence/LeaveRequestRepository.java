package com.erp.hr.persistence;

import static com.erp.db.hr.Tables.EMPLOYEES;
import static com.erp.db.hr.Tables.LEAVE_REQUESTS;

import com.erp.db.hr.tables.records.LeaveRequestsRecord;
import com.erp.hr.application.HrListings;
import com.erp.hr.application.HrViews;
import com.erp.hr.domain.LeaveRequestStatus;
import com.erp.platform.jooq.KeysetPaginator;
import com.erp.platform.jooq.ListBinding;
import com.erp.platform.web.paging.ListQuery;
import com.erp.platform.web.paging.PageResponse;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;

/** Leave requests (DATABASE.md §5.9). */
@Repository
public class LeaveRequestRepository {

    private static final ListBinding BINDING = ListBinding.builder(HrListings.LEAVE_REQUESTS)
            .field("startDate", LEAVE_REQUESTS.START_DATE)
            .field("endDate", LEAVE_REQUESTS.END_DATE)
            .field("createdAt", LEAVE_REQUESTS.CREATED_AT)
            .field("employeeId", LEAVE_REQUESTS.EMPLOYEE_ID)
            .field("leaveTypeId", LEAVE_REQUESTS.LEAVE_TYPE_ID)
            .field("status", LEAVE_REQUESTS.STATUS)
            .tiebreaker(LEAVE_REQUESTS.ID)
            .build();

    private final DSLContext dsl;
    private final KeysetPaginator paginator;

    public LeaveRequestRepository(DSLContext dsl, KeysetPaginator paginator) {
        this.dsl = dsl;
        this.paginator = paginator;
    }

    public UUID insert(
            UUID companyId,
            UUID employeeId,
            UUID leaveTypeId,
            LocalDate start,
            LocalDate end,
            BigDecimal days,
            @Nullable String reason,
            UUID actor) {
        return dsl.insertInto(LEAVE_REQUESTS)
                .set(LEAVE_REQUESTS.COMPANY_ID, companyId)
                .set(LEAVE_REQUESTS.EMPLOYEE_ID, employeeId)
                .set(LEAVE_REQUESTS.LEAVE_TYPE_ID, leaveTypeId)
                .set(LEAVE_REQUESTS.START_DATE, start)
                .set(LEAVE_REQUESTS.END_DATE, end)
                .set(LEAVE_REQUESTS.DAYS, days)
                .set(LEAVE_REQUESTS.REASON, reason)
                .set(LEAVE_REQUESTS.CREATED_BY, actor)
                .set(LEAVE_REQUESTS.UPDATED_BY, actor)
                .returning(LEAVE_REQUESTS.ID)
                .fetchSingle(LEAVE_REQUESTS.ID);
    }

    public boolean updateDraft(
            UUID companyId,
            UUID id,
            int expectedVersion,
            UUID actor,
            UUID leaveTypeId,
            LocalDate start,
            LocalDate end,
            BigDecimal days,
            @Nullable String reason) {
        return dsl.update(LEAVE_REQUESTS)
                        .set(LEAVE_REQUESTS.LEAVE_TYPE_ID, leaveTypeId)
                        .set(LEAVE_REQUESTS.START_DATE, start)
                        .set(LEAVE_REQUESTS.END_DATE, end)
                        .set(LEAVE_REQUESTS.DAYS, days)
                        .set(LEAVE_REQUESTS.REASON, reason)
                        .set(LEAVE_REQUESTS.UPDATED_AT, OffsetDateTime.now())
                        .set(LEAVE_REQUESTS.UPDATED_BY, actor)
                        .set(LEAVE_REQUESTS.VERSION, expectedVersion + 1)
                        .where(LEAVE_REQUESTS.COMPANY_ID.eq(companyId))
                        .and(LEAVE_REQUESTS.ID.eq(id))
                        .and(LEAVE_REQUESTS.VERSION.eq(expectedVersion))
                        .execute()
                == 1;
    }

    /** Moves the request to {@code status}; a decision records who decided, when and why. */
    public boolean transition(
            UUID companyId,
            UUID id,
            int expectedVersion,
            UUID actor,
            LeaveRequestStatus status,
            boolean decision,
            @Nullable String note) {
        OffsetDateTime now = OffsetDateTime.now();
        var update = dsl.update(LEAVE_REQUESTS)
                .set(LEAVE_REQUESTS.STATUS, status.name())
                .set(LEAVE_REQUESTS.UPDATED_AT, now)
                .set(LEAVE_REQUESTS.UPDATED_BY, actor)
                .set(LEAVE_REQUESTS.VERSION, expectedVersion + 1);
        if (status == LeaveRequestStatus.SUBMITTED) {
            update = update.set(LEAVE_REQUESTS.SUBMITTED_AT, now);
        }
        if (decision) {
            update = update.set(LEAVE_REQUESTS.DECIDED_BY, actor)
                    .set(LEAVE_REQUESTS.DECIDED_AT, now)
                    .set(LEAVE_REQUESTS.DECISION_NOTE, note);
        }
        return update.where(LEAVE_REQUESTS.COMPANY_ID.eq(companyId))
                        .and(LEAVE_REQUESTS.ID.eq(id))
                        .and(LEAVE_REQUESTS.VERSION.eq(expectedVersion))
                        .execute()
                == 1;
    }

    public Optional<HrViews.LeaveRequest> find(UUID companyId, UUID id) {
        return dsl.selectFrom(LEAVE_REQUESTS)
                .where(LEAVE_REQUESTS.COMPANY_ID.eq(companyId))
                .and(LEAVE_REQUESTS.ID.eq(id))
                .fetchOptional(LeaveRequestRepository::toView);
    }

    public Optional<HrViews.LeaveRequest> lock(UUID companyId, UUID id) {
        return dsl.selectFrom(LEAVE_REQUESTS)
                .where(LEAVE_REQUESTS.COMPANY_ID.eq(companyId))
                .and(LEAVE_REQUESTS.ID.eq(id))
                .forNoKeyUpdate()
                .fetchOptional(LeaveRequestRepository::toView);
    }

    public void delete(UUID companyId, UUID id) {
        dsl.deleteFrom(LEAVE_REQUESTS)
                .where(LEAVE_REQUESTS.COMPANY_ID.eq(companyId))
                .and(LEAVE_REQUESTS.ID.eq(id))
                .execute();
    }

    /**
     * Requests visible to the caller: within the branch scope, and limited to {@code employeeIds} when
     * given (self-service, a manager's team).
     */
    public PageResponse<HrViews.LeaveRequest> list(
            UUID companyId,
            @Nullable Set<UUID> branchScope,
            LocalDate today,
            @Nullable Collection<UUID> employeeIds,
            ListQuery query) {
        Condition condition = LEAVE_REQUESTS.COMPANY_ID.eq(companyId);
        if (employeeIds != null) {
            condition = condition.and(LEAVE_REQUESTS.EMPLOYEE_ID.in(employeeIds));
        }
        if (branchScope != null) {
            condition = condition.and(DSL.exists(DSL.selectOne()
                    .from(EMPLOYEES)
                    .where(EMPLOYEES.COMPANY_ID.eq(LEAVE_REQUESTS.COMPANY_ID))
                    .and(EMPLOYEES.ID.eq(LEAVE_REQUESTS.EMPLOYEE_ID))
                    .and(EmployeeRepository.visible(branchScope, today))));
        }
        return paginator.fetch(dsl, LEAVE_REQUESTS, condition, query, BINDING, LeaveRequestRepository::toView);
    }

    /** Days of the employee's submitted requests of the type in the year, other than {@code except}. */
    public BigDecimal pendingDays(UUID companyId, UUID employeeId, UUID leaveTypeId, int year, @Nullable UUID except) {
        Condition condition = LEAVE_REQUESTS
                .COMPANY_ID
                .eq(companyId)
                .and(LEAVE_REQUESTS.EMPLOYEE_ID.eq(employeeId))
                .and(LEAVE_REQUESTS.LEAVE_TYPE_ID.eq(leaveTypeId))
                .and(LEAVE_REQUESTS.STATUS.eq(LeaveRequestStatus.SUBMITTED.name()))
                .and(DSL.extract(LEAVE_REQUESTS.START_DATE, org.jooq.DatePart.YEAR)
                        .eq(year));
        if (except != null) {
            condition = condition.and(LEAVE_REQUESTS.ID.ne(except));
        }
        BigDecimal days = dsl.select(DSL.sum(LEAVE_REQUESTS.DAYS))
                .from(LEAVE_REQUESTS)
                .where(condition)
                .fetchOne(0, BigDecimal.class);
        return days == null ? BigDecimal.ZERO : days;
    }

    /** Submitted days per leave type of the employee in the year. */
    public java.util.Map<UUID, BigDecimal> pendingByType(UUID companyId, UUID employeeId, int year) {
        return dsl.select(LEAVE_REQUESTS.LEAVE_TYPE_ID, DSL.sum(LEAVE_REQUESTS.DAYS))
                .from(LEAVE_REQUESTS)
                .where(LEAVE_REQUESTS.COMPANY_ID.eq(companyId))
                .and(LEAVE_REQUESTS.EMPLOYEE_ID.eq(employeeId))
                .and(LEAVE_REQUESTS.STATUS.eq(LeaveRequestStatus.SUBMITTED.name()))
                .and(DSL.extract(LEAVE_REQUESTS.START_DATE, org.jooq.DatePart.YEAR)
                        .eq(year))
                .groupBy(LEAVE_REQUESTS.LEAVE_TYPE_ID)
                .fetchMap(r -> r.value1(), r -> r.value2());
    }

    /** Whether approved leave of the employee covers the date. */
    public boolean approvedOn(UUID companyId, UUID employeeId, LocalDate date) {
        return dsl.fetchExists(dsl.selectOne()
                .from(LEAVE_REQUESTS)
                .where(LEAVE_REQUESTS.COMPANY_ID.eq(companyId))
                .and(LEAVE_REQUESTS.EMPLOYEE_ID.eq(employeeId))
                .and(LEAVE_REQUESTS.STATUS.eq(LeaveRequestStatus.APPROVED.name()))
                .and(LEAVE_REQUESTS.START_DATE.le(date))
                .and(LEAVE_REQUESTS.END_DATE.ge(date)));
    }

    /** Employees with approved leave covering the date. */
    public Set<UUID> onLeave(UUID companyId, LocalDate date) {
        return Set.copyOf(dsl.selectDistinct(LEAVE_REQUESTS.EMPLOYEE_ID)
                .from(LEAVE_REQUESTS)
                .where(LEAVE_REQUESTS.COMPANY_ID.eq(companyId))
                .and(LEAVE_REQUESTS.STATUS.eq(LeaveRequestStatus.APPROVED.name()))
                .and(LEAVE_REQUESTS.START_DATE.le(date))
                .and(LEAVE_REQUESTS.END_DATE.ge(date))
                .fetch(LEAVE_REQUESTS.EMPLOYEE_ID));
    }

    /** Open requests (draft, submitted, approved) of the employee ending after {@code date}. */
    public List<HrViews.LeaveRequest> openAfter(UUID companyId, UUID employeeId, LocalDate date) {
        return dsl.selectFrom(LEAVE_REQUESTS)
                .where(LEAVE_REQUESTS.COMPANY_ID.eq(companyId))
                .and(LEAVE_REQUESTS.EMPLOYEE_ID.eq(employeeId))
                .and(LEAVE_REQUESTS.STATUS.in("DRAFT", "SUBMITTED", "APPROVED"))
                .and(LEAVE_REQUESTS.END_DATE.gt(date))
                .orderBy(LEAVE_REQUESTS.START_DATE)
                .forNoKeyUpdate()
                .fetch(LeaveRequestRepository::toView);
    }

    static HrViews.LeaveRequest toView(LeaveRequestsRecord r) {
        return new HrViews.LeaveRequest(
                r.getId(),
                r.getEmployeeId(),
                r.getLeaveTypeId(),
                r.getStartDate(),
                r.getEndDate(),
                r.getDays(),
                r.getReason(),
                LeaveRequestStatus.valueOf(r.getStatus()),
                r.getSubmittedAt(),
                r.getDecidedBy(),
                r.getDecidedAt(),
                r.getDecisionNote(),
                r.getCreatedBy(),
                r.getCreatedAt(),
                r.getUpdatedAt(),
                r.getVersion());
    }
}
