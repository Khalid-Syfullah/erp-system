package com.erp.procurement.events;

import com.erp.platform.events.DomainEvent;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * {@code procurement.goods_receipt.posted} (ARCHITECTURE.md §7), schema version 1: published in the
 * posting transaction. The stock and its valuation are booked from Inventory's
 * {@code stock_movement.posted} (PURCHASE_RECEIPT); this event is informational.
 */
public record GoodsReceiptPosted(
        EventMetadata metadata,
        UUID goodsReceiptId,
        String number,
        UUID purchaseOrderId,
        UUID supplierId,
        UUID stockMovementId,
        LocalDate receiptDate,
        String currencyCode,
        BigDecimal exchangeRate,
        List<Line> lines)
        implements DomainEvent {

    public static final String TYPE = "procurement.goods_receipt.posted";
    public static final int SCHEMA_VERSION = 1;

    /**
     * @param poUnitPrice the order's net unit price per base unit in the order currency
     * @param valueBase the inventory value of the line in base currency
     */
    public record Line(
            UUID goodsReceiptLineId,
            UUID purchaseOrderLineId,
            UUID variantId,
            BigDecimal quantityBase,
            BigDecimal poUnitPrice,
            BigDecimal valueBase) {}
}
