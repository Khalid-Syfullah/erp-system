package com.erp.accounting.application;

import com.erp.accounting.persistence.OpenItemRepository;
import com.erp.sales.api.CustomerCreditExposurePort;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link CustomerCreditExposurePort} (ARCHITECTURE.md §5.2, SAL-2): a customer's open receivables in
 * base currency, Σ of its AR open items (on-account payments and credit notes count negative).
 */
@Component
class CreditExposureAdapter implements CustomerCreditExposurePort {

    private final OpenItemRepository openItems;

    CreditExposureAdapter(OpenItemRepository openItems) {
        this.openItems = openItems;
    }

    @Override
    @Transactional(readOnly = true)
    public BigDecimal openReceivablesBase(UUID companyId, UUID customerId) {
        return openItems.openBase(companyId, "RECEIVABLE", customerId);
    }
}
