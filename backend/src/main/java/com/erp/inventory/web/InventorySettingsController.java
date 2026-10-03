package com.erp.inventory.web;

import com.erp.inventory.InventoryPermissions;
import com.erp.inventory.application.InventoryListings;
import com.erp.inventory.application.InventoryReferenceService;
import com.erp.inventory.application.InventoryViews;
import com.erp.platform.security.RequiresPermission;
import com.erp.platform.web.ApiPaths;
import com.erp.platform.web.EntityTags;
import com.erp.platform.web.paging.ListQueryParser;
import com.erp.platform.web.paging.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Reason codes and inventory settings (API.md §17.5). */
@RestController
class InventorySettingsController {

    private static final String C = ApiPaths.V1 + "/companies/{companyId}";

    private final InventoryReferenceService reference;
    private final ListQueryParser parser;

    InventorySettingsController(InventoryReferenceService reference, ListQueryParser parser) {
        this.reference = reference;
        this.parser = parser;
    }

    record ReasonCodeRequest(
            @NotBlank @Pattern(regexp = "^[A-Z0-9_-]{1,20}$") String code,

            @NotBlank @Size(max = 100) String name,

            @NotBlank @Pattern(regexp = "^(ADJUSTMENT|SCRAP|COUNT)$")
            String appliesTo) {}

    record SettingsRequest(
            @NotNull @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 4) BigDecimal overReceiptTolerancePercent,

            @DecimalMin("0") @Digits(integer = 15, fraction = 4) @Nullable BigDecimal adjustmentApprovalThreshold) {}

    @RequiresPermission(InventoryPermissions.ADJUSTMENT_MANAGE)
    @GetMapping(C + "/reason-codes")
    PageResponse<InventoryResponses.ReasonCode> reasonCodes(
            @PathVariable UUID companyId, @RequestParam MultiValueMap<String, String> parameters) {
        return reference
                .reasonCodes(parser.parse(parameters, InventoryListings.REASON_CODES))
                .map(InventoryResponses.ReasonCode::from);
    }

    @RequiresPermission(InventoryPermissions.ADJUSTMENT_MANAGE)
    @PostMapping(C + "/reason-codes")
    ResponseEntity<InventoryResponses.ReasonCode> createReasonCode(
            @PathVariable UUID companyId, @Valid @RequestBody ReasonCodeRequest request) {
        return ResponseEntity.status(201)
                .body(InventoryResponses.ReasonCode.from(reference.createReasonCode(
                        request.code(), request.name().strip(), request.appliesTo())));
    }

    @RequiresPermission(InventoryPermissions.SETTINGS_MANAGE)
    @GetMapping(C + "/settings/inventory")
    ResponseEntity<InventoryResponses.Settings> settings(@PathVariable UUID companyId) {
        return settings(reference.settings());
    }

    @RequiresPermission(InventoryPermissions.SETTINGS_MANAGE)
    @PutMapping(C + "/settings/inventory")
    ResponseEntity<InventoryResponses.Settings> replaceSettings(
            @PathVariable UUID companyId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch,
            @Valid @RequestBody SettingsRequest request) {
        return settings(reference.replaceSettings(
                ifMatch, request.overReceiptTolerancePercent(), request.adjustmentApprovalThreshold()));
    }

    private static ResponseEntity<InventoryResponses.Settings> settings(InventoryViews.Settings settings) {
        return ResponseEntity.ok()
                .eTag(EntityTags.forVersion(settings.version()))
                .body(InventoryResponses.Settings.from(settings));
    }
}
