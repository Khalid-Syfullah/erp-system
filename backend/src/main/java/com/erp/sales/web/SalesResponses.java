package com.erp.sales.web;

import com.erp.platform.web.EntityTags;
import com.erp.sales.api.InvoiceSettlementPort;
import com.erp.sales.application.SalesViews;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;

/** Response bodies of the Sales endpoints (API.md §17.7). */
final class SalesResponses {

    static final String MERGE_PATCH = "application/merge-patch+json";

    private SalesResponses() {}

    record Settings(
            String defaultInvoicePolicy,
            String creditCheckMode,
            int quotationValidityDays,
            boolean reserveOnConfirm,
            @Nullable BigDecimal discountApprovalThresholdPercent) {
        static ResponseEntity<Settings> entity(SalesViews.Settings s) {
            return ResponseEntity.ok()
                    .eTag(EntityTags.forVersion(s.version()))
                    .body(new Settings(
                            s.defaultInvoicePolicy(),
                            s.creditCheckMode(),
                            s.quotationValidityDays(),
                            s.reserveOnConfirm(),
                            s.discountApprovalThresholdPercent()));
        }
    }

    // ------------------------------------------------------------------------------ price lists

    record PriceList(
            UUID id,
            String code,
            String name,
            String currencyCode,
            boolean pricesIncludeTax,
            @Nullable UUID customerGroupId,
            boolean isDefault,
            @Nullable LocalDate validFrom,
            @Nullable LocalDate validTo,
            boolean isActive,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            int version) {
        static PriceList from(SalesViews.PriceList l) {
            return new PriceList(
                    l.id(),
                    l.code(),
                    l.name(),
                    l.currencyCode(),
                    l.pricesIncludeTax(),
                    l.customerGroupId(),
                    l.isDefault(),
                    l.validFrom(),
                    l.validTo(),
                    l.active(),
                    l.createdAt(),
                    l.updatedAt(),
                    l.version());
        }

        static ResponseEntity<PriceList> entity(SalesViews.PriceList l) {
            return ResponseEntity.ok().eTag(EntityTags.forVersion(l.version())).body(from(l));
        }
    }

    record PriceListItem(
            UUID id,
            UUID priceListId,
            UUID variantId,
            UUID uomId,
            BigDecimal minQuantity,
            BigDecimal unitPrice,
            @Nullable LocalDate validFrom,
            @Nullable LocalDate validTo,
            int version) {
        static PriceListItem from(SalesViews.PriceListItem i) {
            return new PriceListItem(
                    i.id(),
                    i.priceListId(),
                    i.variantId(),
                    i.uomId(),
                    i.minQuantity(),
                    i.unitPrice(),
                    i.validFrom(),
                    i.validTo(),
                    i.version());
        }

        static ResponseEntity<PriceListItem> entity(SalesViews.PriceListItem i) {
            return ResponseEntity.ok().eTag(EntityTags.forVersion(i.version())).body(from(i));
        }
    }

    record QuotedLine(
            UUID variantId,
            BigDecimal quantity,
            UUID uomId,
            BigDecimal unitPrice,
            @Nullable BigDecimal listPrice,
            @Nullable UUID taxCodeId,
            BigDecimal netAmount,
            BigDecimal taxAmount,
            BigDecimal totalAmount) {}

    record PriceQuote(
            String currencyCode,
            @Nullable UUID priceListId,
            boolean pricesIncludeTax,
            List<QuotedLine> lines,
            BigDecimal subtotal,
            BigDecimal taxTotal,
            BigDecimal total) {
        static PriceQuote from(SalesViews.PriceQuote q) {
            return new PriceQuote(
                    q.currencyCode(),
                    q.priceListId(),
                    q.pricesIncludeTax(),
                    q.lines().stream()
                            .map(l -> new QuotedLine(
                                    l.variantId(),
                                    l.quantity(),
                                    l.uomId(),
                                    l.unitPrice(),
                                    l.listPrice(),
                                    l.taxCodeId(),
                                    l.netAmount(),
                                    l.taxAmount(),
                                    l.totalAmount()))
                            .toList(),
                    q.subtotal(),
                    q.taxTotal(),
                    q.total());
        }
    }

    // ------------------------------------------------------------------------------- quotations

    record Line(
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
            BigDecimal totalAmount) {
        static Line from(SalesViews.Line l) {
            return new Line(
                    l.id(),
                    l.lineNo(),
                    l.variantId(),
                    l.description(),
                    l.quantity(),
                    l.uomId(),
                    l.quantityBase(),
                    l.unitPrice(),
                    l.discountPercent(),
                    l.taxCodeId(),
                    l.netAmount(),
                    l.taxAmount(),
                    l.totalAmount());
        }
    }

