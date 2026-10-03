package com.erp.sales.events;

import com.erp.platform.events.DomainEvent;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * {@code sales.order.confirmed} (ARCHITECTURE.md §7), schema version 1: published in the confirming
 * transaction, with the totals in document and base currency (at the confirmation rate).
 */
public record SalesOrderConfirmed(
        EventMetadata metadata,
        UUID salesOrderId,
        String number,
        UUID customerId,
        String currencyCode,
        BigDecimal total,
        BigDecimal totalBase,
        String creditCheckResult)
        implements DomainEvent {

    public static final String TYPE = "sales.order.confirmed";
    public static final int SCHEMA_VERSION = 1;
}
