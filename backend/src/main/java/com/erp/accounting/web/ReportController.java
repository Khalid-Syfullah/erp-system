package com.erp.accounting.web;

import com.erp.accounting.AccountingPermissions;
import com.erp.accounting.api.AccountingReports;
import com.erp.accounting.application.AccountingViews;
import com.erp.accounting.application.ReportService;
import com.erp.platform.security.RequiresPermission;
import com.erp.platform.web.ApiPaths;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The financial reports as JSON (API.md §17.8, PRODUCT_SPEC.md §8.10). File exports follow with
 * the document rendering pipeline (ADR-038).
 */
@RestController
@RequestMapping(ApiPaths.V1 + "/companies/{companyId}/reports")
class ReportController {

    private final ReportService reports;

    ReportController(ReportService reports) {
        this.reports = reports;
    }

    @RequiresPermission(AccountingPermissions.REPORT_READ)
    @GetMapping("/trial-balance")
    AccountingReports.TrialBalance trialBalance(
            @PathVariable UUID companyId,
            @RequestParam LocalDate from,
            @RequestParam LocalDate to,
            @RequestParam(required = false) @Nullable UUID branchId,
            @RequestParam(defaultValue = "false") boolean includeZero) {
        return reports.trialBalance(from, to, branchId, includeZero);
    }

    @RequiresPermission(AccountingPermissions.REPORT_READ)
    @GetMapping("/general-ledger")
    AccountingReports.GeneralLedger generalLedger(
            @PathVariable UUID companyId,
            @RequestParam UUID accountId,
            @RequestParam LocalDate from,
            @RequestParam LocalDate to) {
        return reports.generalLedger(accountId, from, to);
    }

    @RequiresPermission(AccountingPermissions.REPORT_READ)
    @GetMapping("/journal")
    AccountingViews.JournalReport journal(
            @PathVariable UUID companyId,
            @RequestParam LocalDate from,
            @RequestParam LocalDate to,
            @RequestParam(required = false) @Nullable UUID journalId) {
        return reports.journal(from, to, journalId);
    }

    @RequiresPermission(AccountingPermissions.REPORT_READ)
    @GetMapping("/profit-and-loss")
    AccountingReports.ProfitAndLoss profitAndLoss(
            @PathVariable UUID companyId,
            @RequestParam LocalDate from,
            @RequestParam LocalDate to,
            @RequestParam(required = false) @Nullable LocalDate compareFrom,
            @RequestParam(required = false) @Nullable LocalDate compareTo,
            @RequestParam(required = false) @Nullable UUID branchId) {
        return reports.profitAndLoss(from, to, compareFrom, compareTo, branchId);
    }

    @RequiresPermission(AccountingPermissions.REPORT_READ)
    @GetMapping("/balance-sheet")
    AccountingReports.BalanceSheet balanceSheet(@PathVariable UUID companyId, @RequestParam LocalDate asOf) {
        return reports.balanceSheet(asOf);
    }

    @RequiresPermission({AccountingPermissions.REPORT_READ, AccountingPermissions.AR_READ})
    @GetMapping("/ar-ageing")
    AccountingReports.Ageing arAgeing(@PathVariable UUID companyId, @RequestParam LocalDate asOf) {
        return reports.ageing("RECEIVABLE", asOf);
    }

    @RequiresPermission({AccountingPermissions.REPORT_READ, AccountingPermissions.AP_READ})
    @GetMapping("/ap-ageing")
    AccountingReports.Ageing apAgeing(@PathVariable UUID companyId, @RequestParam LocalDate asOf) {
        return reports.ageing("PAYABLE", asOf);
    }

    @RequiresPermission(AccountingPermissions.REPORT_READ)
    @GetMapping("/partner-statement")
    AccountingReports.PartnerStatement partnerStatement(
            @PathVariable UUID companyId,
            @RequestParam UUID partnerId,
            @RequestParam(defaultValue = "RECEIVABLE") String kind,
            @RequestParam LocalDate from,
            @RequestParam LocalDate to) {
        return reports.partnerStatement(partnerId, kind, from, to);
    }

    @RequiresPermission(AccountingPermissions.REPORT_READ)
    @GetMapping("/tax-summary")
    AccountingReports.TaxSummary taxSummary(
            @PathVariable UUID companyId, @RequestParam LocalDate from, @RequestParam LocalDate to) {
        return reports.taxSummary(from, to);
    }

    @RequiresPermission(AccountingPermissions.REPORT_READ)
    @GetMapping("/cash-book")
    AccountingReports.CashBook cashBook(
            @PathVariable UUID companyId,
            @RequestParam UUID bankAccountId,
            @RequestParam LocalDate from,
            @RequestParam LocalDate to) {
        return reports.cashBook(bankAccountId, from, to);
    }
}
