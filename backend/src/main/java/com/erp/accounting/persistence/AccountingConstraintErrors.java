package com.erp.accounting.persistence;

import com.erp.accounting.application.AccountingErrorCode;
import com.erp.platform.web.ConstraintErrorMapping;
import com.erp.platform.web.ErrorCode;
import com.erp.platform.web.PlatformErrorCode;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Error codes for the Accounting module's constraints. The triggers back up the services' own
 * checks (ACC-1 to ACC-5): hitting one means a check was bypassed or two writers raced.
 */
@Component
class AccountingConstraintErrors implements ConstraintErrorMapping {

    @Override
    public Map<String, ErrorCode> constraintErrors() {
        return Map.ofEntries(
                Map.entry("uq_accounts__company_id_code", PlatformErrorCode.DUPLICATE_CODE),
                Map.entry("uq_journals__company_id_code", PlatformErrorCode.DUPLICATE_CODE),
                Map.entry("uq_bank_accounts__company_id_name", PlatformErrorCode.DUPLICATE_CODE),
                Map.entry("uq_bank_accounts__account_id", PlatformErrorCode.CONFLICT),
                Map.entry("uq_fiscal_years__company_id_code", PlatformErrorCode.CONFLICT),
                Map.entry("ex_fiscal_years__no_overlap", PlatformErrorCode.CONFLICT),
                Map.entry("ex_periods__no_overlap", PlatformErrorCode.CONFLICT),
                Map.entry("ck_accounts__no_cycle", PlatformErrorCode.VALIDATION_FAILED),
                Map.entry("uq_journal_entries__company_id_source_event_id", PlatformErrorCode.INVALID_STATE),
                Map.entry("uq_journal_entries__system_source", PlatformErrorCode.INVALID_STATE),
                Map.entry("uq_journal_entries__reversal_of_id", PlatformErrorCode.INVALID_STATE),
                Map.entry("uq_journal_entries__reversed_by_id", PlatformErrorCode.INVALID_STATE),
                Map.entry("uq_open_items__source", PlatformErrorCode.INVALID_STATE),
                Map.entry("ck_journal_balanced", AccountingErrorCode.UNBALANCED_ENTRY),
                Map.entry("ck_journal_entries__balanced", AccountingErrorCode.UNBALANCED_ENTRY),
                Map.entry("ck_journal_lines__one_side", AccountingErrorCode.UNBALANCED_ENTRY),
                Map.entry("ck_journal_entries__period_open", AccountingErrorCode.PERIOD_CLOSED),
                Map.entry("ck_journal_lines__account_postable", AccountingErrorCode.ACCOUNT_NOT_POSTABLE),
                Map.entry("ck_journal_lines__account_currency", AccountingErrorCode.ACCOUNT_NOT_POSTABLE),
                Map.entry("ck_journal_lines__control_account", AccountingErrorCode.CONTROL_ACCOUNT_MANUAL_POSTING),
                Map.entry("ck_open_items__amounts", AccountingErrorCode.ALLOCATION_INVALID),
                Map.entry("ck_journal_entries__immutable", PlatformErrorCode.INVALID_STATE),
                Map.entry("ck_journal_lines__immutable", PlatformErrorCode.INVALID_STATE),
                Map.entry("ck_open_items__immutable", PlatformErrorCode.INVALID_STATE),
                Map.entry("ck_payment_allocations__immutable", PlatformErrorCode.INVALID_STATE),
                Map.entry("ck_payments__frozen", PlatformErrorCode.INVALID_STATE),
                Map.entry("ck_expenses__frozen", PlatformErrorCode.INVALID_STATE),
                Map.entry("ck_expense_lines__frozen", PlatformErrorCode.INVALID_STATE));
    }
}
