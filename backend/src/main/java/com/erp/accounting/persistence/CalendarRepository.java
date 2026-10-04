package com.erp.accounting.persistence;

import static com.erp.db.accounting.Tables.FISCAL_YEARS;
import static com.erp.db.accounting.Tables.PERIODS;

import com.erp.accounting.application.AccountingListings;
import com.erp.accounting.application.AccountingViews;
import com.erp.db.accounting.tables.records.FiscalYearsRecord;
import com.erp.db.accounting.tables.records.PeriodsRecord;
import com.erp.platform.jooq.KeysetPaginator;
import com.erp.platform.jooq.ListBinding;
import com.erp.platform.web.paging.ListQuery;
import com.erp.platform.web.paging.PageResponse;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;

/** Fiscal years and their periods. */
@Repository
public class CalendarRepository {

    private static final ListBinding YEARS = ListBinding.builder(AccountingListings.FISCAL_YEARS)
            .field("startDate", FISCAL_YEARS.START_DATE)
            .field("status", FISCAL_YEARS.STATUS)
            .tiebreaker(FISCAL_YEARS.ID)
            .build();

    private static final ListBinding PERIOD_BINDING = ListBinding.builder(AccountingListings.PERIODS)
            .field("startDate", PERIODS.START_DATE)
            .field("status", PERIODS.STATUS)
            .field("fiscalYearId", PERIODS.FISCAL_YEAR_ID)
            .tiebreaker(PERIODS.ID)
            .build();

    private final DSLContext dsl;
    private final KeysetPaginator paginator;

    public CalendarRepository(DSLContext dsl, KeysetPaginator paginator) {
        this.dsl = dsl;
        this.paginator = paginator;
    }

    public UUID insertYear(UUID companyId, String code, LocalDate start, LocalDate end, @Nullable UUID actor) {
        return dsl.insertInto(FISCAL_YEARS)
                .set(FISCAL_YEARS.COMPANY_ID, companyId)
                .set(FISCAL_YEARS.CODE, code)
                .set(FISCAL_YEARS.START_DATE, start)
                .set(FISCAL_YEARS.END_DATE, end)
                .set(FISCAL_YEARS.CREATED_BY, actor)
                .set(FISCAL_YEARS.UPDATED_BY, actor)
                .returning(FISCAL_YEARS.ID)
                .fetchOne(FISCAL_YEARS.ID);
    }

    public void insertPeriod(
            UUID companyId, UUID yearId, int number, LocalDate start, LocalDate end, @Nullable UUID actor) {
        dsl.insertInto(PERIODS)
                .set(PERIODS.COMPANY_ID, companyId)
                .set(PERIODS.FISCAL_YEAR_ID, yearId)
                .set(PERIODS.PERIOD_NO, (short) number)
                .set(PERIODS.START_DATE, start)
                .set(PERIODS.END_DATE, end)
                .set(PERIODS.CREATED_BY, actor)
                .set(PERIODS.UPDATED_BY, actor)
                .execute();
    }

    public Optional<AccountingViews.FiscalYear> findYear(UUID companyId, UUID id) {
        return dsl.selectFrom(FISCAL_YEARS)
                .where(FISCAL_YEARS.COMPANY_ID.eq(companyId))
                .and(FISCAL_YEARS.ID.eq(id))
                .fetchOptional(CalendarRepository::toYear);
    }

    public Optional<AccountingViews.FiscalYear> lockYear(UUID companyId, UUID id) {
        return dsl.selectFrom(FISCAL_YEARS)
                .where(FISCAL_YEARS.COMPANY_ID.eq(companyId))
                .and(FISCAL_YEARS.ID.eq(id))
                .forUpdate()
                .fetchOptional(CalendarRepository::toYear);
    }

    public Optional<AccountingViews.FiscalYear> yearStarting(UUID companyId, LocalDate start) {
        return dsl.selectFrom(FISCAL_YEARS)
                .where(FISCAL_YEARS.COMPANY_ID.eq(companyId))
                .and(FISCAL_YEARS.START_DATE.eq(start))
                .fetchOptional(CalendarRepository::toYear);
    }

    public boolean yearOverlaps(UUID companyId, LocalDate start, LocalDate end) {
        return dsl.fetchExists(dsl.selectOne()
                .from(FISCAL_YEARS)
                .where(FISCAL_YEARS.COMPANY_ID.eq(companyId))
                .and(FISCAL_YEARS.START_DATE.le(end))
                .and(FISCAL_YEARS.END_DATE.ge(start)));
    }

    public PageResponse<AccountingViews.FiscalYear> years(UUID companyId, ListQuery query) {
        return paginator.fetch(
                dsl, FISCAL_YEARS, FISCAL_YEARS.COMPANY_ID.eq(companyId), query, YEARS, CalendarRepository::toYear);
    }

    /** Fiscal years ending on or after {@code date} that are still open, oldest first. */
    public List<AccountingViews.FiscalYear> openYearsBefore(UUID companyId, LocalDate start) {
        return dsl.selectFrom(FISCAL_YEARS)
                .where(FISCAL_YEARS.COMPANY_ID.eq(companyId))
                .and(FISCAL_YEARS.END_DATE.lt(start))
                .and(FISCAL_YEARS.STATUS.eq("OPEN"))
                .orderBy(FISCAL_YEARS.START_DATE)
                .fetch(CalendarRepository::toYear);
    }

