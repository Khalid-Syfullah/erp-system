package com.erp.sales.api;

import java.math.BigDecimal;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Port (ARCHITECTURE.md §5.2) through which Sales learns how much of a posted invoice or credit note
 * is still open: Accounting owns the AR open items and customer payments and implements it
 * (Phase 8). Until then the documented default answers {@code UNKNOWN}.
 */
public interface InvoiceSettlementPort {

    /** Settlement status of an AR open item. */
    enum Status {
        UNKNOWN,
        OPEN,
        PARTIALLY_SETTLED,
        SETTLED
    }

    /** @param openAmount in the document currency; {@code null} when unknown */
    record Settlement(
            UUID invoiceId, Status status, @Nullable BigDecimal openAmount) {}

    Settlement settlement(UUID companyId, UUID invoiceId);
}
