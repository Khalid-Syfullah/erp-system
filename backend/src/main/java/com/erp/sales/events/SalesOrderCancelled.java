package com.erp.sales.events;

import com.erp.platform.events.DomainEvent;
import java.math.BigDecimal;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** {@code sales.order.cancelled} (ARCHITECTURE.md §7), schema version 1. */
public record SalesOrderCancelled(
        EventMetadata metadata,
        UUID salesOrderId,
        @Nullable String number,
        UUID customerId,
        String currencyCode,
        BigDecimal total,
        @Nullable String reason)
        implements DomainEvent {

    public static final String TYPE = "sales.order.cancelled";
    public static final int SCHEMA_VERSION = 1;
}