    record Quotation(
            UUID id,
            @Nullable String number,
            String status,
            UUID customerId,
            UUID branchId,
            UUID warehouseId,
            LocalDate quotationDate,
            LocalDate validUntil,
            String currencyCode,
            @Nullable UUID priceListId,
            boolean pricesIncludeTax,
            @Nullable UUID paymentTermsId,
            BigDecimal subtotal,
            BigDecimal taxTotal,
            BigDecimal total,
            @Nullable UUID salesOrderId,
            @Nullable OffsetDateTime sentAt,
            @Nullable String rejectionReason,
            @Nullable String notes,
            @Nullable List<Line> lines,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            int version) {
        static Quotation from(SalesViews.Quotation q, @Nullable List<SalesViews.Line> lines) {
            return new Quotation(
                    q.id(),
                    q.number(),
                    q.status(),
                    q.customerId(),
                    q.branchId(),
                    q.warehouseId(),
                    q.quotationDate(),
                    q.validUntil(),
                    q.currencyCode(),
                    q.priceListId(),
                    q.pricesIncludeTax(),
                    q.paymentTermsId(),
                    q.subtotal(),
                    q.taxTotal(),
                    q.total(),
                    q.salesOrderId(),
                    q.sentAt(),
                    q.rejectionReason(),
                    q.notes(),
                    lines == null ? null : lines.stream().map(Line::from).toList(),
                    q.createdAt(),
                    q.updatedAt(),
                    q.version());
        }

        static ResponseEntity<Quotation> entity(SalesViews.QuotationDetail d) {
            return ResponseEntity.ok()
                    .eTag(EntityTags.forVersion(d.quotation().version()))
                    .body(from(d.quotation(), d.lines()));
        }
    }

    // ----------------------------------------------------------------------------- sales orders

    record SalesOrderLine(
            UUID id,
            int lineNo,
            UUID variantId,
            String description,
            boolean isStockable,
            BigDecimal quantity,
            UUID uomId,
            BigDecimal quantityBase,
            BigDecimal unitPrice,
            BigDecimal discountPercent,
            @Nullable UUID taxCodeId,
            BigDecimal netAmount,
            BigDecimal taxAmount,
            BigDecimal totalAmount,
            @Nullable UUID reservationId,
            BigDecimal reservedQuantityBase,
            BigDecimal backorderQuantityBase,
            BigDecimal deliveredQuantityBase,
            BigDecimal returnedQuantityBase,
            BigDecimal invoicedQuantityBase) {
        static SalesOrderLine from(SalesViews.SalesOrderLine l, boolean confirmed) {
            SalesViews.Line line = l.line();
            BigDecimal backorder = confirmed && l.stockable()
                    ? l.openToDeliverBase().subtract(l.reservedQuantityBase()).max(BigDecimal.ZERO)
                    : BigDecimal.ZERO;
            return new SalesOrderLine(
                    line.id(),
                    line.lineNo(),
                    line.variantId(),
                    line.description(),
                    l.stockable(),
                    line.quantity(),
                    line.uomId(),
                    line.quantityBase(),
                    line.unitPrice(),
                    line.discountPercent(),
                    line.taxCodeId(),
                    line.netAmount(),
                    line.taxAmount(),
                    line.totalAmount(),
                    l.reservationId(),
                    l.reservedQuantityBase(),
                    backorder,
                    l.deliveredQuantityBase(),
                    l.returnedQuantityBase(),
                    l.invoicedQuantityBase());
        }
    }

    record SalesOrder(
            UUID id,
            @Nullable String number,
            String status,
            String invoiceStatus,
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
            @Nullable List<SalesOrderLine> lines,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            int version) {
        static SalesOrder from(SalesViews.SalesOrder o, @Nullable List<SalesViews.SalesOrderLine> lines) {
            boolean open = List.of("CONFIRMED", "PARTIALLY_DELIVERED").contains(o.status());
            return new SalesOrder(
                    o.id(),
                    o.number(),
                    o.status(),
                    o.invoiceStatus(),
                    o.customerId(),
                    o.quotationId(),
                    o.branchId(),
                    o.warehouseId(),
                    o.orderDate(),
                    o.requestedDate(),
                    o.customerReference(),
                    o.currencyCode(),
                    o.priceListId(),
                    o.pricesIncludeTax(),
                    o.paymentTermsId(),
                    o.invoicePolicy(),
                    o.shippingAddress(),
                    o.billingAddress(),
                    o.exchangeRate(),
                    o.creditCheckResult(),
                    o.creditOverrideBy(),
                    o.creditOverrideReason(),
                    o.subtotal(),
                    o.taxTotal(),
                    o.total(),
                    o.confirmedAt(),
                    o.confirmedBy(),
                    o.cancelReason(),
                    o.closeReason(),
                    o.notes(),
                    lines == null
                            ? null
                            : lines.stream()
                                    .map(l -> SalesOrderLine.from(l, open))
                                    .toList(),
                    o.createdAt(),
                    o.updatedAt(),
                    o.version());
        }

        static ResponseEntity<SalesOrder> entity(SalesViews.SalesOrderDetail d) {
            return ResponseEntity.ok()
                    .eTag(EntityTags.forVersion(d.order().version()))
                    .body(from(d.order(), d.lines()));
        }
    }

