package com.erp.reporting.application;

import com.erp.accounting.api.AccountingReports;
import com.erp.accounting.api.FinancialReports;
import com.erp.reporting.domain.ReportDefinition;
import com.erp.reporting.domain.ReportParameters;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The financial statements as report rows (in the definition's column order), computed by
 * Accounting ({@link FinancialReports}) from the posted general ledger: Reporting only lays them
 * out, it never recomputes a balance (ADR-040). The statements are bounded by the chart of accounts,
 * the partners or one account's lines in a period.
 */
@Component
public class FinancialReportRows {

    private final FinancialReports reports;

    FinancialReportRows(FinancialReports reports) {
        this.reports = reports;
    }

    public List<@Nullable Object[]> rows(ReportDefinition definition, ReportParameters p) {
        return switch (definition.code()) {
            case "trial-balance" -> trialBalance(p);
            case "general-ledger" -> generalLedger(p);
            case "profit-and-loss" -> profitAndLoss(p);
            case "balance-sheet" -> balanceSheet(p);
            case "ar-ageing" -> ageing(FinancialReports.RECEIVABLE, p);
            case "ap-ageing" -> ageing(FinancialReports.PAYABLE, p);
            case "cash-book" -> cashBook(p);
            default -> throw new IllegalStateException("Not a financial statement: " + definition.code());
        };
    }

    private List<@Nullable Object[]> trialBalance(ReportParameters p) {
        AccountingReports.TrialBalance tb = reports.trialBalance(
                p.requireDate(ReportCatalog.FROM),
                p.requireDate(ReportCatalog.TO),
                p.id("branchId"),
                p.flag("includeZero"));
        List<@Nullable Object[]> rows = new ArrayList<>();
        for (AccountingReports.TrialBalanceRow r : tb.rows()) {
            rows.add(new Object[] {
                r.account().code(),
                r.account().name(),
                r.account().accountType(),
                r.opening(),
                r.debit(),
                r.credit(),
                r.closing()
            });
        }
        return rows;
    }

    private List<@Nullable Object[]> generalLedger(ReportParameters p) {
        AccountingReports.GeneralLedger gl = reports.generalLedger(
                java.util.Objects.requireNonNull(p.id("accountId")),
                p.requireDate(ReportCatalog.FROM),
                p.requireDate(ReportCatalog.TO));
        List<@Nullable Object[]> rows = new ArrayList<>();
        rows.add(new Object[] {
            gl.from(), null, "Opening balance", null, null, BigDecimal.ZERO, BigDecimal.ZERO, gl.opening()
        });
        for (AccountingReports.LedgerRow r : gl.rows()) {
            rows.add(new Object[] {
                r.entryDate(),
                r.entryNumber(),
                r.description(),
                r.sourceType(),
                r.sourceNumber(),
                r.debit(),
                r.credit(),
                r.balance()
            });
        }
        rows.add(new Object[] {
            gl.to(), null, "Closing balance", null, null, BigDecimal.ZERO, BigDecimal.ZERO, gl.closing()
        });
        return rows;
    }

    private List<@Nullable Object[]> profitAndLoss(ReportParameters p) {
        AccountingReports.ProfitAndLoss pl = reports.profitAndLoss(
                p.requireDate(ReportCatalog.FROM),
                p.requireDate(ReportCatalog.TO),
                p.date("compareFrom"),
                p.date("compareTo"),
                p.id("branchId"));
        List<@Nullable Object[]> rows = new ArrayList<>();
        statement(rows, "REVENUE", pl.revenue());
        rows.add(new Object[] {"REVENUE", null, "Total revenue", pl.totalRevenue(), null});
        statement(rows, "EXPENSE", pl.expenses());
        rows.add(new Object[] {"EXPENSE", null, "Total expenses", pl.totalExpenses(), null});
        rows.add(new Object[] {"NET", null, "Net profit", pl.netProfit(), pl.comparisonNetProfit()});
        return rows;
    }

    private List<@Nullable Object[]> balanceSheet(ReportParameters p) {
        AccountingReports.BalanceSheet bs = reports.balanceSheet(p.requireDate(ReportCatalog.AS_OF));
        List<@Nullable Object[]> rows = new ArrayList<>();
        balances(rows, "ASSET", bs.assets());
        rows.add(new Object[] {"ASSET", null, "Total assets", bs.totalAssets()});
        balances(rows, "LIABILITY", bs.liabilities());
        rows.add(new Object[] {"LIABILITY", null, "Total liabilities", bs.totalLiabilities()});
        balances(rows, "EQUITY", bs.equity());
        rows.add(new Object[] {"EQUITY", null, "Current year earnings", bs.currentEarnings()});
        rows.add(new Object[] {"EQUITY", null, "Total equity", bs.totalEquity()});
        return rows;
    }

    private static void statement(
            List<@Nullable Object[]> rows, String section, List<AccountingReports.StatementLine> lines) {
        for (AccountingReports.StatementLine line : lines) {
            rows.add(new Object[] {
                section, line.account().code(), line.account().name(), line.amount(), line.comparison()
            });
        }
    }

    private static void balances(
            List<@Nullable Object[]> rows, String section, List<AccountingReports.StatementLine> lines) {
        for (AccountingReports.StatementLine line : lines) {
            rows.add(
                    new Object[] {section, line.account().code(), line.account().name(), line.amount()});
        }
    }

    private List<@Nullable Object[]> ageing(String kind, ReportParameters p) {
        AccountingReports.Ageing ageing = reports.ageing(kind, p.requireDate(ReportCatalog.AS_OF));
        List<@Nullable Object[]> rows = new ArrayList<>();
        for (AccountingReports.AgeingRow r : ageing.partners()) {
            AccountingReports.AgeingBuckets b = r.buckets();
            rows.add(new Object[] {
                r.partnerCode(),
                r.partnerName(),
                b.current(),
                b.days1To30(),
                b.days31To60(),
                b.days61To90(),
                b.over90(),
                b.total()
            });
        }
        return rows;
    }

    private List<@Nullable Object[]> cashBook(ReportParameters p) {
        AccountingReports.CashBook book = reports.cashBook(
                java.util.Objects.requireNonNull(p.id("bankAccountId")),
                p.requireDate(ReportCatalog.FROM),
                p.requireDate(ReportCatalog.TO));
        List<@Nullable Object[]> rows = new ArrayList<>();
        rows.add(new Object[] {
            book.from(),
            null,
            "Opening balance",
            null,
            null,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            null,
            book.opening(),
            null
        });
        for (AccountingReports.BankTransaction t : book.transactions()) {
            rows.add(new Object[] {
                t.entryDate(),
                t.entryNumber(),
                t.description(),
                t.sourceType(),
                t.sourceNumber(),
                t.debit(),
                t.credit(),
                t.amountCurrency(),
                t.runningBalance(),
                t.statementReference()
            });
        }
        rows.add(new Object[] {
            book.to(), null, "Closing balance", null, null, BigDecimal.ZERO, BigDecimal.ZERO, null, book.closing(), null
        });
        return rows;
    }
}
