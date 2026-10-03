package com.erp.procurement.application;

import com.erp.platform.numbering.DocumentType;
import com.erp.procurement.api.BillSettlementPort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Document number types of the Procurement module (G-6; separate sequences per type, ADR-021). */
@Configuration(proxyBeanMethods = false)
class ProcurementConfiguration {

    static final String REQUISITION = "PURCHASE_REQUISITION";
    static final String PURCHASE_ORDER = "PURCHASE_ORDER";
    static final String GOODS_RECEIPT = "GOODS_RECEIPT";
    static final String PURCHASE_RETURN = "PURCHASE_RETURN";
    static final String SUPPLIER_BILL = "SUPPLIER_BILL";
    static final String DEBIT_NOTE = "DEBIT_NOTE";

    @Bean
    DocumentType requisitionDocumentType() {
        return new DocumentType(REQUISITION, "PR-{FY}-", 6);
    }

    @Bean
    DocumentType purchaseOrderDocumentType() {
        return new DocumentType(PURCHASE_ORDER, "PO-{FY}-", 6);
    }

    @Bean
    DocumentType goodsReceiptDocumentType() {
        return new DocumentType(GOODS_RECEIPT, "GRN-{FY}-", 6);
    }

    @Bean
    DocumentType purchaseReturnDocumentType() {
        return new DocumentType(PURCHASE_RETURN, "PRT-{FY}-", 6);
    }

    @Bean
    DocumentType supplierBillDocumentType() {
        return new DocumentType(SUPPLIER_BILL, "BILL-{FY}-", 6);
    }

    @Bean
    DocumentType debitNoteDocumentType() {
        return new DocumentType(DEBIT_NOTE, "DN-{FY}-", 6);
    }

    /**
     * The settlement port: Accounting's implementation once it exists (Phase 8), otherwise the
     * documented default {@code UNKNOWN} (ARCHITECTURE.md §5.2).
     */
    @Bean
    BillSettlements billSettlements(ObjectProvider<BillSettlementPort> port) {
        return new BillSettlements(port);
    }

    static final class BillSettlements {

        private final ObjectProvider<BillSettlementPort> port;

        BillSettlements(ObjectProvider<BillSettlementPort> port) {
            this.port = port;
        }

        BillSettlementPort.Settlement settlement(java.util.UUID companyId, java.util.UUID billId) {
            BillSettlementPort implementation = port.getIfAvailable();
            return implementation != null
                    ? implementation.settlement(companyId, billId)
                    : new BillSettlementPort.Settlement(billId, BillSettlementPort.Status.UNKNOWN, null);
        }
    }
}