    record CreditCheck(
            UUID salesOrderId,
            UUID customerId,
            String mode,
            @Nullable BigDecimal creditLimit,
            boolean onHold,
            BigDecimal openReceivablesBase,
            BigDecimal openOrdersBase,
            BigDecimal exposureBase,
            BigDecimal orderTotalBase,
            BigDecimal exchangeRate,
            String outcome) {
        static CreditCheck from(SalesViews.CreditCheckResult c) {
            return new CreditCheck(
                    c.salesOrderId(),
                    c.customerId(),
                    c.mode(),
                    c.creditLimit(),
                    c.onHold(),
                    c.openReceivablesBase(),
                    c.openOrdersBase(),
                    c.exposureBase(),
                    c.orderTotalBase(),
                    c.exchangeRate(),
                    c.outcome());
        }
    }

    // ------------------------------------------------------------------------------- deliveries

    record DeliveryLine(
            UUID id,
            int lineNo,
            UUID salesOrderLineId,
            UUID variantId,
            @Nullable UUID locationId,
            BigDecimal quantity,
            UUID uomId,
            BigDecimal quantityBase,
            @Nullable BigDecimal unitCostBase,
            @Nullable BigDecimal valueBase,
            BigDecimal returnedQuantityBase) {
        static DeliveryLine from(SalesViews.DeliveryLine l) {
            return new DeliveryLine(
                    l.id(),
                    l.lineNo(),
                    l.salesOrderLineId(),
                    l.variantId(),
                    l.locationId(),
                    l.quantity(),
                    l.uomId(),
                    l.quantityBase(),
                    l.unitCostBase(),
                    l.valueBase(),
                    l.returnedQuantityBase());
        }
    }

    record Delivery(
            UUID id,
            @Nullable String number,
            String status,
            UUID salesOrderId,
            UUID customerId,
            UUID branchId,
            UUID warehouseId,
            LocalDate deliveryDate,
            Map<String, Object> shippingAddress,
            @Nullable String carrier,
            @Nullable String trackingNumber,
            @Nullable UUID stockMovementId,
            @Nullable String notes,
            @Nullable OffsetDateTime postedAt,
            @Nullable List<DeliveryLine> lines,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            int version) {
        static Delivery from(SalesViews.Delivery d, @Nullable List<SalesViews.DeliveryLine> lines) {
            return new Delivery(
                    d.id(),
                    d.number(),
                    d.status(),
                    d.salesOrderId(),
                    d.customerId(),
                    d.branchId(),
                    d.warehouseId(),
                    d.deliveryDate(),
                    d.shippingAddress(),
                    d.carrier(),
                    d.trackingNumber(),
                    d.stockMovementId(),
                    d.notes(),
                    d.postedAt(),
                    lines == null
                            ? null
                            : lines.stream().map(DeliveryLine::from).toList(),
                    d.createdAt(),
                    d.updatedAt(),
                    d.version());
        }

        static ResponseEntity<Delivery> entity(SalesViews.DeliveryDetail d) {
            return ResponseEntity.ok()
                    .eTag(EntityTags.forVersion(d.delivery().version()))
                    .body(from(d.delivery(), d.lines()));
        }
    }

    // ---------------------------------------------------------------------------- sales returns

    record SalesReturnLine(
            UUID id,
            int lineNo,
            UUID deliveryLineId,
            UUID variantId,
            @Nullable UUID locationId,
            BigDecimal quantity,
            UUID uomId,
            BigDecimal quantityBase,
            BigDecimal unitCostBase,
            @Nullable BigDecimal valueBase,
            BigDecimal creditedQuantityBase) {
        static SalesReturnLine from(SalesViews.SalesReturnLine l) {
            return new SalesReturnLine(
                    l.id(),
                    l.lineNo(),
                    l.deliveryLineId(),
                    l.variantId(),
                    l.locationId(),
                    l.quantity(),
                    l.uomId(),
                    l.quantityBase(),
                    l.unitCostBase(),
                    l.valueBase(),
                    l.creditedQuantityBase());
        }
    }

