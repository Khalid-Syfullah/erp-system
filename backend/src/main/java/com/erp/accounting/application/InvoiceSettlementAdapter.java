package com.erp.accounting.application;

import com.erp.accounting.persistence.OpenItemRepository;
import com.erp.sales.api.InvoiceSettlementPort;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** {@link InvoiceSettlementPort}: how much of an invoice or credit note is still open (AR open item). */
@Component
class InvoiceSettlementAdapter implements InvoiceSettlementPort {

    private final OpenItemRepository openItems;

    InvoiceSettlementAdapter(OpenItemRepository openItems) {
        this.openItems = openItems;
    }

    @Override
    @Transactional(readOnly = true)
    public Settlement settlement(UUID companyId, UUID invoiceId) {
        return openItems
                .bySource(companyId, "sales", "INVOICE", invoiceId)
                .or(() -> openItems.bySource(companyId, "sales", "CREDIT_NOTE", invoiceId))
                .map(i -> new Settlement(
                        invoiceId, Status.valueOf(i.status()), i.openAmount().abs()))
                .orElseGet(() -> new Settlement(invoiceId, Status.UNKNOWN, null));
    }
}
