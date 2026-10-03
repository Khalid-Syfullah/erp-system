package com.erp.procurement.events;

import com.erp.platform.events.DomainEvent;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * {@code procurement.supplier_bill.posted} and {@code procurement.debit_note.posted}
 * (ARCHITECTURE.md §7), schema version 1: published in the posting transaction. Accounting (Phase 8)
 * books the AP entry and open item from it synchronously (PRODUCT_SPEC.md §8.6): GRNI at the receipt
 * value for STOCK_RECEIVED lines with the difference to PURCHASE_PRICE_VARIANCE, PURCHASE_EXPENSE
 * for SERVICE and EXPENSE lines, TAX_INPUT per tax line, AP_CONTROL for the total. Amounts are
 * positive; a debit note posts the mirror image ({@code metadata.eventType} tells which).
 */
public record SupplierBillPosted(
        EventMetadata metadata,
        UUID billId,
        String documentType,
        String number,
        String supplierInvoiceNumber,
        UUID supplierId,
        @Nullable UUID supplierGroupId,
        @Nullable UUID originalBillId,
        @Nullable UUID purchaseOrderId,
        LocalDate documentDate,
        LocalDate accountingDate,
        LocalDate dueDate,
        String currencyCode,
        BigDecimal exchangeRate,
        Totals totals,
        List<Line> lines,
        List<TaxLine> taxLines)
        implements DomainEvent {

    public static final String BILL_TYPE = "procurement.supplier_bill.posted";
    public static final String DEBIT_NOTE_TYPE = "procurement.debit_note.posted";
    public static final int SCHEMA_VERSION = 1;

    public record Totals(
            BigDecimal subtotal,
            BigDecimal taxTotal,
            BigDecimal total,
            BigDecimal subtotalBase,
            BigDecimal taxTotalBase,
            BigDecimal totalBase) {}

    /**
     * @param type STOCK_RECEIVED, SERVICE or EXPENSE
     * @param receiptValueBase STOCK_RECEIVED: the receipt value invoiced (GRNI clearing)
     */
    public record Line(
            UUID lineId,
            String type,
            UUID variantId,
            UUID categoryId,
            @Nullable UUID purchaseOrderLineId,
            @Nullable UUID goodsReceiptLineId,
            BigDecimal quantityBase,
            BigDecimal netDoc,
            BigDecimal netBase,
            @Nullable BigDecimal receiptValueBase,
            @Nullable UUID taxCodeId,
            @Nullable UUID branchId,
            @Nullable UUID departmentId) {}

    public record TaxLine(
            UUID taxCodeId,
            BigDecimal ratePercent,
            BigDecimal taxableDoc,
            BigDecimal taxDoc,
            BigDecimal taxableBase,
            BigDecimal taxBase) {}
}
