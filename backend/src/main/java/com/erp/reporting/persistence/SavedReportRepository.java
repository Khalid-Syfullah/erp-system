package com.erp.reporting.persistence;

import static com.erp.db.reporting.tables.SavedReports.SAVED_REPORTS;

import com.erp.db.reporting.tables.records.SavedReportsRecord;
import com.erp.platform.jooq.KeysetPaginator;
import com.erp.platform.jooq.ListBinding;
import com.erp.platform.web.paging.ListQuery;
import com.erp.platform.web.paging.PageResponse;
import com.erp.reporting.application.ReportingListings;
import com.erp.reporting.application.ReportingViews;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

/** Saved report parameters (DATABASE.md §5.11). */
@Repository
public class SavedReportRepository {

    private static final ListBinding BINDING = ListBinding.builder(ReportingListings.SAVED_REPORTS)
            .field("name", SAVED_REPORTS.NAME)
            .field("createdAt", SAVED_REPORTS.CREATED_AT)
            .field("reportCode", SAVED_REPORTS.REPORT_CODE)
            .field("isShared", SAVED_REPORTS.IS_SHARED)
            .tiebreaker(SAVED_REPORTS.ID)
            .search(List.of(SAVED_REPORTS.NAME))
            .build();

    private final DSLContext dsl;
    private final KeysetPaginator paginator;
    private final ReportingJson json;

    SavedReportRepository(DSLContext dsl, KeysetPaginator paginator, ReportingJson json) {
        this.dsl = dsl;
        this.paginator = paginator;
        this.json = json;
    }

    public UUID insert(
            UUID companyId,
            UUID userId,
            String reportCode,
            String name,
            Map<String, String> parameters,
            boolean shared) {
        return dsl.insertInto(SAVED_REPORTS)
                .set(SAVED_REPORTS.COMPANY_ID, companyId)
                .set(SAVED_REPORTS.USER_ID, userId)
                .set(SAVED_REPORTS.REPORT_CODE, reportCode)
                .set(SAVED_REPORTS.NAME, name)
                .set(SAVED_REPORTS.PARAMETERS, json.write(parameters))
                .set(SAVED_REPORTS.IS_SHARED, shared)
                .set(SAVED_REPORTS.CREATED_BY, userId)
                .set(SAVED_REPORTS.UPDATED_BY, userId)
                .returning(SAVED_REPORTS.ID)
                .fetchSingle(SAVED_REPORTS.ID);
    }

    public Optional<ReportingViews.SavedReport> find(UUID companyId, UUID id) {
        return dsl.selectFrom(SAVED_REPORTS)
                .where(SAVED_REPORTS.COMPANY_ID.eq(companyId))
                .and(SAVED_REPORTS.ID.eq(id))
                .fetchOptional(this::toView);
    }

    public Optional<ReportingViews.SavedReport> lockForChange(UUID companyId, UUID id) {
        return dsl.selectFrom(SAVED_REPORTS)
                .where(SAVED_REPORTS.COMPANY_ID.eq(companyId))
                .and(SAVED_REPORTS.ID.eq(id))
                .forNoKeyUpdate()
                .fetchOptional(this::toView);
    }

    public boolean update(
            UUID companyId,
            UUID id,
            int expectedVersion,
            UUID actor,
            String name,
            Map<String, String> parameters,
            boolean shared) {
        return dsl.update(SAVED_REPORTS)
                        .set(SAVED_REPORTS.NAME, name)
                        .set(SAVED_REPORTS.PARAMETERS, json.write(parameters))
                        .set(SAVED_REPORTS.IS_SHARED, shared)
                        .set(SAVED_REPORTS.UPDATED_AT, OffsetDateTime.now())
                        .set(SAVED_REPORTS.UPDATED_BY, actor)
                        .set(SAVED_REPORTS.VERSION, expectedVersion + 1)
                        .where(SAVED_REPORTS.COMPANY_ID.eq(companyId))
                        .and(SAVED_REPORTS.ID.eq(id))
                        .and(SAVED_REPORTS.VERSION.eq(expectedVersion))
                        .execute()
                == 1;
    }

    public boolean delete(UUID companyId, UUID id, int expectedVersion) {
        return dsl.deleteFrom(SAVED_REPORTS)
                        .where(SAVED_REPORTS.COMPANY_ID.eq(companyId))
                        .and(SAVED_REPORTS.ID.eq(id))
                        .and(SAVED_REPORTS.VERSION.eq(expectedVersion))
                        .execute()
                == 1;
    }

    /** The caller's own saved reports and those shared in the company, of the reports the caller may run. */
    public PageResponse<ReportingViews.SavedReport> list(
            UUID companyId, UUID userId, Collection<String> visibleReports, ListQuery query) {
        return paginator.fetch(
                dsl,
                SAVED_REPORTS,
                SAVED_REPORTS
                        .COMPANY_ID
                        .eq(companyId)
                        .and(SAVED_REPORTS.USER_ID.eq(userId).or(SAVED_REPORTS.IS_SHARED.isTrue()))
                        .and(SAVED_REPORTS.REPORT_CODE.in(visibleReports)),
                query,
                BINDING,
                this::toView);
    }

    private ReportingViews.SavedReport toView(SavedReportsRecord r) {
        return new ReportingViews.SavedReport(
                r.getId(),
                r.getUserId(),
                r.getReportCode(),
                r.getName(),
                json.read(r.getParameters()),
                r.getIsShared(),
                r.getCreatedAt(),
                r.getUpdatedAt(),
                r.getVersion());
    }
}
