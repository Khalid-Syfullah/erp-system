package com.erp.accounting.api;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The financial reports (PRODUCT_SPEC.md §8.10), published to Reporting through {@link FinancialReports}. Amounts are in base currency unless a field says
 * otherwise; signed balances are debit − credit. Every report is computed from the posted general
 * ledger (or the open items, for ageing), so it reconciles to the GL. Amounts carry the ledger's
 * scale of 4 decimals.
 */
public final class AccountingReports {

    private static final int SCALE = 4;

    private AccountingReports() {}

    /** The ledger scale; report amounts are sums of ledger amounts, so nothing is ever rounded. */
    static BigDecimal amount(BigDecimal value) {
        return value.setScale(SCALE, RoundingMode.UNNECESSARY);
    }

    static @Nullable BigDecimal optional(@Nullable BigDecimal value) {
        return value == null ? null : amount(value);
    }

    public record AccountRef(UUID id, String code, String name, String accountType, String accountSubtype) {}

    public record TrialBalanceRow(
            AccountRef account, BigDecimal opening, BigDecimal debit, BigDecimal credit, BigDecimal closing) {

        public TrialBalanceRow {
            opening = amount(opening);
            debit = amount(debit);
            credit = amount(credit);
            closing = amount(closing);
        }
    }

    public record TrialBalance(
            LocalDate from,
            LocalDate to,
            @Nullable UUID branchId,
            List<TrialBalanceRow> rows,
            BigDecimal totalDebit,
            BigDecimal totalCredit,
            BigDecimal closingDebit,
            BigDecimal closingCredit) {

        public TrialBalance {
            totalDebit = amount(totalDebit);
            totalCredit = amount(totalCredit);
            closingDebit = amount(closingDebit);
            closingCredit = amount(closingCredit);
        }
    }

    public record LedgerRow(
            UUID journalLineId,
            UUID journalEntryId,
            @Nullable String entryNumber,
            LocalDate entryDate,
            String description,
            @Nullable String sourceModule,
            @Nullable String sourceType,
            @Nullable UUID sourceId,
            @Nullable String sourceNumber,
            @Nullable UUID partnerId,
            BigDecimal debit,
            BigDecimal credit,
            String currencyCode,
            BigDecimal amountCurrency,
            BigDecimal balance) {

        public LedgerRow {
            debit = amount(debit);
            credit = amount(credit);
            balance = amount(balance);
        }
    }

    public record GeneralLedger(
            AccountRef account,
            LocalDate from,
            LocalDate to,
            BigDecimal opening,
            List<LedgerRow> rows,
            BigDecimal closing) {

        public GeneralLedger {
            opening = amount(opening);
            closing = amount(closing);
        }
    }

    public record StatementLine(
            AccountRef account, BigDecimal amount, @Nullable BigDecimal comparison) {

        public StatementLine {
            amount = AccountingReports.amount(amount);
            comparison = optional(comparison);
        }
    }

    public record ProfitAndLoss(
            LocalDate from,
            LocalDate to,
            @Nullable LocalDate compareFrom,
            @Nullable LocalDate compareTo,
            List<StatementLine> revenue,
            List<StatementLine> expenses,
            BigDecimal totalRevenue,
            BigDecimal totalExpenses,
            BigDecimal netProfit,
            @Nullable BigDecimal comparisonNetProfit) {

        public ProfitAndLoss {
            totalRevenue = amount(totalRevenue);
            totalExpenses = amount(totalExpenses);
            netProfit = amount(netProfit);
            comparisonNetProfit = optional(comparisonNetProfit);
        }
    }

    public record BalanceSheet(
            LocalDate asOf,
            List<StatementLine> assets,
            List<StatementLine> liabilities,
            List<StatementLine> equity,
            BigDecimal totalAssets,
            BigDecimal totalLiabilities,
            BigDecimal totalEquity,
            BigDecimal currentEarnings,
            boolean balanced) {

        public BalanceSheet {
            totalAssets = amount(totalAssets);
            totalLiabilities = amount(totalLiabilities);
            totalEquity = amount(totalEquity);
            currentEarnings = amount(currentEarnings);
        }
    }

    public record AgeingBuckets(
            BigDecimal current,
            BigDecimal days1To30,
            BigDecimal days31To60,
            BigDecimal days61To90,
            BigDecimal over90,
            BigDecimal total) {

        public AgeingBuckets {
            current = amount(current);
            days1To30 = amount(days1To30);
            days31To60 = amount(days31To60);
            days61To90 = amount(days61To90);
            over90 = amount(over90);
            total = amount(total);
        }
    }

    public record AgeingRow(
            UUID partnerId,
            @Nullable String partnerCode,
            @Nullable String partnerName,
            AgeingBuckets buckets) {}

    public record Ageing(String kind, LocalDate asOf, List<AgeingRow> partners, AgeingBuckets total) {}

    public record PartnerStatement(
            UUID partnerId,
            @Nullable String partnerCode,
            @Nullable String partnerName,
            String kind,
            LocalDate from,
            LocalDate to,
            BigDecimal opening,
            List<LedgerRow> rows,
            BigDecimal closing) {

        public PartnerStatement {
            opening = amount(opening);
            closing = amount(closing);
        }
    }

    public record TaxRow(
            UUID taxCodeId,
            @Nullable String taxCode,
            @Nullable BigDecimal ratePercent,
            BigDecimal taxableSales,
            BigDecimal outputTax,
            BigDecimal taxablePurchases,
            BigDecimal inputTax,
            BigDecimal netTax) {

        public TaxRow {
            taxableSales = amount(taxableSales);
            outputTax = amount(outputTax);
            taxablePurchases = amount(taxablePurchases);
            inputTax = amount(inputTax);
            netTax = amount(netTax);
        }
    }

    public record TaxSummary(
            LocalDate from,
            LocalDate to,
            List<TaxRow> rows,
            BigDecimal outputTax,
            BigDecimal inputTax,
            BigDecimal netTax) {

        public TaxSummary {
            outputTax = amount(outputTax);
            inputTax = amount(inputTax);
            netTax = amount(netTax);
        }
    }

    public record CashBook(
            BankAccount bankAccount,
            LocalDate from,
            LocalDate to,
            BigDecimal opening,
            List<BankTransaction> transactions,
            BigDecimal closing) {

        public CashBook {
            opening = amount(opening);
            closing = amount(closing);
        }
    }

    public record BankAccount(
            UUID id,
            String name,
            UUID accountId,
            String currencyCode,
            @Nullable String bankName,
            @Nullable String accountNumberLast4,
            boolean hasAccountNumber,
            boolean hasIban,
            boolean active,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            int version) {}

    public record BankTransaction(
            UUID journalLineId,
            UUID journalEntryId,
            @Nullable String entryNumber,
            LocalDate entryDate,
            String description,
            @Nullable String sourceModule,
            @Nullable String sourceType,
            @Nullable UUID sourceId,
            @Nullable String sourceNumber,
            BigDecimal debit,
            BigDecimal credit,
            BigDecimal amountCurrency,
            BigDecimal runningBalance,
            @Nullable String statementReference,
            @Nullable LocalDate statementDate) {

        public boolean reconciled() {
            return statementReference != null;
        }
    }
}
