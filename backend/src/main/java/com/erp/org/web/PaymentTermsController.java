package com.erp.org.web;

import com.erp.org.OrgPermissions;
import com.erp.org.application.OrgListings;
import com.erp.org.application.PaymentTermsService;
import com.erp.org.application.PaymentTermsView;
import com.erp.org.application.ReferenceCommands;
import com.erp.platform.security.RequiresPermission;
import com.erp.platform.web.ApiPaths;
import com.erp.platform.web.EntityTags;
import com.erp.platform.web.paging.ListQueryParser;
import com.erp.platform.web.paging.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Payment terms of a company and their due-date calculation (API.md §17.3). */
@RestController
@RequestMapping(ApiPaths.V1 + "/companies/{companyId}/payment-terms")
class PaymentTermsController {

    private final PaymentTermsService terms;
    private final ListQueryParser parser;

    PaymentTermsController(PaymentTermsService terms, ListQueryParser parser) {
        this.terms = terms;
        this.parser = parser;
    }

    record CreateTermsRequest(
            @NotBlank @Pattern(regexp = "^[A-Z0-9_-]{1,20}$") String code,

            @NotBlank @Size(max = 100) String name,

            @NotNull @Min(0) @Max(PaymentTermsService.MAX_DUE_DAYS) Integer dueDays,

            @Pattern(regexp = "^(DOCUMENT_DATE|END_OF_MONTH)$") @Nullable String dueBasis) {}

    record TermsResponse(
            UUID id,
            String code,
            String name,
            int dueDays,
            String dueBasis,
            boolean isActive,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            int version) {

        static TermsResponse from(PaymentTermsView v) {
            return new TermsResponse(
                    v.id(),
                    v.code(),
                    v.name(),
                    v.dueDays(),
                    v.dueBasis(),
                    v.active(),
                    v.createdAt(),
                    v.updatedAt(),
                    v.version());
        }
    }

    record DueDateResponse(LocalDate documentDate, LocalDate dueDate) {}

    @RequiresPermission(OrgPermissions.PAYMENT_TERMS_READ)
    @GetMapping
    PageResponse<TermsResponse> list(
            @PathVariable UUID companyId, @RequestParam MultiValueMap<String, String> parameters) {
        return terms.list(parser.parse(parameters, OrgListings.PAYMENT_TERMS)).map(TermsResponse::from);
    }

    @RequiresPermission(OrgPermissions.PAYMENT_TERMS_READ)
    @GetMapping("/{termsId}")
    ResponseEntity<TermsResponse> get(@PathVariable UUID companyId, @PathVariable UUID termsId) {
        return withETag(terms.get(termsId));
    }

    @RequiresPermission(OrgPermissions.PAYMENT_TERMS_READ)
    @GetMapping("/{termsId}/due-date")
    DueDateResponse dueDate(
            @PathVariable UUID companyId, @PathVariable UUID termsId, @RequestParam LocalDate documentDate) {
        return new DueDateResponse(documentDate, terms.dueDate(termsId, documentDate));
    }

    @RequiresPermission(OrgPermissions.PAYMENT_TERMS_MANAGE)
    @PostMapping
    ResponseEntity<TermsResponse> create(@PathVariable UUID companyId, @Valid @RequestBody CreateTermsRequest request) {
        PaymentTermsView created = terms.create(new ReferenceCommands.PaymentTerms(
                request.code(),
                request.name(),
                request.dueDays(),
                request.dueBasis() == null ? "DOCUMENT_DATE" : request.dueBasis(),
                true));
        return ResponseEntity.created(
                        URI.create(ApiPaths.V1 + "/companies/" + companyId + "/payment-terms/" + created.id()))
                .eTag(EntityTags.forVersion(created.version()))
                .body(TermsResponse.from(created));
    }

    @RequiresPermission(OrgPermissions.PAYMENT_TERMS_MANAGE)
    @PatchMapping(path = "/{termsId}", consumes = CompanyController.MERGE_PATCH)
    ResponseEntity<TermsResponse> patch(
            @PathVariable UUID companyId,
            @PathVariable UUID termsId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch,
            @RequestBody JsonNode patch) {
        return withETag(terms.patch(termsId, ifMatch, patch));
    }

    @RequiresPermission(OrgPermissions.PAYMENT_TERMS_MANAGE)
    @PostMapping("/{termsId}/deactivate")
    ResponseEntity<TermsResponse> deactivate(
            @PathVariable UUID companyId,
            @PathVariable UUID termsId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch) {
        return withETag(terms.setActive(termsId, ifMatch, false));
    }

    @RequiresPermission(OrgPermissions.PAYMENT_TERMS_MANAGE)
    @PostMapping("/{termsId}/activate")
    ResponseEntity<TermsResponse> activate(
            @PathVariable UUID companyId,
            @PathVariable UUID termsId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch) {
        return withETag(terms.setActive(termsId, ifMatch, true));
    }

    private static ResponseEntity<TermsResponse> withETag(PaymentTermsView view) {
        return ResponseEntity.ok().eTag(EntityTags.forVersion(view.version())).body(TermsResponse.from(view));
    }
}
