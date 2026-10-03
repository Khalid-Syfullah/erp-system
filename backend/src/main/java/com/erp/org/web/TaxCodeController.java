package com.erp.org.web;

import com.erp.org.OrgPermissions;
import com.erp.org.application.OrgListings;
import com.erp.org.application.ReferenceCommands;
import com.erp.org.application.TaxCodeService;
import com.erp.org.application.TaxCodeView;
import com.erp.platform.security.RequiresPermission;
import com.erp.platform.web.ApiPaths;
import com.erp.platform.web.EntityTags;
import com.erp.platform.web.paging.ListQueryParser;
import com.erp.platform.web.paging.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
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

/** Tax codes of a company (API.md §17.3). */
@RestController
@RequestMapping(ApiPaths.V1 + "/companies/{companyId}/tax-codes")
class TaxCodeController {

    private final TaxCodeService taxCodes;
    private final ListQueryParser parser;

    TaxCodeController(TaxCodeService taxCodes, ListQueryParser parser) {
        this.taxCodes = taxCodes;
        this.parser = parser;
    }

    record CreateTaxCodeRequest(
            @NotBlank @Pattern(regexp = "^[A-Z0-9_-]{1,20}$") String code,

            @NotBlank @Size(max = 100) String name,

            @NotBlank @Pattern(regexp = "^(SALES|PURCHASE|BOTH)$")
            String scope,

            @NotNull @DecimalMin("0") @DecimalMax("100") BigDecimal ratePercent,
            @Nullable Boolean isExempt,
            @Nullable LocalDate validFrom,
            @Nullable LocalDate validTo) {}

    record TaxCodeResponse(
            UUID id,
            String code,
            String name,
            String scope,
            BigDecimal ratePercent,
            boolean isExempt,
            @Nullable LocalDate validFrom,
            @Nullable LocalDate validTo,
            boolean isActive,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            int version) {

        static TaxCodeResponse from(TaxCodeView v) {
            return new TaxCodeResponse(
                    v.id(),
                    v.code(),
                    v.name(),
                    v.scope(),
                    v.ratePercent(),
                    v.exempt(),
                    v.validFrom(),
                    v.validTo(),
                    v.active(),
                    v.createdAt(),
                    v.updatedAt(),
                    v.version());
        }
    }

    @RequiresPermission(OrgPermissions.TAX_CODE_READ)
    @GetMapping
    PageResponse<TaxCodeResponse> list(
            @PathVariable UUID companyId, @RequestParam MultiValueMap<String, String> parameters) {
        return taxCodes.list(parser.parse(parameters, OrgListings.TAX_CODES)).map(TaxCodeResponse::from);
    }

    @RequiresPermission(OrgPermissions.TAX_CODE_READ)
    @GetMapping("/{taxCodeId}")
    ResponseEntity<TaxCodeResponse> get(@PathVariable UUID companyId, @PathVariable UUID taxCodeId) {
        return withETag(taxCodes.get(taxCodeId));
    }

    @RequiresPermission(OrgPermissions.TAX_CODE_MANAGE)
    @PostMapping
    ResponseEntity<TaxCodeResponse> create(
            @PathVariable UUID companyId, @Valid @RequestBody CreateTaxCodeRequest request) {
        TaxCodeView created = taxCodes.create(new ReferenceCommands.TaxCode(
                request.code(),
                request.name(),
                request.scope(),
                request.ratePercent(),
                Boolean.TRUE.equals(request.isExempt()),
                request.validFrom(),
                request.validTo(),
                true));
        return ResponseEntity.created(
                        URI.create(ApiPaths.V1 + "/companies/" + companyId + "/tax-codes/" + created.id()))
                .eTag(EntityTags.forVersion(created.version()))
                .body(TaxCodeResponse.from(created));
    }

    @RequiresPermission(OrgPermissions.TAX_CODE_MANAGE)
    @PatchMapping(path = "/{taxCodeId}", consumes = CompanyController.MERGE_PATCH)
    ResponseEntity<TaxCodeResponse> patch(
            @PathVariable UUID companyId,
            @PathVariable UUID taxCodeId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch,
            @RequestBody JsonNode patch) {
        return withETag(taxCodes.patch(taxCodeId, ifMatch, patch));
    }

    @RequiresPermission(OrgPermissions.TAX_CODE_MANAGE)
    @PostMapping("/{taxCodeId}/deactivate")
    ResponseEntity<TaxCodeResponse> deactivate(
            @PathVariable UUID companyId,
            @PathVariable UUID taxCodeId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch) {
        return withETag(taxCodes.setActive(taxCodeId, ifMatch, false));
    }

    @RequiresPermission(OrgPermissions.TAX_CODE_MANAGE)
    @PostMapping("/{taxCodeId}/activate")
    ResponseEntity<TaxCodeResponse> activate(
            @PathVariable UUID companyId,
            @PathVariable UUID taxCodeId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch) {
        return withETag(taxCodes.setActive(taxCodeId, ifMatch, true));
    }

    private static ResponseEntity<TaxCodeResponse> withETag(TaxCodeView taxCode) {
        return ResponseEntity.ok()
                .eTag(EntityTags.forVersion(taxCode.version()))
                .body(TaxCodeResponse.from(taxCode));
    }
}
