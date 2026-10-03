package com.erp.sales.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Read models of the Sales module. */
public final class SalesViews {

    private SalesViews() {}

    public record Settings(
            String defaultInvoicePolicy,
            String creditCheckMode,
            int quotationValidityDays,
            boolean reserveOnConfirm,
            @Nullable BigDecimal discountApprovalThresholdPercent,
            int version) {}

    public record PriceList(
            UUID id,
            String code,
            String name,
            String currencyCode,
            boolean pricesIncludeTax,
            @Nullable UUID customerGroupId,
            boolean isDefault,
            @Nullable LocalDate validFrom,
            @Nullable LocalDate validTo,
            boolean active,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            int version) {

        public boolean validOn(LocalDate date) {
            return active
                    && (validFrom == null || !date.isBefore(validFrom))
                    && (validTo == null || !date.isAfter(validTo));
        }
    }

    public record PriceListItem(
            UUID id,
            UUID priceListId,
            UUID variantId,
            UUID uomId,
            BigDecimal minQuantity,
            BigDecimal unitPrice,
            @Nullable LocalDate validFrom,
            @Nullable LocalDate validTo,
            int version) {}

    /** A priced document line (quotations, orders) as stored. */
    public record Line(
            UUID id,
            int lineNo,
            UUID variantId,
            String description,
            BigDecimal quantity,
            UUID uomId,
            BigDecimal quantityBase,
            BigDecimal unitPrice,
            BigDecimal discountPercent,
            @Nullable UUID taxCodeId,
            BigDecimal netAmount,
            BigDecimal taxAmount,
            BigDecimal totalAmount) {}

    // ---------------------------------------------------------------------------- quotations

    public record Quotation(
            UUID id,
            UUID companyId,
            @Nullable String number,
            UUID customerId,
            UUID branchId,
            UUID warehouseId,
            LocalDate quotationDate,
            LocalDate validUntil,
            String currencyCode,
            @Nullable UUID priceListId,
            boolean pricesIncludeTax,
            @Nullable UUID paymentTermsId,
            String status,
            BigDecimal subtotal,
            BigDecimal taxTotal,
            BigDecimal total,
            @Nullable UUID salesOrderId,
            @Nullable OffsetDateTime sentAt,
            @Nullable String rejectionReason,
            @Nullable String notes,
            @Nullable UUID createdBy,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            int version) {}

    public record QuotationDetail(Quotation quotation, List<Line> lines) {}

    // --------------------------------------------------------------------------- sales orders

    public record SalesOrder(
            UUID id,
            UUID companyId,
            @Nullable String number,
            UUID customerId,
            @Nullable UUID quotationId,
            UUID branchId,
            UUID warehouseId,
            LocalDate orderDate,
            @Nullable LocalDate requestedDate,
            @Nullable String customerReference,
            String currencyCode,
            @Nullable UUID priceListId,
            boolean pricesIncludeTax,
            @Nullable UUID paymentTermsId,
            String invoicePolicy,
            Map<String, Object> shippingAddress,
            Map<String, Object> billingAddress,
            String status,
            String invoiceStatus,
            @Nullable BigDecimal exchangeRate,
            @Nullable String creditCheckResult,
            @Nullable UUID creditOverrideBy,
            @Nullable String creditOverrideReason,
            BigDecimal subtotal,
            BigDecimal taxTotal,
            BigDecimal total,
            @Nullable OffsetDateTime confirmedAt,
            @Nullable UUID confirmedBy,
            @Nullable String cancelReason,
            @Nullable String closeReason,
            @Nullable String notes,
            @Nullable UUID createdBy,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            int version) {}

    public record SalesOrderLine(
            Line line,
            boolean stockable,
            @Nullable UUID reservationId,
            BigDecimal reservedQuantityBase,
            BigDecimal deliveredQuantityBase,
            BigDecimal returnedQuantityBase,
            BigDecimal invoicedQuantityBase) {

        public UUID id() {
            return line.id();
        }

        /** Quantity still to deliver. */
        public BigDecimal openToDeliverBase() {
            return line.quantityBase().subtract(deliveredQuantityBase);
        }
    }

    public record SalesOrderDetail(SalesOrder order, List<SalesOrderLine> lines) {}

    /**
     * The credit check of an order (SAL-2): exposure = open receivables + the uninvoiced part of the
     * customer's other confirmed orders, all in base currency.
     */
    public record CreditCheckResult(
            UUID salesOrderId,
            UUID customerId,
            String mode,
            @Nullable BigDecimal creditLimit,
            boolean onHold,
            BigDecimal openReceivablesBase,
            BigDecimal openOrdersBase,
            BigDecimal orderTotalBase,
            BigDecimal exchangeRate,
            String outcome) {

        public BigDecimal exposureBase() {
            return openReceivablesBase.add(openOrdersBase);
        }
    }

