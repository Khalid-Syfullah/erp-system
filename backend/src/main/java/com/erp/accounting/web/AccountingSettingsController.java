package com.erp.accounting.web;

import com.erp.accounting.AccountingPermissions;
import com.erp.accounting.application.AccountingCommands;
import com.erp.accounting.application.AccountingSettingsService;
import com.erp.platform.security.RequiresPermission;
import com.erp.platform.web.ApiPaths;
import com.erp.platform.web.EntityTags;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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

/** Accounting settings (API.md §17.8). */
@RestController
class AccountingSettingsController {

    private static final String P = ApiPaths.V1 + "/companies/{companyId}/settings/accounting";

    private final AccountingSettingsService settings;

    AccountingSettingsController(AccountingSettingsService settings) {
        this.settings = settings;
    }

    record SettingsRequest(
            @NotNull UUID retainedEarningsAccountId,
            @NotNull Boolean allowManualEntriesInSoftClosed,
            @NotNull @Min(0) @Max(100) Integer maxRoundingDifferenceMinorUnits,

            @DecimalMin("0") @Digits(integer = 15, fraction = 4) @Nullable BigDecimal manualEntryApprovalThresholdBase) {}

    @RequiresPermission(AccountingPermissions.SETTINGS_MANAGE)
    @GetMapping(P)
    ResponseEntity<AccountingResponses.Settings> get(@PathVariable UUID companyId) {
        return AccountingResponses.Settings.entity(settings.get());
    }

    @RequiresPermission(AccountingPermissions.SETTINGS_MANAGE)
    @PutMapping(P)
    ResponseEntity<AccountingResponses.Settings> put(
            @PathVariable UUID companyId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch,
            @Valid @RequestBody SettingsRequest request) {
        return AccountingResponses.Settings.entity(settings.replace(
                ifMatch,
                new AccountingCommands.Settings(
                        request.retainedEarningsAccountId(),
                        request.allowManualEntriesInSoftClosed(),
                        request.maxRoundingDifferenceMinorUnits(),
                        request.manualEntryApprovalThresholdBase())));
    }
}
