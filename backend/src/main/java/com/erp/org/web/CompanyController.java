package com.erp.org.web;

import com.erp.org.OrgPermissions;
import com.erp.org.application.CompanyCommands;
import com.erp.org.application.CompanyService;
import com.erp.org.application.CompanyView;
import com.erp.org.application.OrgListings;
import com.erp.platform.security.AuthenticatedEndpoint;
import com.erp.platform.security.GlobalAccess;
import com.erp.platform.security.RequiresPermission;
import com.erp.platform.web.ApiPaths;
import com.erp.platform.web.EntityTags;
import com.erp.platform.web.paging.ListQueryParser;
import com.erp.platform.web.paging.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * Companies (API.md §17.2–§17.3). Creating and listing all companies is system administration
 * ({@code org.company.create}, a global permission); reading and changing one company happens inside
 * it (membership is checked by the company context interceptor).
 */
@RestController
class CompanyController {

    static final String MERGE_PATCH = "application/merge-patch+json";

    private final CompanyService companies;
    private final ListQueryParser parser;

    CompanyController(CompanyService companies, ListQueryParser parser) {
        this.companies = companies;
        this.parser = parser;
    }

    record CreateCompanyRequest(
            @NotBlank @Pattern(regexp = "^[A-Z0-9_-]{2,20}$") String code,

            @NotBlank @Size(max = 200) String legalName,
            @NotBlank @Size(max = 100) String displayName,
            @Size(max = 50) @Nullable String taxRegistrationNo,
            @Size(max = 50) @Nullable String registrationNo,
            @NotBlank @Pattern(regexp = "^[A-Z]{2}$") String countryCode,
            @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String baseCurrency,
            @NotBlank @Size(max = 64) String timezone,
            @Min(1) @Max(12) @Nullable Integer fiscalYearStartMonth,
            @Size(max = 200) @Nullable String addressLine1,
            @Size(max = 200) @Nullable String addressLine2,
            @Size(max = 100) @Nullable String city,
            @Size(max = 100) @Nullable String region,
            @Size(max = 20) @Nullable String postalCode) {}

    record CompanyResponse(
            UUID id,
            String code,
            String legalName,
            String displayName,
            @Nullable String taxRegistrationNo,
            @Nullable String registrationNo,
            String countryCode,
            String baseCurrency,
            String timezone,
            int fiscalYearStartMonth,
            @Nullable String addressLine1,
            @Nullable String addressLine2,
            @Nullable String city,
            @Nullable String region,
            @Nullable String postalCode,
            String roundingMode,
            String taxRounding,
            String status,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            int version) {

        static CompanyResponse from(CompanyView v) {
            return new CompanyResponse(
                    v.id(),
                    v.code(),
                    v.legalName(),
                    v.displayName(),
                    v.taxRegistrationNo(),
                    v.registrationNo(),
                    v.countryCode(),
                    v.baseCurrency(),
                    v.timezone(),
                    v.fiscalYearStartMonth(),
                    v.addressLine1(),
                    v.addressLine2(),
                    v.city(),
                    v.region(),
                    v.postalCode(),
                    v.roundingMode(),
                    v.taxRounding(),
                    v.status(),
                    v.createdAt(),
                    v.updatedAt(),
                    v.version());
        }
    }

    @GlobalAccess
    @RequiresPermission(OrgPermissions.COMPANY_CREATE)
    @PostMapping(ApiPaths.V1 + "/companies")
    ResponseEntity<CompanyResponse> create(@Valid @RequestBody CreateCompanyRequest request) {
        CompanyView created = companies.create(new CompanyCommands.Create(
                request.code(),
                request.legalName(),
                request.displayName(),
                request.taxRegistrationNo(),
                request.registrationNo(),
                request.countryCode(),
                request.baseCurrency(),
                request.timezone(),
                request.fiscalYearStartMonth() == null ? 1 : request.fiscalYearStartMonth(),
                request.addressLine1(),
                request.addressLine2(),
                request.city(),
                request.region(),
                request.postalCode()));
        return ResponseEntity.created(URI.create(ApiPaths.V1 + "/companies/" + created.id()))
                .eTag(EntityTags.forVersion(created.version()))
                .body(CompanyResponse.from(created));
    }

    @RequiresPermission(OrgPermissions.COMPANY_CREATE)
    @GetMapping(ApiPaths.V1 + "/admin/companies")
    PageResponse<CompanyResponse> listAll(@RequestParam MultiValueMap<String, String> parameters) {
        return companies.list(parser.parse(parameters, OrgListings.COMPANIES)).map(CompanyResponse::from);
    }

    @AuthenticatedEndpoint
    @GetMapping(ApiPaths.V1 + "/companies/{companyId}")
    ResponseEntity<CompanyResponse> get(@PathVariable UUID companyId) {
        CompanyView company = companies.get(companyId);
        return ResponseEntity.ok()
                .eTag(EntityTags.forVersion(company.version()))
                .body(CompanyResponse.from(company));
    }

    @RequiresPermission(OrgPermissions.COMPANY_MANAGE)
    @PatchMapping(path = ApiPaths.V1 + "/companies/{companyId}", consumes = MERGE_PATCH)
    ResponseEntity<CompanyResponse> patch(
            @PathVariable UUID companyId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch,
            @RequestBody JsonNode patch) {
        CompanyView company = companies.patch(companyId, ifMatch, patch);
        return ResponseEntity.ok()
                .eTag(EntityTags.forVersion(company.version()))
                .body(CompanyResponse.from(company));
    }
}
