package com.erp.procurement.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Read models of the Procurement module. */
public final class ProcurementViews {

    private ProcurementViews() {}

    public record Settings(
            @Nullable BigDecimal poApprovalThresholdBase,
            BigDecimal priceMatchTolerancePercent,
            BigDecimal qtyMatchTolerancePercent,
            boolean requireReceiptBeforeBill,
            int version) {}

    // ---------------------------------------------------------------------------- requisitions

    public record Requisition(
            UUID id,
            UUID companyId,
            @Nullable String number,
            UUID branchId,
            @Nullable UUID departmentId,
            UUID requestedBy,
            @Nullable LocalDate neededBy,
            String status,
            @Nullable UUID submittedBy,
            @Nullable OffsetDateTime submittedAt,
            @Nullable UUID approvedBy,
            @Nullable OffsetDateTime approvedAt,
            @Nullable String rejectionReason,
            @Nullable String cancelReason,
            @Nullable String notes,
            @Nullable UUID createdBy,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            int version) {}

    public record RequisitionLine(
            UUID id,
            int lineNo,
            UUID variantId,
            String description,
            BigDecimal quantity,
            UUID uomId,
            BigDecimal quantityBase,
            @Nullable BigDecimal estimatedUnitPrice,
            @Nullable UUID suggestedSupplierId,
            BigDecimal orderedQuantityBase) {}

    public record RequisitionDetail(Requisition requisition, List<RequisitionLine> lines) {}

    // ------------------------------------------------------------------------- purchase orders

    public record PurchaseOrder(
            UUID id,
            UUID companyId,
            @Nullable String number,
            UUID supplierId,
            UUID branchId,
            UUID warehouseId,
            @Nullable UUID departmentId,
            LocalDate orderDate,
            @Nullable LocalDate expectedDate,
            String currencyCode,
            @Nullable UUID paymentTermsId,
            boolean pricesIncludeTax,
            String status,
            String billingStatus,
            BigDecimal subtotal,
            BigDecimal taxTotal,
            BigDecimal total,
            @Nullable UUID submittedBy,
            @Nullable OffsetDateTime submittedAt,
            @Nullable UUID approvedBy,
            @Nullable OffsetDateTime approvedAt,
            @Nullable String rejectionReason,
            @Nullable String cancelReason,
            @Nullable String closeReason,
            @Nullable String notes,
            @Nullable UUID createdBy,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            int version) {}

    public record PurchaseOrderLine(
            UUID id,
            int lineNo,
            UUID variantId,
            String description,
            boolean stockable,
            BigDecimal quantity,
            UUID uomId,
            BigDecimal quantityBase,
            BigDecimal unitPrice,
            BigDecimal discountPercent,
            @Nullable UUID taxCodeId,
            BigDecimal netAmount,
            BigDecimal taxAmount,
            BigDecimal totalAmount,
            BigDecimal receivedQuantityBase,
            BigDecimal returnedQuantityBase,
            BigDecimal billedQuantityBase,
            @Nullable UUID requisitionLineId) {

        /** Received less returned. */
        public BigDecimal netReceivedBase() {
            return receivedQuantityBase.subtract(returnedQuantityBase);
        }
    }

    public record PurchaseOrderDetail(PurchaseOrder order, List<PurchaseOrderLine> lines) {}

    // ------------------------------------------------------------------------- goods receipts

    public record GoodsReceipt(
            UUID id,
            UUID companyId,
            @Nullable String number,
            UUID purchaseOrderId,
            UUID supplierId,
            UUID branchId,
            UUID warehouseId,
            LocalDate receiptDate,
            String status,
            String currencyCode,
            @Nullable BigDecimal exchangeRate,
            @Nullable UUID stockMovementId,
            @Nullable String supplierDeliveryNote,
            @Nullable String notes,
            @Nullable OffsetDateTime postedAt,
            @Nullable UUID postedBy,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            int version) {}

