package com.erp.partners.web;

import com.erp.partners.PartnersPermissions;
import com.erp.partners.application.CustomerService;
import com.erp.partners.application.PartnerCommands;
import com.erp.partners.application.PartnerListings;
import com.erp.platform.security.RequiresPermission;
import com.erp.platform.web.ApiPaths;
import com.erp.platform.web.EntityTags;
import com.erp.platform.web.paging.ListQueryParser;
import com.erp.platform.web.paging.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
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

/** Customer profiles and the customer list (API.md §17.4). */
@RestController
class CustomerController {

    private static final String C = ApiPaths.V1 + "/companies/{companyId}";

    private final CustomerService customers;
    private final ListQueryParser parser;

    CustomerController(CustomerService customers, ListQueryParser parser) {
        this.customers = customers;
        this.parser = parser;
    }

    record CustomerProfileRequest(
            @Nullable UUID customerGroupId,

            @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String currencyCode,
            @Nullable UUID paymentTermsId,
            @Nullable UUID defaultTaxCodeId,

            @DecimalMin("0") @Digits(integer = 15, fraction = 4) @Nullable BigDecimal creditLimit,

            @Nullable Boolean isOnHold) {}

    @RequiresPermission(PartnersPermissions.PARTNER_READ)
    @GetMapping(C + "/customers")
    PageResponse<PartnersResponses.CustomerRow> list(
            @PathVariable UUID companyId, @RequestParam MultiValueMap<String, String> parameters) {
        return customers
                .list(parser.parse(parameters, PartnerListings.CUSTOMERS))
                .map(PartnersResponses.CustomerRow::from);
    }

    /** Creates ({@code If-Match: W/"0"}) or replaces the partner's customer profile. */
    @RequiresPermission(PartnersPermissions.CUSTOMER_MANAGE)
    @PutMapping(C + "/partners/{partnerId}/customer-profile")
    ResponseEntity<PartnersResponses.Customer> put(
            @PathVariable UUID companyId,
            @PathVariable UUID partnerId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch,
            @Valid @RequestBody CustomerProfileRequest request) {
        var saved = customers.replace(
                partnerId,
                ifMatch,
                new PartnerCommands.Customer(
                        request.customerGroupId(),
                        request.currencyCode(),
                        request.paymentTermsId(),
                        request.defaultTaxCodeId(),
                        request.creditLimit(),
                        Boolean.TRUE.equals(request.isOnHold())));
        return ResponseEntity.ok()
                .eTag(EntityTags.forVersion(saved.version()))
                .body(PartnersResponses.Customer.from(saved));
    }
}
