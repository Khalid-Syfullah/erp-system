package com.erp.accounting.web;

import com.erp.accounting.AccountingPermissions;
import com.erp.accounting.application.ReconciliationService;
import com.erp.platform.security.RequiresPermission;
import com.erp.platform.web.ApiPaths;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Manual bank reconciliation marks (API.md §17.8). */
@RestController
class ReconciliationController {

    private static final String P = ApiPaths.V1 + "/companies/{companyId}/bank-reconciliation-marks";

    private final ReconciliationService reconciliation;

    ReconciliationController(ReconciliationService reconciliation) {
        this.reconciliation = reconciliation;
    }

    record MarkRequest(
            @NotNull @Size(min = 1, max = 1000) List<@NotNull UUID> journalLineIds,
            @NotBlank @Size(max = 100) String statementReference,
            @NotNull LocalDate statementDate) {}

    record UnmarkRequest(@NotNull @Size(min = 1, max = 1000) List<@NotNull UUID> journalLineIds) {}

    @RequiresPermission(AccountingPermissions.BANK_RECONCILIATION_MANAGE)
    @PostMapping(P)
    AccountingResponses.Reconciliation mark(@PathVariable UUID companyId, @Valid @RequestBody MarkRequest request) {
        return new AccountingResponses.Reconciliation(
                reconciliation.mark(request.journalLineIds(), request.statementReference(), request.statementDate()));
    }

    @RequiresPermission(AccountingPermissions.BANK_RECONCILIATION_MANAGE)
    @DeleteMapping(P)
    AccountingResponses.Reconciliation unmark(@PathVariable UUID companyId, @Valid @RequestBody UnmarkRequest request) {
        return new AccountingResponses.Reconciliation(reconciliation.unmark(request.journalLineIds()));
    }
}