    public record GoodsReceiptLine(
            UUID id,
            UUID goodsReceiptId,
            int lineNo,
            UUID purchaseOrderLineId,
            UUID variantId,
            @Nullable UUID locationId,
            BigDecimal quantity,
            UUID uomId,
            BigDecimal quantityBase,
            BigDecimal unitCostDoc,
            @Nullable BigDecimal unitCostBase,
            @Nullable BigDecimal valueBase,
            BigDecimal billedQuantityBase,
            BigDecimal billedValueBase,
            BigDecimal returnedQuantityBase,
            BigDecimal returnedValueBase,
            BigDecimal creditedQuantityBase,
            BigDecimal creditedValueBase) {

        /** Quantity still to bill: received − returned − (billed − credited); negative if over-billed. */
        public BigDecimal openToBillBase() {
            return quantityBase
                    .subtract(returnedQuantityBase)
                    .subtract(billedQuantityBase.subtract(creditedQuantityBase));
        }

        /** Receipt value not yet cleared by bills or returns (GRNI of the line). */
        public BigDecimal openValueBase() {
            return (valueBase == null ? BigDecimal.ZERO : valueBase)
                    .subtract(returnedValueBase)
                    .subtract(billedValueBase.subtract(creditedValueBase));
        }
    }

    public record GoodsReceiptDetail(GoodsReceipt receipt, List<GoodsReceiptLine> lines) {}

    // ----------------------------------------------------------------------- purchase returns

    public record PurchaseReturn(
            UUID id,
            UUID companyId,
            @Nullable String number,
            UUID supplierId,
            UUID purchaseOrderId,
            UUID goodsReceiptId,
            UUID branchId,
            UUID warehouseId,
            LocalDate returnDate,
            String status,
            String reason,
            @Nullable UUID stockMovementId,
            @Nullable OffsetDateTime postedAt,
            @Nullable UUID postedBy,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            int version) {}

    public record PurchaseReturnLine(
            UUID id,
            int lineNo,
            UUID goodsReceiptLineId,
            UUID variantId,
            @Nullable UUID locationId,
            BigDecimal quantity,
            UUID uomId,
            BigDecimal quantityBase,
            BigDecimal unitCostBase,
            @Nullable BigDecimal valueBase) {}

    public record PurchaseReturnDetail(PurchaseReturn purchaseReturn, List<PurchaseReturnLine> lines) {}

    // ------------------------------------------------------------------------ supplier bills

    public record SupplierBill(
            UUID id,
            UUID companyId,
            String documentType,
            @Nullable String number,
            String supplierInvoiceNumber,
            UUID supplierId,
            @Nullable UUID purchaseOrderId,
            @Nullable UUID originalBillId,
            LocalDate billDate,
            LocalDate accountingDate,
            LocalDate dueDate,
            String currencyCode,
            BigDecimal exchangeRate,
            boolean pricesIncludeTax,
            @Nullable UUID paymentTermsId,
            String status,
            String matchStatus,
            @Nullable UUID matchOverrideBy,
            @Nullable String matchOverrideReason,
            BigDecimal subtotal,
            BigDecimal taxTotal,
            BigDecimal total,
            BigDecimal subtotalBase,
            BigDecimal taxTotalBase,
            BigDecimal totalBase,
            @Nullable String notes,
            @Nullable OffsetDateTime postedAt,
            @Nullable UUID postedBy,
            @Nullable UUID createdBy,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            int version) {}

    public record SupplierBillLine(
            UUID id,
            int lineNo,
            String lineKind,
            @Nullable UUID purchaseOrderLineId,
            @Nullable UUID goodsReceiptLineId,
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
            @Nullable BigDecimal receiptValueBase,
            @Nullable UUID branchId,
            @Nullable UUID departmentId) {}

    public record SupplierBillTax(
            UUID taxCodeId,
            BigDecimal ratePercent,
            BigDecimal taxableAmount,
            BigDecimal taxAmount,
            BigDecimal taxableAmountBase,
            BigDecimal taxAmountBase) {}

    public record SupplierBillDetail(SupplierBill bill, List<SupplierBillLine> lines, List<SupplierBillTax> taxes) {}

    /** The result of a three-way match check: one entry per problem. */
    public record MatchIssue(int lineNo, String problem, BigDecimal expected, BigDecimal actual) {}

    public record MatchResult(String matchStatus, List<MatchIssue> issues) {}
}
