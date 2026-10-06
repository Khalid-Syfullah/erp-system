package com.erp.reporting.web;

import com.erp.platform.security.AuthenticatedEndpoint;
import com.erp.platform.web.EntityTags;
import com.erp.platform.web.paging.ListQueryParser;
import com.erp.platform.web.paging.PageResponse;
import com.erp.reporting.application.ReportingListings;
import com.erp.reporting.application.SavedReportService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * Saved report parameters (API.md §17.11): the owner's own and the company's shared ones, for
 * reports the caller may run. Sharing needs {@code reporting.saved_report.share}.
 */
@RestController
class SavedReportController {

    private static final String C = ReportingController.C;
    private static final String MERGE_PATCH = "application/merge-patch+json";

    private final SavedReportService saved;
    private final ListQueryParser parser;

    SavedReportController(SavedReportService saved, ListQueryParser parser) {
        this.saved = saved;
        this.parser = parser;
    }

    record SavedReportRequest(
            @NotBlank @Size(max = 63) String reportCode,
            @NotBlank @Size(max = 100) String name,
            @Nullable @Size(max = 30) Map<String, @Size(max = 200) String> parameters,
            @Nullable Boolean isShared) {}

    @AuthenticatedEndpoint
    @GetMapping(C + "/saved-reports")
    PageResponse<ReportingResponses.SavedReport> list(
            @PathVariable UUID companyId, @RequestParam MultiValueMap<String, String> parameters) {
        UUID caller = ReportingController.caller();
        return saved.list(parser.parse(parameters, ReportingListings.SAVED_REPORTS))
                .map(s -> ReportingResponses.SavedReport.of(s, caller));
    }

    @AuthenticatedEndpoint
    @GetMapping(C + "/saved-reports/{savedReportId}")
    ResponseEntity<ReportingResponses.SavedReport> get(@PathVariable UUID companyId, @PathVariable UUID savedReportId) {
        var report = saved.get(savedReportId);
        return ResponseEntity.ok()
                .eTag(EntityTags.forVersion(report.version()))
                .body(ReportingResponses.SavedReport.of(report, ReportingController.caller()));
    }

    @AuthenticatedEndpoint
    @PostMapping(C + "/saved-reports")
    ResponseEntity<ReportingResponses.SavedReport> create(
            @PathVariable UUID companyId, @Valid @RequestBody SavedReportRequest request) {
        var report = saved.create(new SavedReportService.Command(
                request.reportCode(),
                request.name(),
                request.parameters() == null ? Map.of() : request.parameters(),
                Boolean.TRUE.equals(request.isShared())));
        return ResponseEntity.created(URI.create(ReportingController.C.replace("{companyId}", companyId.toString())
                        + "/saved-reports/" + report.id()))
                .eTag(EntityTags.forVersion(report.version()))
                .body(ReportingResponses.SavedReport.of(report, ReportingController.caller()));
    }

    @AuthenticatedEndpoint
    @PatchMapping(path = C + "/saved-reports/{savedReportId}", consumes = MERGE_PATCH)
    ResponseEntity<ReportingResponses.SavedReport> patch(
            @PathVariable UUID companyId,
            @PathVariable UUID savedReportId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch,
            @RequestBody JsonNode patch) {
        var report = saved.patch(savedReportId, ifMatch, patch);
        return ResponseEntity.ok()
                .eTag(EntityTags.forVersion(report.version()))
                .body(ReportingResponses.SavedReport.of(report, ReportingController.caller()));
    }

    @AuthenticatedEndpoint
    @DeleteMapping(C + "/saved-reports/{savedReportId}")
    ResponseEntity<Void> delete(
            @PathVariable UUID companyId,
            @PathVariable UUID savedReportId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch) {
        saved.delete(savedReportId, ifMatch);
        return ResponseEntity.noContent().build();
    }
}
