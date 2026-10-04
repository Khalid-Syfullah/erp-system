package com.erp.accounting;

/** Permission codes of the Accounting module (SECURITY.md §4.2). */
public final class AccountingPermissions {

    public static final String ACCOUNT_READ = "accounting.account.read";
    public static final String ACCOUNT_MANAGE = "accounting.account.manage";
    public static final String ACCOUNT_MAPPING_MANAGE = "accounting.account_mapping.manage";
    public static final String FISCAL_YEAR_MANAGE = "accounting.fiscal_year.manage";
    public static final String FISCAL_YEAR_CLOSE = "accounting.fiscal_year.close";
    public static final String PERIOD_READ = "accounting.period.read";
    public static final String PERIOD_SOFT_CLOSE = "accounting.period.soft_close";
    public static final String PERIOD_CLOSE = "accounting.period.close";
    public static final String PERIOD_REOPEN = "accounting.period.reopen";
    public static final String PERIOD_POST_SOFT_CLOSED = "accounting.period.post_soft_closed";
    public static final String JOURNAL_MANAGE = "accounting.journal.manage";
    public static final String JOURNAL_ENTRY_READ = "accounting.journal_entry.read";
    public static final String JOURNAL_ENTRY_CREATE = "accounting.journal_entry.create";
    public static final String JOURNAL_ENTRY_POST = "accounting.journal_entry.post";
    public static final String JOURNAL_ENTRY_REVERSE = "accounting.journal_entry.reverse";
    public static final String AR_READ = "accounting.ar.read";
    public static final String AP_READ = "accounting.ap.read";
    public static final String BANK_ACCOUNT_READ = "accounting.bank_account.read";
    public static final String BANK_ACCOUNT_MANAGE = "accounting.bank_account.manage";
    public static final String PAYMENT_READ = "accounting.payment.read";
    public static final String PAYMENT_CREATE = "accounting.payment.create";
    public static final String PAYMENT_POST = "accounting.payment.post";
    public static final String PAYMENT_VOID = "accounting.payment.void";
    public static final String PAYMENT_ALLOCATE = "accounting.payment.allocate";
    public static final String PAYMENT_UNALLOCATE = "accounting.payment.unallocate";
    public static final String EXPENSE_READ = "accounting.expense.read";
    public static final String EXPENSE_CREATE = "accounting.expense.create";
    public static final String EXPENSE_POST = "accounting.expense.post";
    public static final String BANK_RECONCILIATION_MANAGE = "accounting.bank_reconciliation.manage";
    public static final String REPORT_READ = "accounting.report.read";
    public static final String SETTINGS_MANAGE = "accounting.settings.manage";

    private AccountingPermissions() {}
}
