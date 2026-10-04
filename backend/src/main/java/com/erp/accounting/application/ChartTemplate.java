package com.erp.accounting.application;

import com.erp.accounting.domain.MappingKey;
import java.util.List;
import java.util.Map;

/**
 * The {@code STANDARD_SME} chart of accounts, default mappings and journals (PRODUCT_SPEC.md §8.2).
 * Control accounts take system postings only; every seeded account is a system account the posting
 * rules rely on, so it cannot be deactivated.
 */
final class ChartTemplate {

    record AccountRow(String code, String name, String type, String subtype, boolean control) {}

    record JournalRow(String code, String name, String type) {}

    static final String RETAINED_EARNINGS = "3100";

    static final List<AccountRow> ACCOUNTS = List.of(
            new AccountRow("1000", "Cash on hand", "ASSET", "CASH", false),
            new AccountRow("1010", "Bank – main", "ASSET", "BANK", false),
            new AccountRow("1100", "Accounts receivable", "ASSET", "RECEIVABLE", true),
            new AccountRow("1150", "Supplier advances", "ASSET", "PREPAYMENT", false),
            new AccountRow("1200", "Inventory", "ASSET", "INVENTORY", true),
            new AccountRow("1300", "Input tax recoverable", "ASSET", "TAX_RECEIVABLE", true),
            new AccountRow("1500", "Fixed assets", "ASSET", "FIXED_ASSET", false),
            new AccountRow("1590", "Accumulated depreciation", "ASSET", "ACCUMULATED_DEPRECIATION", false),
            new AccountRow("2000", "Accounts payable", "LIABILITY", "PAYABLE", true),
            new AccountRow("2050", "Goods received not invoiced", "LIABILITY", "GRNI", true),
            new AccountRow("2100", "Output tax payable", "LIABILITY", "TAX_PAYABLE", true),
            new AccountRow("2150", "Customer advances", "LIABILITY", "CUSTOMER_ADVANCE", false),
            new AccountRow("2200", "Salaries payable", "LIABILITY", "PAYROLL_LIABILITY", false),
            new AccountRow("2210", "Payroll deductions payable", "LIABILITY", "PAYROLL_LIABILITY", false),
            new AccountRow("2220", "Employer contributions payable", "LIABILITY", "PAYROLL_LIABILITY", false),
            new AccountRow("2300", "Accrued liabilities", "LIABILITY", "ACCRUED_LIABILITY", false),
            new AccountRow("3000", "Share capital", "EQUITY", "EQUITY", false),
            new AccountRow("3100", "Retained earnings", "EQUITY", "RETAINED_EARNINGS", false),
            new AccountRow("3900", "Opening balance equity", "EQUITY", "OPENING_BALANCE_EQUITY", false),
            new AccountRow("4000", "Sales revenue", "REVENUE", "OPERATING_REVENUE", false),
            new AccountRow("4100", "Sales returns and allowances", "REVENUE", "OPERATING_REVENUE", false),
            new AccountRow("4900", "Other income", "REVENUE", "OTHER_INCOME", false),
            new AccountRow("5000", "Cost of goods sold", "EXPENSE", "COST_OF_GOODS_SOLD", false),
            new AccountRow("5100", "Inventory adjustments", "EXPENSE", "COST_OF_GOODS_SOLD", false),
            new AccountRow("5200", "Purchase price variance", "EXPENSE", "COST_OF_GOODS_SOLD", false),
            new AccountRow("6000", "Operating expenses (general)", "EXPENSE", "OPERATING_EXPENSE", false),
            new AccountRow("6100", "Salaries and wages", "EXPENSE", "PAYROLL_EXPENSE", false),
            new AccountRow("6150", "Employer contributions", "EXPENSE", "PAYROLL_EXPENSE", false),
            new AccountRow("6900", "Depreciation", "EXPENSE", "DEPRECIATION", false),
            new AccountRow("7000", "FX gains and losses", "EXPENSE", "FX_GAIN_LOSS", false),
            new AccountRow("7100", "Rounding differences", "EXPENSE", "OTHER_EXPENSE", false));

    static final Map<MappingKey, String> DEFAULT_MAPPINGS = Map.ofEntries(
            Map.entry(MappingKey.AR_CONTROL, "1100"),
            Map.entry(MappingKey.AP_CONTROL, "2000"),
            Map.entry(MappingKey.INVENTORY_ASSET, "1200"),
            Map.entry(MappingKey.GRNI, "2050"),
            Map.entry(MappingKey.COGS, "5000"),
            Map.entry(MappingKey.SALES_REVENUE, "4000"),
            Map.entry(MappingKey.SALES_RETURNS, "4100"),
            Map.entry(MappingKey.PURCHASE_EXPENSE, "6000"),
            Map.entry(MappingKey.PURCHASE_PRICE_VARIANCE, "5200"),
            Map.entry(MappingKey.INVENTORY_ADJUSTMENT, "5100"),
            Map.entry(MappingKey.INVENTORY_OPENING, "3900"),
            Map.entry(MappingKey.TAX_OUTPUT, "2100"),
            Map.entry(MappingKey.TAX_INPUT, "1300"),
            Map.entry(MappingKey.FX_REALIZED_GAIN, "7000"),
            Map.entry(MappingKey.FX_REALIZED_LOSS, "7000"),
            Map.entry(MappingKey.ROUNDING_DIFFERENCE, "7100"),
            Map.entry(MappingKey.CUSTOMER_ADVANCE, "2150"),
            Map.entry(MappingKey.SUPPLIER_ADVANCE, "1150"),
            Map.entry(MappingKey.SALARY_EXPENSE, "6100"),
            Map.entry(MappingKey.PAYROLL_DEDUCTION_LIABILITY, "2210"),
            Map.entry(MappingKey.EMPLOYER_CONTRIBUTION_EXPENSE, "6150"),
            Map.entry(MappingKey.EMPLOYER_CONTRIBUTION_LIABILITY, "2220"),
            Map.entry(MappingKey.SALARIES_PAYABLE, "2200"));

    /** The journals entries are numbered in (PRODUCT_SPEC.md §8.1). */
    static final List<JournalRow> JOURNALS = List.of(
            new JournalRow(Journals.GENERAL, "General", "GENERAL"),
            new JournalRow(Journals.SALES, "Sales", "SALES"),
            new JournalRow(Journals.PURCHASES, "Purchases", "PURCHASE"),
            new JournalRow(Journals.BANK, "Bank", "BANK"),
            new JournalRow(Journals.CASH, "Cash", "CASH"),
            new JournalRow(Journals.INVENTORY, "Inventory", "INVENTORY"),
            new JournalRow(Journals.PAYROLL, "Payroll", "PAYROLL"),
            new JournalRow(Journals.CLOSING, "Closing", "CLOSING"),
            new JournalRow(Journals.OPENING, "Opening", "OPENING"));

    /** Codes of the system journals. */
    static final class Journals {
        static final String GENERAL = "GEN";
        static final String SALES = "SAL";
        static final String PURCHASES = "PUR";
        static final String BANK = "BNK";
        static final String CASH = "CSH";
        static final String INVENTORY = "INV";
        static final String PAYROLL = "PAY";
        static final String CLOSING = "CLS";
        static final String OPENING = "OPN";

        private Journals() {}
    }

    private ChartTemplate() {}
}
