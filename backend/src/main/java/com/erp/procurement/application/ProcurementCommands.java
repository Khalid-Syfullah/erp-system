package com.erp.procurement.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Write models of the Procurement module (built by the web layer from request DTOs). */
public final class ProcurementCommands {

    private ProcurementCommands() {}

    public record RequisitionLine(
            UUID variantId,
            @Nullable String description,
            BigDecimal quantity,
            UUID uomId,
            @Nullable BigDecimal estimatedUnitPrice,
            @Nullable UUID suggestedSupplierId) {}

    public record Requisition(
            UUID branchId,
            @Nullable UUID departmentId,
            @Nullable LocalDate neededBy,
            @Nullable String notes,
            List<RequisitionLine> lines) {}

    /** A priced document line (orders and bills). */
    public record OrderLine(
            UUID variantId,
            @Nullable String description,
            BigDecimal quantity,
            UUID uomId,
            BigDecimal unitPrice,
            BigDecimal discountPercent,
            @Nullable UUID taxCodeId,
            @Nullable UUID requisitionLineId) {}

    public record PurchaseOrder(
            UUID supplierId,
            UUID warehouseId,
            @Nullable UUID departmentId,
            @Nullable LocalDate orderDate,
            @Nullable LocalDate expectedDate,
            @Nullable String currencyCode,
            @Nullable UUID paymentTermsId,
            boolean pricesIncludeTax,
            @Nullable String notes,
            List<OrderLine> lines) {}

    public record ReceiptLine(
            UUID purchaseOrderLineId,
            BigDecimal quantity,
            @Nullable UUID uomId,
            @Nullable UUID locationId) {}

    /** {@code lines == null}: everything still open on the order's stockable lines. */
    public record Receipt(
            UUID purchaseOrderId,
            @Nullable LocalDate receiptDate,
            @Nullable String supplierDeliveryNote,
            @Nullable String notes,
            @Nullable List<ReceiptLine> lines) {}

    public record ReturnLine(
            UUID goodsReceiptLineId,
            BigDecimal quantity,
            @Nullable UUID uomId) {}

    public record PurchaseReturn(
            UUID goodsReceiptId, @Nullable LocalDate returnDate, String reason, List<ReturnLine> lines) {}

    /**
     * A bill line: against a receipt line (received stock), an order line (services and non-stock
     * goods) or, for direct bills, a product.
     */
    public record BillLine(
            @Nullable UUID goodsReceiptLineId,
            @Nullable UUID purchaseOrderLineId,
            @Nullable UUID variantId,
            @Nullable String description,
            BigDecimal quantity,
            @Nullable UUID uomId,
            BigDecimal unitPrice,
            BigDecimal discountPercent,
            @Nullable UUID taxCodeId,
            @Nullable UUID branchId,
            @Nullable UUID departmentId) {}

    public record SupplierBill(
            String documentType,
            UUID supplierId,
            String supplierInvoiceNumber,
            LocalDate billDate,
            @Nullable LocalDate accountingDate,
            @Nullable LocalDate dueDate,
            @Nullable UUID purchaseOrderId,
            @Nullable UUID originalBillId,
            boolean pricesIncludeTax,
            @Nullable String notes,
            List<BillLine> lines) {}
}