    record SalesReturn(
            UUID id,
            @Nullable String number,
            String status,
            UUID customerId,
            UUID salesOrderId,
            UUID deliveryId,
            UUID branchId,
            UUID warehouseId,
            LocalDate returnDate,
            String reason,
            @Nullable UUID stockMovementId,
            @Nullable OffsetDateTime receivedAt,
            @Nullable List<SalesReturnLine> lines,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            int version) {
        static SalesReturn from(SalesViews.SalesReturn r, @Nullable List<SalesViews.SalesReturnLine> lines) {
            return new SalesReturn(
                    r.id(),
                    r.number(),
                    r.status(),
                    r.customerId(),
                    r.salesOrderId(),
                    r.deliveryId(),
                    r.branchId(),
                    r.warehouseId(),
                    r.returnDate(),
                    r.reason(),
                    r.stockMovementId(),
                    r.receivedAt(),
                    lines == null
                            ? null
                            : lines.stream().map(SalesReturnLine::from).toList(),
                    r.createdAt(),
                    r.updatedAt(),
                    r.version());
        }

        static ResponseEntity<SalesReturn> entity(SalesViews.SalesReturnDetail d) {
            return ResponseEntity.ok()
                    .eTag(EntityTags.forVersion(d.salesReturn().version()))
                    .body(from(d.salesReturn(), d.lines()));
        }
    }

    // --------------------------------------------------------------------------------- invoices

    record InvoiceLine(
            UUID id,
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
            BigDecimal creditedQuantityBase) {
        static InvoiceLine from(SalesViews.InvoiceLine l) {
            return new InvoiceLine(
                    l.id(),
                    l.lineNo(),
                    l.salesOrderLineId(),
                    l.deliveryLineId(),
                    l.originalInvoiceLineId(),
                    l.salesReturnLineId(),
                    l.variantId(),
                    l.description(),
                    l.quantity(),
                    l.uomId(),
                    l.quantityBase(),
                    l.unitPrice(),
                    l.discountPercent(),
                    l.taxCodeId(),
                    l.netAmount(),
                    l.taxAmount(),
                    l.totalAmount(),
                    l.netAmountBase(),
                    l.taxAmountBase(),
                    l.branchId(),
                    l.departmentId(),
                    l.creditedQuantityBase());
        }
    }

    record InvoiceTax(
            UUID taxCodeId,
            BigDecimal ratePercent,
            BigDecimal taxableAmount,
            BigDecimal taxAmount,
            BigDecimal taxableAmountBase,
            BigDecimal taxAmountBase) {
        static InvoiceTax from(SalesViews.InvoiceTax t) {
            return new InvoiceTax(
                    t.taxCodeId(),
                    t.ratePercent(),
                    t.taxableAmount(),
                    t.taxAmount(),
                    t.taxableAmountBase(),
                    t.taxAmountBase());
        }
    }

    record Invoice(
            UUID id,
            String documentType,
            @Nullable String number,
            String status,
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
            BigDecimal subtotal,
            BigDecimal taxTotal,
            BigDecimal total,
            BigDecimal subtotalBase,
            BigDecimal taxTotalBase,
            BigDecimal totalBase,
            @Nullable String notes,
            @Nullable OffsetDateTime postedAt,
            @Nullable List<InvoiceLine> lines,
            @Nullable List<InvoiceTax> taxes,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            int version) {
        static Invoice from(
                SalesViews.Invoice i,
                @Nullable List<SalesViews.InvoiceLine> lines,
                @Nullable List<SalesViews.InvoiceTax> taxes) {
            return new Invoice(
                    i.id(),
                    i.documentType(),
                    i.number(),
                    i.status(),
                    i.customerId(),
                    i.salesOrderId(),
                    i.originalInvoiceId(),
                    i.salesReturnId(),
                    i.invoiceDate(),
                    i.accountingDate(),
                    i.dueDate(),
                    i.currencyCode(),
                    i.exchangeRate(),
                    i.pricesIncludeTax(),
                    i.paymentTermsId(),
                    i.billingAddress(),
                    i.customerTaxRegistrationNo(),
                    i.subtotal(),
                    i.taxTotal(),
                    i.total(),
                    i.subtotalBase(),
                    i.taxTotalBase(),
                    i.totalBase(),
                    i.notes(),
                    i.postedAt(),
                    lines == null ? null : lines.stream().map(InvoiceLine::from).toList(),
                    taxes == null ? null : taxes.stream().map(InvoiceTax::from).toList(),
                    i.createdAt(),
                    i.updatedAt(),
                    i.version());
        }

        static ResponseEntity<Invoice> entity(SalesViews.InvoiceDetail d) {
            return ResponseEntity.ok()
                    .eTag(EntityTags.forVersion(d.invoice().version()))
                    .body(from(d.invoice(), d.lines(), d.taxes()));
        }
    }

    record Settlement(
            UUID invoiceId, String status, @Nullable BigDecimal openAmount) {
        static Settlement from(InvoiceSettlementPort.Settlement s) {
            return new Settlement(s.invoiceId(), s.status().name(), s.openAmount());
        }
    }
}
