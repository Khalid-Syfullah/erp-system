package com.erp.hr.persistence;

import static com.erp.db.hr.Tables.PUBLIC_HOLIDAYS;

import com.erp.db.hr.tables.records.PublicHolidaysRecord;
import com.erp.hr.application.HrListings;
import com.erp.hr.application.HrViews;
import com.erp.platform.jooq.KeysetPaginator;
import com.erp.platform.jooq.ListBinding;
import com.erp.platform.web.paging.ListQuery;
import com.erp.platform.web.paging.PageResponse;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;

/** Public holidays, company-wide ({@code branch_id} null) or for one branch (DATABASE.md §5.9). */
@Repository
public class PublicHolidayRepository {

    private static final ListBinding BINDING = ListBinding.builder(HrListings.HOLIDAYS)
            .field("date", PUBLIC_HOLIDAYS.HOLIDAY_DATE)
            .field("createdAt", PUBLIC_HOLIDAYS.CREATED_AT)
            .field("branchId", PUBLIC_HOLIDAYS.BRANCH_ID)
            .tiebreaker(PUBLIC_HOLIDAYS.ID)
            .build();

    private final DSLContext dsl;
    private final KeysetPaginator paginator;

    public PublicHolidayRepository(DSLContext dsl, KeysetPaginator paginator) {
        this.dsl = dsl;
        this.paginator = paginator;
    }

    public UUID insert(UUID companyId, @Nullable UUID branchId, LocalDate date, String name, UUID actor) {
        return dsl.insertInto(PUBLIC_HOLIDAYS)
                .set(PUBLIC_HOLIDAYS.COMPANY_ID, companyId)
                .set(PUBLIC_HOLIDAYS.BRANCH_ID, branchId)
                .set(PUBLIC_HOLIDAYS.HOLIDAY_DATE, date)
                .set(PUBLIC_HOLIDAYS.NAME, name)
                .set(PUBLIC_HOLIDAYS.CREATED_BY, actor)
                .set(PUBLIC_HOLIDAYS.UPDATED_BY, actor)
                .returning(PUBLIC_HOLIDAYS.ID)
                .fetchSingle(PUBLIC_HOLIDAYS.ID);
    }

    public Optional<HrViews.Holiday> find(UUID companyId, UUID id) {
        return dsl.selectFrom(PUBLIC_HOLIDAYS)
                .where(PUBLIC_HOLIDAYS.COMPANY_ID.eq(companyId))
                .and(PUBLIC_HOLIDAYS.ID.eq(id))
                .fetchOptional(PublicHolidayRepository::toView);
    }

    public boolean update(UUID companyId, UUID id, int expectedVersion, UUID actor, LocalDate date, String name) {
        return dsl.update(PUBLIC_HOLIDAYS)
                        .set(PUBLIC_HOLIDAYS.HOLIDAY_DATE, date)
                        .set(PUBLIC_HOLIDAYS.NAME, name)
                        .set(PUBLIC_HOLIDAYS.UPDATED_AT, OffsetDateTime.now())
                        .set(PUBLIC_HOLIDAYS.UPDATED_BY, actor)
                        .set(PUBLIC_HOLIDAYS.VERSION, expectedVersion + 1)
                        .where(PUBLIC_HOLIDAYS.COMPANY_ID.eq(companyId))
                        .and(PUBLIC_HOLIDAYS.ID.eq(id))
                        .and(PUBLIC_HOLIDAYS.VERSION.eq(expectedVersion))
                        .execute()
                == 1;
    }

    public void delete(UUID companyId, UUID id) {
        dsl.deleteFrom(PUBLIC_HOLIDAYS)
                .where(PUBLIC_HOLIDAYS.COMPANY_ID.eq(companyId))
                .and(PUBLIC_HOLIDAYS.ID.eq(id))
                .execute();
    }

    public PageResponse<HrViews.Holiday> list(UUID companyId, ListQuery query) {
        return paginator.fetch(
                dsl,
                PUBLIC_HOLIDAYS,
                PUBLIC_HOLIDAYS.COMPANY_ID.eq(companyId),
                query,
                BINDING,
                PublicHolidayRepository::toView);
    }

    /** Holidays in the range that apply to the branch: company-wide ones and the branch's own. */
    public Set<LocalDate> dates(UUID companyId, @Nullable UUID branchId, LocalDate from, LocalDate to) {
        var condition = PUBLIC_HOLIDAYS.BRANCH_ID.isNull();
        if (branchId != null) {
            condition = condition.or(PUBLIC_HOLIDAYS.BRANCH_ID.eq(branchId));
        }
        return new HashSet<>(dsl.select(PUBLIC_HOLIDAYS.HOLIDAY_DATE)
                .from(PUBLIC_HOLIDAYS)
                .where(PUBLIC_HOLIDAYS.COMPANY_ID.eq(companyId))
                .and(PUBLIC_HOLIDAYS.HOLIDAY_DATE.between(from, to))
                .and(condition)
                .fetch(PUBLIC_HOLIDAYS.HOLIDAY_DATE));
    }

    static HrViews.Holiday toView(PublicHolidaysRecord r) {
        return new HrViews.Holiday(r.getId(), r.getBranchId(), r.getHolidayDate(), r.getName(), r.getVersion());
    }
}
