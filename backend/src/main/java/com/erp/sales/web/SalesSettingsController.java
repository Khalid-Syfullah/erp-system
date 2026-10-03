package com.erp.sales.web;

import com.erp.platform.security.RequiresPermission;
import com.erp.platform.web.ApiPaths;
import com.erp.platform.web.EntityTags;
import com.erp.sales.SalesPermissions;
import com.erp.sales.application.SalesSettingsService;
import com.erp.sales.application.SalesViews;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Sales settings (API.md §17.7). */
@RestController
class SalesSettingsController {

    private static final String P = ApiPaths.V1 + "/companies/{companyId}/settings/sales";

    private final SalesSettingsService settings;

    SalesSettingsController(SalesSettingsService settings) {
        this.settings = settings;
    }

    record SettingsRequest(
            @NotNull @Pattern(regexp = "^(ORDERED|DELIVERED)$")
            String defaultInvoicePolicy,

            @NotNull @Pattern(regexp = "^(NONE|WARN|BLOCK)$")
            String creditCheckMode,

            @NotNull @Min(1) @Max(365) Integer quotationValidityDays,
            @NotNull Boolean reserveOnConfirm,

            @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 4) @Nullable BigDecimal discountApprovalThresholdPercent) {}

    @RequiresPermission(SalesPermissions.SETTINGS_MANAGE)
    @GetMapping(P)
    ResponseEntity<SalesResponses.Settings> get(@PathVariable UUID companyId) {
        return SalesResponses.Settings.entity(settings.get());
    }

    @RequiresPermission(SalesPermissions.SETTINGS_MANAGE)
    @PutMapping(P)
    ResponseEntity<SalesResponses.Settings> put(
            @PathVariable UUID companyId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch,
            @Valid @RequestBody SettingsRequest request) {
        return SalesResponses.Settings.entity(settings.replace(
                ifMatch,
                new SalesViews.Settings(
                        request.defaultInvoicePolicy(),
                        request.creditCheckMode(),
                        request.quotationValidityDays(),
                        request.reserveOnConfirm(),
                        request.discountApprovalThresholdPercent(),
                        0)));
    }
}
