package com.erp.reporting.web;

import com.erp.platform.context.CurrentContext;
import com.erp.platform.idempotency.IdempotencyExecutor;
import com.erp.platform.security.AuthenticatedEndpoint;
import com.erp.platform.security.RequiresPermission;
import com.erp.platform.web.ApiPaths;
import com.erp.platform.web.paging.ListQueryParser;
import com.erp.platform.web.paging.PageResponse;
import com.erp.reporting.ReportingPermissions;
import com.erp.reporting.application.ExportService;
import com.erp.reporting.application.ReportRunner;
import com.erp.reporting.application.ReportingListings;
import com.erp.reporting.domain.ExportFormat;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The report catalogue, synchronous reports and asynchronous exports (API.md §17.11). A report's own
 * permissions are checked per request (404 for an unknown report, 403 without its permissions).
 * Accounting serves its financial statements at the same paths ({@code {c}/reports/trial-balance}
 * …); the catalogue lists them and exports them through here (ADR-040).
 */
@RestController
class ReportingController {

    static final String C = ApiPaths.V1 + "/companies/{companyId}";

    private final ReportRunner reports;
    private final ExportService exports;
    private final ListQueryParser parser;
    private final IdempotencyExecutor idempotency;

    ReportingController(
            ReportRunner reports, ExportService exports, ListQueryParser parser, IdempotencyExecutor idempotency) {
        this.reports = reports;
        this.exports = exports;
        this.parser = parser;
        this.idempotency = idempotency;
    }

    record ExportRequest(
            @NotNull @Pattern(regexp = "^(CSV|XLSX|PDF)$") String format,
            @Nullable @Size(max = 30) Map<String, @Size(max = 200) String> parameters) {}

    @AuthenticatedEndpoint
    @GetMapping(C + "/reports")
    List<ReportingResponses.Report> catalogue(@PathVariable UUID companyId) {
        return reports.catalogue().stream()
                .map(d -> ReportingResponses.Report.of(companyId, d))
                .toList();
    }

    /** Requires the report's permissions (checked by the service). */
    @AuthenticatedEndpoint
    @GetMapping(C + "/reports/{reportCode}")
    ReportingResponses.ReportRun run(
            @PathVariable UUID companyId,
            @PathVariable String reportCode,
            @RequestParam MultiValueMap<String, String> parameters) {
        return ReportingResponses.ReportRun.of(reports.run(reportCode, parameters));
    }

    /** [I] Requires {@code reporting.export.create} and the report's permissions. */
    @RequiresPermission(ReportingPermissions.EXPORT_CREATE)
    @PostMapping(C + "/reports/{reportCode}/exports")
    ResponseEntity<?> export(
            @PathVariable UUID companyId,
            @PathVariable String reportCode,
            @Valid @RequestBody ExportRequest request,
            HttpServletRequest http) {
        return idempotency.execute(http, request, true, () -> {
            var job = exports.request(
                    reportCode,
                    ExportFormat.valueOf(request.format()),
                    request.parameters() == null ? Map.of() : request.parameters());
            return ResponseEntity.accepted()
                    .location(URI.create(ReportingResponses.ExportJob.path(companyId, job.id())))
                    .body(ReportingResponses.ExportJob.of(companyId, job));
        });
    }

    @RequiresPermission(ReportingPermissions.EXPORT_CREATE)
    @GetMapping(C + "/report-exports")
    PageResponse<ReportingResponses.ExportJob> exports(
            @PathVariable UUID companyId, @RequestParam MultiValueMap<String, String> parameters) {
        return exports.list(parser.parse(parameters, ReportingListings.EXPORT_JOBS))
                .map(j -> ReportingResponses.ExportJob.of(companyId, j));
    }

    @RequiresPermission(ReportingPermissions.EXPORT_CREATE)
    @GetMapping(C + "/report-exports/{exportId}")
    ReportingResponses.ExportJob exportJob(@PathVariable UUID companyId, @PathVariable UUID exportId) {
        return ReportingResponses.ExportJob.of(companyId, exports.get(exportId));
    }

    /** The export file, streamed from object storage. */
    @RequiresPermission(ReportingPermissions.EXPORT_CREATE)
    @GetMapping(C + "/report-exports/{exportId}/content")
    ResponseEntity<Resource> content(@PathVariable UUID companyId, @PathVariable UUID exportId) {
        ExportService.Download download = exports.content(exportId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(download.contentType()))
                .contentLength(download.size())
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(download.fileName())
                                .build()
                                .toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new InputStreamResource(download.content()));
    }

    static UUID caller() {
        return CurrentContext.requireActor().userId();
    }
}