    // ----------------------------------------------------------------------------- deliveries

    public record Delivery(
            UUID id,
            UUID companyId,
            @Nullable String number,
            UUID salesOrderId,
            UUID customerId,
            UUID branchId,
            UUID warehouseId,
            LocalDate deliveryDate,
            String status,
            Map<String, Object> shippingAddress,
            @Nullable String carrier,
            @Nullable String trackingNumber,
            @Nullable UUID stockMovementId,
            @Nullable String notes,
            @Nullable OffsetDateTime postedAt,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            int version) {}

    public record DeliveryLine(
            UUID id,
            UUID deliveryId,
            int lineNo,
            UUID salesOrderLineId,
            UUID variantId,
            @Nullable UUID locationId,
            BigDecimal quantity,
            UUID uomId,
            BigDecimal quantityBase,
            @Nullable BigDecimal unitCostBase,
            @Nullable BigDecimal valueBase,
            BigDecimal returnedQuantityBase) {}

    public record DeliveryDetail(Delivery delivery, List<DeliveryLine> lines) {}

    // -------------------------------------------------------------------------- sales returns

    public record SalesReturn(
            UUID id,
            UUID companyId,
            @Nullable String number,
            UUID customerId,
            UUID salesOrderId,
            UUID deliveryId,
            UUID branchId,
            UUID warehouseId,
            LocalDate returnDate,
            String reason,
            String status,
            @Nullable UUID stockMovementId,
            @Nullable OffsetDateTime receivedAt,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            int version) {}

    public record SalesReturnLine(
            UUID id,
            UUID salesReturnId,
            int lineNo,
            UUID deliveryLineId,
            UUID variantId,
            @Nullable UUID locationId,
            BigDecimal quantity,
            UUID uomId,
            BigDecimal quantityBase,
            BigDecimal unitCostBase,
            @Nullable BigDecimal valueBase,
            BigDecimal creditedQuantityBase) {}

    public record SalesReturnDetail(SalesReturn salesReturn, List<SalesReturnLine> lines) {}

    // ---------------------------------------------------------------------------- invoices

    public record Invoice(
            UUID id,
            UUID companyId,
            String documentType,
            @Nullable String number,
            UUID customerId,
            @Nullable UUID salesOrderId,
            @Nullable UUID originalInvoiceId,
            @Nullable UUID salesReturnId,
            LocalDate invoiceDate,
            LocalDate accountingDate,
            LocalDate dueDate,
            String currencyCode,
            BigDecimal exchangeRate,
            boolean pricesIncludeTax,
            @Nullable UUID paymentTermsId,
            Map<String, Object> billingAddress,
            @Nullable String customerTaxRegistrationNo,
            String status,
            BigDecimal subtotal,
            BigDecimal taxTotal,
            BigDecimal total,
            BigDecimal subtotalBase,
            BigDecimal taxTotalBase,
            BigDecimal totalBase,
            @Nullable String notes,
            @Nullable OffsetDateTime postedAt,
            @Nullable UUID createdBy,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            int version) {}

    public record InvoiceLine(
            UUID id,
            UUID invoiceId,
            int lineNo,
            @Nullable UUID salesOrderLineId,
            @Nullable UUID deliveryLineId,
            @Nullable UUID originalInvoiceLineId,
            @Nullable UUID salesReturnLineId,
            UUID variantId,
            String description,
            BigDecimal quantity,
            UUID uomId,
            BigDecimal quantityBase,
            BigDecimal unitPrice,
            BigDecimal discountPercent,
            @Nullable UUID taxCodeId,
            BigDecimal netAmount,
            BigDecimal taxAmount,
            BigDecimal totalAmount,
            BigDecimal netAmountBase,
            BigDecimal taxAmountBase,
            @Nullable UUID branchId,
            @Nullable UUID departmentId,
            BigDecimal creditedQuantityBase) {}

    public record InvoiceTax(
            UUID taxCodeId,
            BigDecimal ratePercent,
            BigDecimal taxableAmount,
            BigDecimal taxAmount,
            BigDecimal taxableAmountBase,
            BigDecimal taxAmountBase) {}

    public record InvoiceDetail(Invoice invoice, List<InvoiceLine> lines, List<InvoiceTax> taxes) {}

    /** The result of {@code POST {c}/pricing/quote}. */
    public record PriceQuote(
            String currencyCode,
            @Nullable UUID priceListId,
            boolean pricesIncludeTax,
            List<QuotedLine> lines,
            BigDecimal subtotal,
            BigDecimal taxTotal,
            BigDecimal total) {}

    public record QuotedLine(
            UUID variantId,
            BigDecimal quantity,
            UUID uomId,
            BigDecimal unitPrice,
            @Nullable BigDecimal listPrice,
            @Nullable UUID taxCodeId,
            BigDecimal netAmount,
            BigDecimal taxAmount,
            BigDecimal totalAmount) {}
}
