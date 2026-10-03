package com.erp.sales.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Port (ARCHITECTURE.md §5.2, C-12) through which the credit check (SAL-2) learns a customer's open
 * receivables: Accounting owns the AR open items and implements it (Phase 8). Until then the
 * documented default answers zero; the open orders part of the exposure is computed by Sales.
 */
public interface CustomerCreditExposurePort {

    /** Open AR of the customer in the company's base currency (positive: the customer owes). */
    BigDecimal openReceivablesBase(UUID companyId, UUID customerId);
}
