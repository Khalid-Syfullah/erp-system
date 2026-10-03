package com.erp.sales.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Write models of the Sales module (built by the web layer from request DTOs). */
public final class SalesCommands {

    private SalesCommands() {}

    public record PriceList(
            String code,
            String name,
            String currencyCode,
            boolean pricesIncludeTax,
            @Nullable UUID customerGroupId,
            boolean isDefault,
            @Nullable LocalDate validFrom,
            @Nullable LocalDate validTo,
            boolean active) {}

    public record PriceListItem(
            UUID variantId,
            UUID uomId,
            BigDecimal minQuantity,
            BigDecimal unitPrice,
            @Nullable LocalDate validFrom,
            @Nullable LocalDate validTo) {}

    /** {@code POST {c}/pricing/quote}: prices from the list for a customer, nothing stored. */
    public record Quote(
            UUID customerId,
            @Nullable String currencyCode,
            @Nullable LocalDate date,
            @Nullable UUID priceListId,
            List<PricedLine> lines) {}

    /**
     * A priced line of a quotation or order. {@code unitPrice == null}: the price list's price
     * (SAL-1); {@code taxCodeId == null}: the product's, else the customer's default sales tax code.
     */
    public record PricedLine(
            UUID variantId,
            @Nullable String description,
            BigDecimal quantity,
            UUID uomId,
            @Nullable BigDecimal unitPrice,
            BigDecimal discountPercent,
            @Nullable UUID taxCodeId) {}

    public record Quotation(
            UUID customerId,
            UUID warehouseId,
            @Nullable LocalDate quotationDate,
            @Nullable LocalDate validUntil,
            @Nullable String currencyCode,
            @Nullable UUID priceListId,
            @Nullable UUID paymentTermsId,
            @Nullable String notes,
            List<PricedLine> lines) {}

    public record SalesOrder(
            UUID customerId,
            UUID warehouseId,
            @Nullable LocalDate orderDate,
            @Nullable LocalDate requestedDate,
            @Nullable String customerReference,
            @Nullable String currencyCode,
            @Nullable UUID priceListId,
            @Nullable UUID paymentTermsId,
            @Nullable String invoicePolicy,
            @Nullable String notes,
            List<PricedLine> lines) {}

    public record DeliveryLine(
            UUID salesOrderLineId,
            BigDecimal quantity,
            @Nullable UUID uomId,
            @Nullable UUID locationId) {}

    /** {@code lines == null}: everything still open (and reserved first) on the order's stockable lines. */
    public record Delivery(
            UUID salesOrderId,
            @Nullable LocalDate deliveryDate,
            @Nullable String carrier,
            @Nullable String trackingNumber,
            @Nullable String notes,
            @Nullable List<DeliveryLine> lines) {}

    public record ReturnLine(
            UUID deliveryLineId,
            BigDecimal quantity,
            @Nullable UUID uomId) {}

    public record SalesReturn(UUID deliveryId, @Nullable LocalDate returnDate, String reason, List<ReturnLine> lines) {}

    /**
     * An invoice line: against an order line, or — for a direct invoice of services — a product. A
     * credit note line credits an invoice line ({@code originalInvoiceLineId}), with the return line
     * when goods came back.
     */
    public record InvoiceLine(
            @Nullable UUID salesOrderLineId,
            @Nullable UUID originalInvoiceLineId,
            @Nullable UUID salesReturnLineId,
            @Nullable UUID variantId,
            @Nullable String description,
            BigDecimal quantity,
            @Nullable UUID uomId,
            @Nullable BigDecimal unitPrice,
            @Nullable BigDecimal discountPercent,
            @Nullable UUID taxCodeId,
            @Nullable UUID branchId,
            @Nullable UUID departmentId) {}

    public record Invoice(
            String documentType,
            UUID customerId,
            @Nullable LocalDate invoiceDate,
            @Nullable LocalDate accountingDate,
            @Nullable LocalDate dueDate,
            @Nullable UUID salesOrderId,
            @Nullable UUID originalInvoiceId,
            @Nullable UUID salesReturnId,
            @Nullable String notes,
            List<InvoiceLine> lines) {}
}
