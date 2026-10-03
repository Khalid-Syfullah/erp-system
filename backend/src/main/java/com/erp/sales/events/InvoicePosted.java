package com.erp.sales.events;

import com.erp.platform.events.DomainEvent;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * {@code sales.invoice.posted} and {@code sales.credit_note.posted} (ARCHITECTURE.md §7), schema
 * version 1: published in the posting transaction. Accounting (Phase 8) books the AR entry and open
 * item from it synchronously (PRODUCT_SPEC.md §8.6): AR_CONTROL for the total, SALES_REVENUE per
 * line by category, TAX_OUTPUT per tax line; a credit note posts the mirror image. Amounts are
 * positive; {@code metadata.eventType} tells which document it is. Cost of goods sold is booked
 * from Inventory's {@code stock_movement.posted} at delivery (ADR-015).
 */
public record InvoicePosted(
        EventMetadata metadata,
        UUID invoiceId,
        String documentType,
        String number,
        UUID customerId,
        @Nullable UUID customerGroupId,
        @Nullable UUID salesOrderId,
        @Nullable UUID originalInvoiceId,
        @Nullable UUID salesReturnId,
        LocalDate documentDate,
        LocalDate accountingDate,
        LocalDate dueDate,
        String currencyCode,
        BigDecimal exchangeRate,
        Totals totals,
        List<Line> lines,
        List<TaxLine> taxLines)
        implements DomainEvent {

    public static final String INVOICE_TYPE = "sales.invoice.posted";
    public static final String CREDIT_NOTE_TYPE = "sales.credit_note.posted";
    public static final int SCHEMA_VERSION = 1;

    public record Totals(
            BigDecimal subtotal,
            BigDecimal taxTotal,
            BigDecimal total,
            BigDecimal subtotalBase,
            BigDecimal taxTotalBase,
            BigDecimal totalBase) {}

    public record Line(
            UUID lineId,
            UUID variantId,
            UUID categoryId,
            @Nullable UUID salesOrderLineId,
            BigDecimal quantityBase,
            BigDecimal netDoc,
            BigDecimal netBase,
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
