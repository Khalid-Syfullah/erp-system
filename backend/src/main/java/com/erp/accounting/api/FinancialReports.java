package com.erp.accounting.api;

import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The financial reports of the current company, computed by Accounting from the posted general
 * ledger and the open items (the accounting source of truth). Reporting exports them; it never
 * recomputes them (ADR-040). Callers check the reports' permissions; invalid ranges and unknown
 * accounts fail as the corresponding Accounting endpoints do.
 */
public interface FinancialReports {

    /** {@code kind} of {@link #ageing}. */
    String RECEIVABLE = "RECEIVABLE";

    String PAYABLE = "PAYABLE";

    AccountingReports.TrialBalance trialBalance(
            LocalDate from, LocalDate to, @Nullable UUID branchId, boolean includeZero);

    AccountingReports.GeneralLedger generalLedger(UUID accountId, LocalDate from, LocalDate to);

    AccountingReports.ProfitAndLoss profitAndLoss(
            LocalDate from,
            LocalDate to,
            @Nullable LocalDate compareFrom,
            @Nullable LocalDate compareTo,
            @Nullable UUID branchId);

    AccountingReports.BalanceSheet balanceSheet(LocalDate asOf);

    AccountingReports.Ageing ageing(String kind, LocalDate asOf);

    AccountingReports.CashBook cashBook(UUID bankAccountId, LocalDate from, LocalDate to);
}
