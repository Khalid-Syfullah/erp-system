package com.erp.procurement.api;

import java.math.BigDecimal;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Port (ARCHITECTURE.md §5.2) through which Procurement learns how much of a posted bill or debit
 * note is still open: Accounting owns the AP open items and implements it (Phase 8). Until then the
 * documented default answers {@code UNKNOWN}.
 */
public interface BillSettlementPort {

    /** Settlement status of an AP open item. */
    enum Status {
        UNKNOWN,
        OPEN,
        PARTIALLY_SETTLED,
        SETTLED
    }

    /** @param openAmount in the document currency; {@code null} when unknown */
    record Settlement(UUID billId, Status status, @Nullable BigDecimal openAmount) {}

    Settlement settlement(UUID companyId, UUID billId);
}
