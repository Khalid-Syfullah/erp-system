package com.erp.procurement.events;

import com.erp.platform.events.DomainEvent;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * {@code procurement.purchase_order.approved} (ARCHITECTURE.md §7), schema version 1: published in
 * the approving transaction. Consumers (emailing the order to the supplier) are asynchronous and
 * follow later.
 */
public record PurchaseOrderApproved(
        EventMetadata metadata,
        UUID purchaseOrderId,
        String number,
        UUID supplierId,
        String currencyCode,
        BigDecimal subtotal,
        BigDecimal taxTotal,
        BigDecimal total)
        implements DomainEvent {

    public static final String TYPE = "procurement.purchase_order.approved";
    public static final int SCHEMA_VERSION = 1;
}
