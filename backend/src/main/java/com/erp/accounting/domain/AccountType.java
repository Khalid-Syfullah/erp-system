package com.erp.accounting.domain;

import java.util.Map;
import java.util.Set;

/**
 * Account types and their subtypes (PRODUCT_SPEC.md §8.1, DATABASE.md §5.8). The type decides the
 * normal balance and the statement: ASSET and EXPENSE are debit-normal, LIABILITY, EQUITY and
 * REVENUE credit-normal; REVENUE and EXPENSE are closed into retained earnings at year end.
 */
public enum AccountType {
    ASSET,
    LIABILITY,
    EQUITY,
    REVENUE,
    EXPENSE;

    private static final Map<AccountType, Set<String>> SUBTYPES = Map.of(
            ASSET,
            Set.of(
                    "CASH",
                    "BANK",
                    "RECEIVABLE",
                    "INVENTORY",
                    "PREPAYMENT",
                    "FIXED_ASSET",
                    "ACCUMULATED_DEPRECIATION",
                    "TAX_RECEIVABLE",
                    "OTHER_CURRENT_ASSET",
                    "OTHER_ASSET"),
            LIABILITY,
            Set.of(
                    "PAYABLE",
                    "GRNI",
                    "TAX_PAYABLE",
                    "PAYROLL_LIABILITY",
                    "ACCRUED_LIABILITY",
                    "CUSTOMER_ADVANCE",
                    "OTHER_CURRENT_LIABILITY",
                    "LONG_TERM_LIABILITY"),
            EQUITY,
            Set.of("EQUITY", "RETAINED_EARNINGS", "OPENING_BALANCE_EQUITY"),
            REVENUE,
            Set.of("OPERATING_REVENUE", "OTHER_INCOME"),
            EXPENSE,
            Set.of(
                    "COST_OF_GOODS_SOLD",
                    "OPERATING_EXPENSE",
                    "PAYROLL_EXPENSE",
                    "DEPRECIATION",
                    "FX_GAIN_LOSS",
                    "OTHER_EXPENSE"));

    /** Subtypes of system postings that make an account a control account (no manual lines). */
    public static final Set<String> CONTROL_SUBTYPES =
            Set.of("RECEIVABLE", "PAYABLE", "INVENTORY", "GRNI", "TAX_RECEIVABLE", "TAX_PAYABLE");

    /** Subtypes that can back a bank account (payments and expenses move money through them). */
    public static final Set<String> MONEY_SUBTYPES = Set.of("BANK", "CASH");

    public boolean allows(String subtype) {
        return SUBTYPES.get(this).contains(subtype);
    }

    public Set<String> subtypes() {
        return SUBTYPES.get(this);
    }

    /** Whether a debit increases the balance (ASSET, EXPENSE). */
    public boolean debitNormal() {
        return this == ASSET || this == EXPENSE;
    }

    /** Whether the account belongs to the balance sheet (else to profit and loss). */
    public boolean balanceSheet() {
        return this == ASSET || this == LIABILITY || this == EQUITY;
    }
}
