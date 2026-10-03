package com.erp.sales.application;

import com.erp.org.api.OrgFacade;
import com.erp.platform.numbering.DocumentType;
import com.erp.sales.api.CustomerCreditExposurePort;
import com.erp.sales.api.InvoiceSettlementPort;
import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.Schedules;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Document number types of the Sales module (G-6; separate sequences per type, ADR-021), the
 * defaults of the ports Accounting implements in Phase 8 (ARCHITECTURE.md §5.2) and the daily
 * quotation expiry job.
 */
@Configuration(proxyBeanMethods = false)
class SalesConfiguration {

    static final String QUOTATION = "QUOTATION";
    static final String SALES_ORDER = "SALES_ORDER";
    static final String DELIVERY = "DELIVERY";
    static final String SALES_RETURN = "SALES_RETURN";
    static final String INVOICE = "SALES_INVOICE";
    static final String CREDIT_NOTE = "CREDIT_NOTE";

    @Bean
    DocumentType quotationDocumentType() {
        return new DocumentType(QUOTATION, "QT-{FY}-", 6);
    }

    @Bean
    DocumentType salesOrderDocumentType() {
        return new DocumentType(SALES_ORDER, "SO-{FY}-", 6);
    }

    @Bean
    DocumentType deliveryDocumentType() {
        return new DocumentType(DELIVERY, "DL-{FY}-", 6);
    }

    @Bean
    DocumentType salesReturnDocumentType() {
        return new DocumentType(SALES_RETURN, "SR-{FY}-", 6);
    }

    @Bean
    DocumentType salesInvoiceDocumentType() {
        return new DocumentType(INVOICE, "INV-{FY}-", 6);
    }

    @Bean
    DocumentType creditNoteDocumentType() {
        return new DocumentType(CREDIT_NOTE, "CN-{FY}-", 6);
    }

    @Bean
    RecurringTask<Void> quotationExpiryTask(QuotationExpiry expiry, OrgFacade org) {
        return Tasks.recurring("sales-quotation-expiry", Schedules.daily(LocalTime.of(1, 15)))
                .execute((instance, context) -> org.allCompanyIds().forEach(expiry::run));
    }

    /** Accounting's ports once they exist (Phase 8), otherwise the documented defaults. */
    @Bean
    Receivables receivables(
            ObjectProvider<CustomerCreditExposurePort> exposure, ObjectProvider<InvoiceSettlementPort> settlement) {
        return new Receivables(exposure, settlement);
    }

    static final class Receivables {

        private final ObjectProvider<CustomerCreditExposurePort> exposure;
        private final ObjectProvider<InvoiceSettlementPort> settlement;

        Receivables(
                ObjectProvider<CustomerCreditExposurePort> exposure, ObjectProvider<InvoiceSettlementPort> settlement) {
            this.exposure = exposure;
            this.settlement = settlement;
        }

        /** Open AR of the customer (base currency); zero until Accounting implements the port. */
        BigDecimal openReceivablesBase(UUID companyId, UUID customerId) {
            CustomerCreditExposurePort port = exposure.getIfAvailable();
            return port == null ? BigDecimal.ZERO : port.openReceivablesBase(companyId, customerId);
        }

        InvoiceSettlementPort.Settlement settlement(UUID companyId, UUID invoiceId) {
            InvoiceSettlementPort port = settlement.getIfAvailable();
            return port != null
                    ? port.settlement(companyId, invoiceId)
                    : new InvoiceSettlementPort.Settlement(invoiceId, InvoiceSettlementPort.Status.UNKNOWN, null);
        }
    }
}