    public boolean closeYear(UUID companyId, UUID id, int version, @Nullable UUID closingEntryId, UUID actor) {
        return dsl.update(FISCAL_YEARS)
                        .set(FISCAL_YEARS.STATUS, "CLOSED")
                        .set(FISCAL_YEARS.CLOSING_ENTRY_ID, closingEntryId)
                        .set(FISCAL_YEARS.CLOSED_AT, OffsetDateTime.now())
                        .set(FISCAL_YEARS.CLOSED_BY, actor)
                        .set(FISCAL_YEARS.UPDATED_AT, OffsetDateTime.now())
                        .set(FISCAL_YEARS.UPDATED_BY, actor)
                        .set(FISCAL_YEARS.VERSION, version + 1)
                        .where(FISCAL_YEARS.COMPANY_ID.eq(companyId))
                        .and(FISCAL_YEARS.ID.eq(id))
                        .and(FISCAL_YEARS.VERSION.eq(version))
                        .execute()
                == 1;
    }

    public List<AccountingViews.Period> periods(UUID companyId, UUID yearId) {
        return dsl.selectFrom(PERIODS)
                .where(PERIODS.COMPANY_ID.eq(companyId))
                .and(PERIODS.FISCAL_YEAR_ID.eq(yearId))
                .orderBy(PERIODS.PERIOD_NO)
                .fetch(CalendarRepository::toPeriod);
    }

    public PageResponse<AccountingViews.Period> listPeriods(UUID companyId, ListQuery query) {
        return paginator.fetch(
                dsl, PERIODS, PERIODS.COMPANY_ID.eq(companyId), query, PERIOD_BINDING, CalendarRepository::toPeriod);
    }

    public Optional<AccountingViews.Period> findPeriod(UUID companyId, UUID id) {
        return dsl.selectFrom(PERIODS)
                .where(PERIODS.COMPANY_ID.eq(companyId))
                .and(PERIODS.ID.eq(id))
                .fetchOptional(CalendarRepository::toPeriod);
    }

    /** The period {@code FOR UPDATE}: close and reopen exclude postings into it (DATABASE.md §9). */
    public Optional<AccountingViews.Period> lockPeriod(UUID companyId, UUID id) {
        return dsl.selectFrom(PERIODS)
                .where(PERIODS.COMPANY_ID.eq(companyId))
                .and(PERIODS.ID.eq(id))
                .forUpdate()
                .fetchOptional(CalendarRepository::toPeriod);
    }

    /** The period containing the date {@code FOR SHARE}: a posting waits for a concurrent close. */
    public Optional<AccountingViews.Period> periodForPosting(UUID companyId, LocalDate date) {
        return dsl.selectFrom(PERIODS)
                .where(PERIODS.COMPANY_ID.eq(companyId))
                .and(PERIODS.START_DATE.le(date))
                .and(PERIODS.END_DATE.ge(date))
                .forShare()
                .fetchOptional(CalendarRepository::toPeriod);
    }

    public Optional<AccountingViews.Period> periodContaining(UUID companyId, LocalDate date) {
        return dsl.selectFrom(PERIODS)
                .where(PERIODS.COMPANY_ID.eq(companyId))
                .and(PERIODS.START_DATE.le(date))
                .and(PERIODS.END_DATE.ge(date))
                .fetchOptional(CalendarRepository::toPeriod);
    }

    /** Periods before the date that are not closed. */
    public List<AccountingViews.Period> unclosedBefore(UUID companyId, LocalDate date) {
        return dsl.selectFrom(PERIODS)
                .where(PERIODS.COMPANY_ID.eq(companyId))
                .and(PERIODS.END_DATE.lt(date))
                .and(PERIODS.STATUS.ne("CLOSED"))
                .orderBy(PERIODS.START_DATE)
                .fetch(CalendarRepository::toPeriod);
    }

    /** Closed periods after the date. */
    public List<AccountingViews.Period> closedAfter(UUID companyId, LocalDate date) {
        return dsl.selectFrom(PERIODS)
                .where(PERIODS.COMPANY_ID.eq(companyId))
                .and(PERIODS.START_DATE.gt(date))
                .and(PERIODS.STATUS.eq("CLOSED"))
                .orderBy(PERIODS.START_DATE)
                .fetch(CalendarRepository::toPeriod);
    }

    public boolean transition(UUID companyId, UUID id, int version, String status, UUID actor) {
        boolean closed = "CLOSED".equals(status);
        return dsl.update(PERIODS)
                        .set(PERIODS.STATUS, status)
                        .set(PERIODS.CLOSED_AT, closed ? OffsetDateTime.now() : null)
                        .set(PERIODS.CLOSED_BY, closed ? actor : null)
                        .set(PERIODS.UPDATED_AT, OffsetDateTime.now())
                        .set(PERIODS.UPDATED_BY, actor)
                        .set(PERIODS.VERSION, version + 1)
                        .where(PERIODS.COMPANY_ID.eq(companyId))
                        .and(PERIODS.ID.eq(id))
                        .and(PERIODS.VERSION.eq(version))
                        .execute()
                == 1;
    }

    static AccountingViews.FiscalYear toYear(FiscalYearsRecord r) {
        return new AccountingViews.FiscalYear(
                r.getId(),
                r.getCode(),
                r.getStartDate(),
                r.getEndDate(),
                r.getStatus(),
                r.getClosingEntryId(),
                r.getClosedAt(),
                r.getClosedBy(),
                r.getVersion());
    }

    static AccountingViews.Period toPeriod(PeriodsRecord r) {
        return new AccountingViews.Period(
                r.getId(),
                r.getFiscalYearId(),
                r.getPeriodNo(),
                r.getStartDate(),
                r.getEndDate(),
                r.getStatus(),
                r.getClosedAt(),
                r.getClosedBy(),
                r.getVersion());
    }
}
