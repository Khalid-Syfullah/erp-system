package com.erp.reporting.application;

import com.erp.platform.context.CurrentContext;
import com.erp.platform.context.RequestContext;
import com.erp.platform.files.FileService;
import com.erp.platform.web.ApiException;
import com.erp.reporting.domain.ExportFormat;
import com.erp.reporting.domain.ReportDefinition;
import com.erp.reporting.domain.ReportParameters;
import com.erp.reporting.domain.ReportSourceKind;
import com.erp.reporting.persistence.ExportJobRepository;
import com.erp.reporting.persistence.ViewReports;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The export worker (db-scheduler task {@code reporting-exports}; tests call it directly): claims
 * queued jobs one at a time ({@code SKIP LOCKED}), streams the report into a temporary file in the
 * requested format under the row and size limits, stores it through {@link FileService} and records
 * the result. Failures are recorded with an error code; nothing is retried automatically. The
 * expiry task deletes files after the retention period.
 */
@Component
public class ExportJobs {

    private static final Logger log = LoggerFactory.getLogger(ExportJobs.class);

    private final ExportJobRepository jobs;
    private final ReportCatalog catalog;
    private final ReportParameterParser parser;
    private final ViewReports views;
    private final FinancialReportRows financial;
    private final FileService files;
    private final ReportingContext context;
    private final ReportingProperties properties;
    private final TransactionTemplate tx;

    ExportJobs(
            ExportJobRepository jobs,
            ReportCatalog catalog,
            ReportParameterParser parser,
            ViewReports views,
            FinancialReportRows financial,
            FileService files,
            ReportingContext context,
            ReportingProperties properties,
            TransactionTemplate tx) {
        this.jobs = jobs;
        this.catalog = catalog;
        this.parser = parser;
        this.views = views;
        this.financial = financial;
        this.files = files;
        this.context = context;
        this.properties = properties;
        this.tx = tx;
    }

    /** Processes the company's queued exports; returns how many were processed. */
    public int processQueued(UUID companyId) {
        return CurrentContext.callWith(
                RequestContext.forRequest("job-report-exports-" + UUID.randomUUID())
                        .withCompany(companyId),
                () -> {
                    OffsetDateTime now = context.now();
                    // A RUNNING job older than the export timeout lost its worker.
                    tx.executeWithoutResult(s -> jobs.failInterrupted(
                            companyId, now.minus(properties.exportTimeout()).minus(Duration.ofMinutes(5)), now));
                    int processed = 0;
                    Optional<ReportingViews.ExportJob> next;
                    while ((next = claim(companyId)).isPresent()) {
                        process(companyId, next.get());
                        processed++;
                    }
                    return processed;
                });
    }

    /** Deletes the files of expired exports; returns how many expired. */
    public int expire(UUID companyId) {
        return CurrentContext.callWith(
                RequestContext.forRequest("job-report-export-expiry-" + UUID.randomUUID())
                        .withCompany(companyId),
                () -> {
                    Integer expired = tx.execute(s -> {
                        List<ReportingViews.ExportJob> due = jobs.lockExpired(companyId, context.now(), 500);
                        for (ReportingViews.ExportJob job : due) {
                            jobs.expire(companyId, job.id());
                            if (job.fileId() != null) {
                                files.delete(job.fileId());
                            }
                        }
                        return due.size();
                    });
                    return expired == null ? 0 : expired;
                });
    }

    private Optional<ReportingViews.ExportJob> claim(UUID companyId) {
        Optional<ReportingViews.ExportJob> job = tx.execute(s -> jobs.claimNext(companyId, context.now()));
        return job == null ? Optional.empty() : job;
    }

    private void process(UUID companyId, ReportingViews.ExportJob job) {
        RequestContext scoped = CurrentContext.require().withCompany(companyId, job.branchScope());
        CurrentContext.runWith(scoped, () -> {
            Path file = null;
            try {
                ReportDefinition definition =
                        catalog.find(job.reportCode()).orElseThrow(() -> new IllegalStateException("Unknown report"));
                LocalDate today = context.today(companyId);
                ReportParameters parameters = parser.parseBody(definition, job.parameters(), today, "/parameters");
                ExportFormat format = ExportFormat.valueOf(job.format());
                long maxRows = format == ExportFormat.PDF ? properties.maxPdfRows() : properties.maxExportRows();
                file = Files.createTempFile("erp-export-", "." + format.extension());
                long rows = write(
                        definition,
                        parameters,
                        format,
                        file,
                        new ReportScope(companyId, job.branchScope(), today),
                        maxRows);
                if (rows > maxRows) {
                    fail(companyId, job, "TOO_MANY_ROWS", rows);
                    return;
                }
                if (Files.size(file) > properties.maxExportSize().toBytes()) {
                    fail(companyId, job, "FILE_TOO_LARGE", rows);
                    return;
                }
                String fileName = definition.code() + "-" + today + "." + format.extension();
                FileService.Upload upload =
                        files.putGenerated("reporting", "export_job", fileName, format.contentType(), file);
                try {
                    tx.executeWithoutResult(s -> {
                        UUID fileId = files.register(upload, job.id()).id();
                        OffsetDateTime completed = context.now();
                        jobs.succeed(
                                companyId,
                                job.id(),
                                fileId,
                                rows,
                                completed,
                                completed.plus(properties.exportRetention()));
                    });
                } catch (RuntimeException e) {
                    files.discard(upload);
                    throw e;
                }
            } catch (ApiException e) {
                fail(companyId, job, "INVALID_PARAMETERS", null);
            } catch (RuntimeException | IOException e) {
                String code = timedOut(e) ? "TIMEOUT" : "ERROR";
                log.error("Export {} of report {} failed ({})", job.id(), job.reportCode(), code, e);
                fail(companyId, job, code, null);
            } finally {
                if (file != null) {
                    try {
                        Files.deleteIfExists(file);
                    } catch (IOException e) {
                        log.warn("Could not delete temporary export file {}", file, e);
                    }
                }
            }
        });
    }

    private long write(
            ReportDefinition definition,
            ReportParameters parameters,
            ExportFormat format,
            Path file,
            ReportScope scope,
            long maxRows)
            throws IOException {
        ExportWriters.Heading heading = new ExportWriters.Heading(
                definition.name(), context.companyName(scope.companyId()), parameters.asStrings(), context.now());
        try (ExportWriters.ExportWriter writer = ExportWriters.open(format, file, definition.columns(), heading)) {
            if (definition.source() == ReportSourceKind.VIEWS) {
                return views.stream(
                        definition,
                        parameters,
                        scope,
                        definition.defaultSort(),
                        maxRows,
                        properties.exportTimeout(),
                        writer);
            }
            List<@Nullable Object[]> rows = financial.rows(definition, parameters);
            for (int i = 0; i < rows.size() && i < maxRows; i++) {
                writer.row(rows.get(i));
            }
            return rows.size();
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    private void fail(UUID companyId, ReportingViews.ExportJob job, String code, @Nullable Long rows) {
        tx.executeWithoutResult(s -> jobs.fail(companyId, job.id(), code, rows, context.now()));
    }

    /** A statement cancelled by {@code statement_timeout} (SQLSTATE 57014). */
    private static boolean timedOut(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && "57014".equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }
}
