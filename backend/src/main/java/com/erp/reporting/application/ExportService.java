package com.erp.reporting.application;

import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditPort;
import com.erp.platform.context.CurrentContext;
import com.erp.platform.files.FileService;
import com.erp.platform.web.ApiException;
import com.erp.platform.web.PlatformErrorCode;
import com.erp.platform.web.paging.ListQuery;
import com.erp.platform.web.paging.PageResponse;
import com.erp.reporting.domain.ExportFormat;
import com.erp.reporting.domain.ExportStatus;
import com.erp.reporting.domain.ReportDefinition;
import com.erp.reporting.domain.ReportParameters;
import com.erp.reporting.persistence.ExportJobRepository;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Asynchronous report exports (API.md §17.11, ADR-040): a request validates the parameters and
 * queues a job with the requester's branch scope; {@link ExportJobs} writes the file; only the
 * requester downloads it, while still holding the report's permissions, until it expires. Each
 * request is audited ({@code EXPORT}).
 */
@Service
public class ExportService {

    /** An export file being downloaded: its name, type, size and content stream. */
    public record Download(String fileName, String contentType, long size, java.io.InputStream content) {}

    private final ReportRunner reports;
    private final ReportParameterParser parser;
    private final ExportJobRepository jobs;
    private final FileService files;
    private final ReportingContext context;
    private final ReportingProperties properties;
    private final AuditPort audit;
    private final TransactionTemplate readOnly;

    ExportService(
            ReportRunner reports,
            ReportParameterParser parser,
            ExportJobRepository jobs,
            FileService files,
            ReportingContext context,
            ReportingProperties properties,
            AuditPort audit,
            PlatformTransactionManager transactions) {
        this.reports = reports;
        this.parser = parser;
        this.jobs = jobs;
        this.files = files;
        this.context = context;
        this.properties = properties;
        this.audit = audit;
        this.readOnly = new TransactionTemplate(transactions);
        this.readOnly.setReadOnly(true);
    }

    /** Queues an export; runs in the idempotency executor's transaction. */
    @Transactional
    public ReportingViews.ExportJob request(String code, ExportFormat format, Map<String, String> parameters) {
        ReportDefinition definition = reports.authorize(code);
        UUID companyId = context.companyId();
        UUID userId = context.actor();
        ReportParameters effective = parser.parseBody(definition, parameters, context.today(companyId), "/parameters");
        if (jobs.requestedSince(companyId, userId, context.now().minus(Duration.ofHours(1)))
                >= properties.exportsPerUserPerHour()) {
            throw new ApiException(
                    PlatformErrorCode.RATE_LIMITED,
                    "At most " + properties.exportsPerUserPerHour() + " exports per hour can be requested.");
        }
        UUID id = jobs.insert(
                companyId,
                userId,
                code,
                effective.asStrings(),
                format.name(),
                CurrentContext.require().branchScope());
        audit.record(AuditEvent.builder("EXPORT", "reporting")
                .entity("export_job", id, code)
                .detail("format", format.name())
                .detail("parameters", effective.asStrings())
                .build());
        return jobs.find(companyId, id).orElseThrow();
    }

    /** The caller's own export job, if the caller still holds the report's permissions. */
    @Transactional(readOnly = true)
    public ReportingViews.ExportJob get(UUID id) {
        ReportingViews.ExportJob job = jobs.find(context.companyId(), id)
                .filter(j -> j.userId().equals(context.actor()))
                .orElseThrow(ApiException::notFound);
        reports.authorize(job.reportCode());
        return job;
    }

    @Transactional(readOnly = true)
    public PageResponse<ReportingViews.ExportJob> list(ListQuery query) {
        return jobs.list(context.companyId(), context.actor(), query);
    }

    /** The file of a succeeded export, streamed from object storage (outside any transaction). */
    public Download content(UUID id) {
        // The job is read in its own transaction (a self-call would bypass @Transactional and RLS).
        ReportingViews.ExportJob job = java.util.Objects.requireNonNull(readOnly.execute(s -> get(id)));
        switch (ExportStatus.valueOf(job.status())) {
            case QUEUED, RUNNING ->
                throw new ApiException(ReportingErrorCode.EXPORT_NOT_READY, "The export is " + job.status() + ".");
            case FAILED ->
                throw new ApiException(ReportingErrorCode.EXPORT_FAILED, "The export failed: " + job.errorCode() + ".");
            case EXPIRED -> throw new ApiException(ReportingErrorCode.EXPORT_EXPIRED, "The export has expired.");
            case SUCCEEDED -> {}
        }
        if (job.expiresAt() != null && job.expiresAt().isBefore(context.now())) {
            throw new ApiException(ReportingErrorCode.EXPORT_EXPIRED, "The export has expired.");
        }
        FileService.Stream stream = files.open(java.util.Objects.requireNonNull(job.fileId()))
                .orElseThrow(() -> new ApiException(ReportingErrorCode.EXPORT_EXPIRED, "The export has expired."));
        return new Download(
                stream.file().fileName(),
                stream.file().contentType(),
                stream.file().sizeBytes(),
                stream.content());
    }
}
