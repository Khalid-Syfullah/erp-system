package com.erp.payroll.persistence;

import static com.erp.db.payroll.Tables.PAYROLL_PERIODS;

import com.erp.db.payroll.tables.records.PayrollPeriodsRecord;
import com.erp.payroll.application.PayrollListings;
import com.erp.payroll.application.PayrollViews;
import com.erp.platform.jooq.KeysetPaginator;
import com.erp.platform.jooq.ListBinding;
import com.erp.platform.web.paging.ListQuery;
import com.erp.platform.web.paging.PageResponse;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

/** Payroll periods of the pay schedules (DATABASE.md §5.10). */
@Repository
public class PeriodRepository {

    private static final ListBinding BINDING = ListBinding.builder(PayrollListings.PERIODS)
            .field("startDate", PAYROLL_PERIODS.START_DATE)
            .field("createdAt", PAYROLL_PERIODS.CREATED_AT)
            .field("payScheduleId", PAYROLL_PERIODS.PAY_SCHEDULE_ID)
            .field("status", PAYROLL_PERIODS.STATUS)
            .tiebreaker(PAYROLL_PERIODS.ID)
            .build();

    private final DSLContext dsl;
    private final KeysetPaginator paginator;

    public PeriodRepository(DSLContext dsl, KeysetPaginator paginator) {
        this.dsl = dsl;
        this.paginator = paginator;
    }

    /** Inserts the period unless the schedule has one starting that day; returns whether it was new. */
    public boolean insert(
            UUID companyId, UUID scheduleId, LocalDate start, LocalDate end, LocalDate payDate, UUID actor) {
        return dsl.insertInto(PAYROLL_PERIODS)
                        .set(PAYROLL_PERIODS.COMPANY_ID, companyId)
                        .set(PAYROLL_PERIODS.PAY_SCHEDULE_ID, scheduleId)
                        .set(PAYROLL_PERIODS.START_DATE, start)
                        .set(PAYROLL_PERIODS.END_DATE, end)
                        .set(PAYROLL_PERIODS.PAY_DATE, payDate)
                        .set(PAYROLL_PERIODS.CREATED_BY, actor)
                        .set(PAYROLL_PERIODS.UPDATED_BY, actor)
                        .onConflict(PAYROLL_PERIODS.PAY_SCHEDULE_ID, PAYROLL_PERIODS.START_DATE)
                        .doNothing()
                        .execute()
                == 1;
    }

    public Optional<PayrollViews.Period> find(UUID companyId, UUID id) {
        return dsl.selectFrom(PAYROLL_PERIODS)
                .where(PAYROLL_PERIODS.COMPANY_ID.eq(companyId))
                .and(PAYROLL_PERIODS.ID.eq(id))
                .fetchOptional(PeriodRepository::toView);
    }

    /** {@code FOR SHARE}: inputs and runs of the period serialize with its processing. */
    public Optional<PayrollViews.Period> lockForUse(UUID companyId, UUID id) {
        return dsl.selectFrom(PAYROLL_PERIODS)
                .where(PAYROLL_PERIODS.COMPANY_ID.eq(companyId))
                .and(PAYROLL_PERIODS.ID.eq(id))
                .forShare()
                .fetchOptional(PeriodRepository::toView);
    }

    public java.util.List<PayrollViews.Period> forScheduleYear(UUID companyId, UUID scheduleId, int year) {
        return dsl.selectFrom(PAYROLL_PERIODS)
                .where(PAYROLL_PERIODS.COMPANY_ID.eq(companyId))
                .and(PAYROLL_PERIODS.PAY_SCHEDULE_ID.eq(scheduleId))
                .and(PAYROLL_PERIODS.START_DATE.between(LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31)))
                .orderBy(PAYROLL_PERIODS.START_DATE)
                .fetch(PeriodRepository::toView);
    }

    public PageResponse<PayrollViews.Period> list(UUID companyId, ListQuery query) {
        return paginator.fetch(
                dsl,
                PAYROLL_PERIODS,
                PAYROLL_PERIODS.COMPANY_ID.eq(companyId),
                query,
                BINDING,
                PeriodRepository::toView);
    }

    public void setStatus(UUID companyId, UUID id, String status, UUID actor) {
        dsl.update(PAYROLL_PERIODS)
                .set(PAYROLL_PERIODS.STATUS, status)
                .set(PAYROLL_PERIODS.UPDATED_AT, OffsetDateTime.now())
                .set(PAYROLL_PERIODS.UPDATED_BY, actor)
                .set(PAYROLL_PERIODS.VERSION, PAYROLL_PERIODS.VERSION.plus(1))
                .where(PAYROLL_PERIODS.COMPANY_ID.eq(companyId))
                .and(PAYROLL_PERIODS.ID.eq(id))
                .execute();
    }

    static PayrollViews.Period toView(PayrollPeriodsRecord r) {
        return new PayrollViews.Period(
                r.getId(),
                r.getPayScheduleId(),
                r.getStartDate(),
                r.getEndDate(),
                r.getPayDate(),
                r.getStatus(),
                r.getVersion());
    }
}
