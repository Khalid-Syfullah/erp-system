package com.erp.reporting.persistence;

import static com.erp.db.reporting.tables.ExportJobs.EXPORT_JOBS;

import com.erp.db.reporting.tables.records.ExportJobsRecord;
import com.erp.platform.jooq.KeysetPaginator;
import com.erp.platform.jooq.ListBinding;
import com.erp.platform.web.paging.ListQuery;
import com.erp.platform.web.paging.PageResponse;
import com.erp.reporting.application.ReportingListings;
import com.erp.reporting.application.ReportingViews;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;

/** Export jobs (DATABASE.md §5.11): queued by requests, claimed and completed by the worker. */
@Repository
public class ExportJobRepository {

    private static final ListBinding BINDING = ListBinding.builder(ReportingListings.EXPORT_JOBS)
            .field("requestedAt", EXPORT_JOBS.REQUESTED_AT)
            .field("status", EXPORT_JOBS.STATUS)
            .field("reportCode", EXPORT_JOBS.REPORT_CODE)
            .tiebreaker(EXPORT_JOBS.ID)
            .build();

    private final DSLContext dsl;
    private final KeysetPaginator paginator;
    private final ReportingJson json;

    ExportJobRepository(DSLContext dsl, KeysetPaginator paginator, ReportingJson json) {
        this.dsl = dsl;
        this.paginator = paginator;
        this.json = json;
    }

    public UUID insert(
            UUID companyId,
            UUID userId,
            String reportCode,
            Map<String, String> parameters,
            String format,
            @Nullable Set<UUID> branchScope) {
        return dsl.insertInto(EXPORT_JOBS)
                .set(EXPORT_JOBS.COMPANY_ID, companyId)
                .set(EXPORT_JOBS.USER_ID, userId)
                .set(EXPORT_JOBS.REPORT_CODE, reportCode)
                .set(EXPORT_JOBS.PARAMETERS, json.write(parameters))
                .set(EXPORT_JOBS.FORMAT, format)
                .set(EXPORT_JOBS.BRANCH_SCOPE, branchScope == null ? null : branchScope.toArray(UUID[]::new))
                .returning(EXPORT_JOBS.ID)
                .fetchSingle(EXPORT_JOBS.ID);
    }

    public Optional<ReportingViews.ExportJob> find(UUID companyId, UUID id) {
        return dsl.selectFrom(EXPORT_JOBS)
                .where(EXPORT_JOBS.COMPANY_ID.eq(companyId))
                .and(EXPORT_JOBS.ID.eq(id))
                .fetchOptional(this::toView);
    }

    /** Requests of the user since {@code since} (the hourly export budget, SECURITY.md §9). */
    public int requestedSince(UUID companyId, UUID userId, OffsetDateTime since) {
        return dsl.fetchCount(
                EXPORT_JOBS,
                EXPORT_JOBS
                        .COMPANY_ID
                        .eq(companyId)
                        .and(EXPORT_JOBS.USER_ID.eq(userId))
                        .and(EXPORT_JOBS.REQUESTED_AT.ge(since)));
    }

    public PageResponse<ReportingViews.ExportJob> list(UUID companyId, UUID userId, ListQuery query) {
        return paginator.fetch(
                dsl,
                EXPORT_JOBS,
                EXPORT_JOBS.COMPANY_ID.eq(companyId).and(EXPORT_JOBS.USER_ID.eq(userId)),
                query,
                BINDING,
                this::toView);
    }

    /** Takes the oldest queued job ({@code SKIP LOCKED}: concurrent workers take different jobs) and marks it RUNNING. */
    public Optional<ReportingViews.ExportJob> claimNext(UUID companyId, OffsetDateTime now) {
        Optional<ExportJobsRecord> next = dsl.selectFrom(EXPORT_JOBS)
                .where(EXPORT_JOBS.COMPANY_ID.eq(companyId))
                .and(EXPORT_JOBS.STATUS.eq("QUEUED"))
                .orderBy(EXPORT_JOBS.REQUESTED_AT, EXPORT_JOBS.ID)
                .limit(1)
                .forUpdate()
                .skipLocked()
                .fetchOptional();
        next.ifPresent(r -> dsl.update(EXPORT_JOBS)
                .set(EXPORT_JOBS.STATUS, "RUNNING")
                .set(EXPORT_JOBS.STARTED_AT, now)
                .set(EXPORT_JOBS.VERSION, r.getVersion() + 1)
                .where(EXPORT_JOBS.COMPANY_ID.eq(companyId))
                .and(EXPORT_JOBS.ID.eq(r.getId()))
                .execute());
        return next.flatMap(r -> find(companyId, r.getId()));
    }

