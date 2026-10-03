package com.erp.procurement.web;

import com.erp.platform.security.RequiresPermission;
import com.erp.platform.web.ApiPaths;
import com.erp.platform.web.EntityTags;
import com.erp.procurement.ProcurementPermissions;
import com.erp.procurement.application.ProcurementSettingsService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
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

/** Procurement settings (API.md §17.6). */
@RestController
class ProcurementSettingsController {

    private static final String P = ApiPaths.V1 + "/companies/{companyId}/settings/procurement";

    private final ProcurementSettingsService settings;

    ProcurementSettingsController(ProcurementSettingsService settings) {
        this.settings = settings;
    }

    record SettingsRequest(
            @DecimalMin("0") @Digits(integer = 15, fraction = 4) @Nullable BigDecimal poApprovalThresholdBase,

            @NotNull @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 4) BigDecimal priceMatchTolerancePercent,

            @NotNull @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 4) BigDecimal qtyMatchTolerancePercent) {}

    @RequiresPermission(ProcurementPermissions.SETTINGS_MANAGE)
    @GetMapping(P)
    ResponseEntity<ProcurementResponses.Settings> get(@PathVariable UUID companyId) {
        return ProcurementResponses.Settings.entity(settings.get());
    }

    @RequiresPermission(ProcurementPermissions.SETTINGS_MANAGE)
    @PutMapping(P)
    ResponseEntity<ProcurementResponses.Settings> put(
            @PathVariable UUID companyId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch,
            @Valid @RequestBody SettingsRequest request) {
        return ProcurementResponses.Settings.entity(settings.replace(
                ifMatch,
                request.poApprovalThresholdBase(),
                request.priceMatchTolerancePercent(),
                request.qtyMatchTolerancePercent()));
    }
}
