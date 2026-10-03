package com.erp.partners.web;

import com.erp.partners.PartnersPermissions;
import com.erp.partners.application.PartnerCommands;
import com.erp.partners.application.PartnerListings;
import com.erp.partners.application.SupplierService;
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
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Supplier profiles and the supplier list (API.md §17.4). */
@RestController
class SupplierController {

    private static final String C = ApiPaths.V1 + "/companies/{companyId}";

    private final SupplierService suppliers;
    private final ListQueryParser parser;

    SupplierController(SupplierService suppliers, ListQueryParser parser) {
        this.suppliers = suppliers;
        this.parser = parser;
    }

    record SupplierProfileRequest(
            @Nullable UUID supplierGroupId,

            @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String currencyCode,
            @Nullable UUID paymentTermsId,
            @Nullable UUID defaultTaxCodeId,
            @Min(0) @Max(3650) @Nullable Integer leadTimeDays) {}

    @RequiresPermission(PartnersPermissions.PARTNER_READ)
    @GetMapping(C + "/suppliers")
    PageResponse<PartnersResponses.SupplierRow> list(
            @PathVariable UUID companyId, @RequestParam MultiValueMap<String, String> parameters) {
        return suppliers
                .list(parser.parse(parameters, PartnerListings.SUPPLIERS))
                .map(PartnersResponses.SupplierRow::from);
    }

    /** Creates ({@code If-Match: W/"0"}) or replaces the partner's supplier profile. */
    @RequiresPermission(PartnersPermissions.SUPPLIER_MANAGE)
    @PutMapping(C + "/partners/{partnerId}/supplier-profile")
    ResponseEntity<PartnersResponses.Supplier> put(
            @PathVariable UUID companyId,
            @PathVariable UUID partnerId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch,
            @Valid @RequestBody SupplierProfileRequest request) {
        var saved = suppliers.replace(
                partnerId,
                ifMatch,
                new PartnerCommands.Supplier(
                        request.supplierGroupId(),
                        request.currencyCode(),
                        request.paymentTermsId(),
                        request.defaultTaxCodeId(),
                        request.leadTimeDays()));
        return ResponseEntity.ok()
                .eTag(EntityTags.forVersion(saved.version()))
                .body(PartnersResponses.Supplier.from(saved));
    }
}
