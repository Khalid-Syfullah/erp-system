package com.erp.accounting.application;

import com.erp.org.api.OrgFacade;
import com.erp.platform.numbering.DocumentType;
import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.Schedules;
import java.time.LocalTime;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Document number types of the Accounting module (G-6, ADR-021) and the nightly ledger invariant
 * check (ACC-6, ACC-7). Journal entries are numbered per journal through dynamic types
 * ({@code JOURNAL:<code>}, ADR-038).
 */
@Configuration(proxyBeanMethods = false)
class AccountingConfiguration {

    static final String CUSTOMER_RECEIPT = "CUSTOMER_RECEIPT";
    static final String SUPPLIER_PAYMENT = "SUPPLIER_PAYMENT";
    static final String EXPENSE_VOUCHER = "EXPENSE_VOUCHER";

    @Bean
    DocumentType customerReceiptDocumentType() {
        return new DocumentType(CUSTOMER_RECEIPT, "RCT-{FY}-", 6);
    }

    @Bean
    DocumentType supplierPaymentDocumentType() {
        return new DocumentType(SUPPLIER_PAYMENT, "PAY-{FY}-", 6);
    }

    @Bean
    DocumentType expenseVoucherDocumentType() {
        return new DocumentType(EXPENSE_VOUCHER, "EXP-{FY}-", 6);
    }

    @Bean
    RecurringTask<Void> accountingInvariantTask(LedgerInvariantCheck check, OrgFacade org) {
        return Tasks.recurring("accounting-invariants", Schedules.daily(LocalTime.of(2, 45)))
                .execute((instance, context) -> org.allCompanyIds().forEach(check::check));
    }
}