    public void succeed(
            UUID companyId, UUID id, UUID fileId, long rowCount, OffsetDateTime completedAt, OffsetDateTime expiresAt) {
        dsl.update(EXPORT_JOBS)
                .set(EXPORT_JOBS.STATUS, "SUCCEEDED")
                .set(EXPORT_JOBS.FILE_ID, fileId)
                .set(EXPORT_JOBS.ROW_COUNT, rowCount)
                .set(EXPORT_JOBS.COMPLETED_AT, completedAt)
                .set(EXPORT_JOBS.EXPIRES_AT, expiresAt)
                .set(EXPORT_JOBS.VERSION, EXPORT_JOBS.VERSION.plus(1))
                .where(EXPORT_JOBS.COMPANY_ID.eq(companyId))
                .and(EXPORT_JOBS.ID.eq(id))
                .and(EXPORT_JOBS.STATUS.eq("RUNNING"))
                .execute();
    }

    public void fail(UUID companyId, UUID id, String errorCode, @Nullable Long rowCount, OffsetDateTime completedAt) {
        dsl.update(EXPORT_JOBS)
                .set(EXPORT_JOBS.STATUS, "FAILED")
                .set(EXPORT_JOBS.ERROR_CODE, errorCode)
                .set(EXPORT_JOBS.ROW_COUNT, rowCount)
                .set(EXPORT_JOBS.COMPLETED_AT, completedAt)
                .set(EXPORT_JOBS.VERSION, EXPORT_JOBS.VERSION.plus(1))
                .where(EXPORT_JOBS.COMPANY_ID.eq(companyId))
                .and(EXPORT_JOBS.ID.eq(id))
                .and(EXPORT_JOBS.STATUS.in("QUEUED", "RUNNING"))
                .execute();
    }

    /** Jobs still RUNNING since before {@code startedBefore} (a worker died): failed as INTERRUPTED. */
    public int failInterrupted(UUID companyId, OffsetDateTime startedBefore, OffsetDateTime now) {
        return dsl.update(EXPORT_JOBS)
                .set(EXPORT_JOBS.STATUS, "FAILED")
                .set(EXPORT_JOBS.ERROR_CODE, "INTERRUPTED")
                .set(EXPORT_JOBS.COMPLETED_AT, now)
                .set(EXPORT_JOBS.VERSION, EXPORT_JOBS.VERSION.plus(1))
                .where(EXPORT_JOBS.COMPANY_ID.eq(companyId))
                .and(EXPORT_JOBS.STATUS.eq("RUNNING"))
                .and(EXPORT_JOBS.STARTED_AT.lt(startedBefore))
                .execute();
    }

    /** Succeeded jobs whose file expired, locked for the expiry. */
    public List<ReportingViews.ExportJob> lockExpired(UUID companyId, OffsetDateTime now, int limit) {
        return dsl.selectFrom(EXPORT_JOBS)
                .where(EXPORT_JOBS.COMPANY_ID.eq(companyId))
                .and(EXPORT_JOBS.STATUS.eq("SUCCEEDED"))
                .and(EXPORT_JOBS.EXPIRES_AT.lt(now))
                .orderBy(EXPORT_JOBS.EXPIRES_AT)
                .limit(limit)
                .forUpdate()
                .skipLocked()
                .fetch(this::toView);
    }

    public void expire(UUID companyId, UUID id) {
        dsl.update(EXPORT_JOBS)
                .set(EXPORT_JOBS.STATUS, "EXPIRED")
                .set(EXPORT_JOBS.FILE_ID, (UUID) null)
                .set(EXPORT_JOBS.VERSION, EXPORT_JOBS.VERSION.plus(1))
                .where(EXPORT_JOBS.COMPANY_ID.eq(companyId))
                .and(EXPORT_JOBS.ID.eq(id))
                .execute();
    }

    private ReportingViews.ExportJob toView(ExportJobsRecord r) {
        UUID[] scope = r.getBranchScope();
        return new ReportingViews.ExportJob(
                r.getId(),
                r.getUserId(),
                r.getReportCode(),
                json.read(r.getParameters()),
                r.getFormat(),
                scope == null ? null : Set.of(scope),
                r.getStatus(),
                r.getFileId(),
                r.getRowCount(),
                r.getErrorCode(),
                r.getRequestedAt(),
                r.getStartedAt(),
                r.getCompletedAt(),
                r.getExpiresAt(),
                r.getVersion());
    }
}
