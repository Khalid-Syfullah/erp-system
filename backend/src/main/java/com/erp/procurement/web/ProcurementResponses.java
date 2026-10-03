package com.erp.procurement.web;

import com.erp.platform.web.EntityTags;
import com.erp.procurement.application.ProcurementViews;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;

/** Response bodies of the Procurement endpoints (API.md §17.6). */
final class ProcurementResponses {

    static final String MERGE_PATCH = "application/merge-patch+json";

    private ProcurementResponses() {}

    record Settings(
            @Nullable BigDecimal poApprovalThresholdBase,
            BigDecimal priceMatchTolerancePercent,
            BigDecimal qtyMatchTolerancePercent,
            boolean requireReceiptBeforeBill) {
        static ResponseEntity<Settings> entity(ProcurementViews.Settings s) {
            return ResponseEntity.ok()
                    .eTag(EntityTags.forVersion(s.version()))
                    .body(new Settings(
                            s.poApprovalThresholdBase(),
                            s.priceMatchTolerancePercent(),
                            s.qtyMatchTolerancePercent(),
                            s.requireReceiptBeforeBill()));
        }
    }

    // ---------------------------------------------------------------------------- requisitions

    record RequisitionLine(
            UUID id,
            int lineNo,
            UUID variantId,
            String description,
            BigDecimal quantity,
            UUID uomId,
            BigDecimal quantityBase,
            @Nullable BigDecimal estimatedUnitPrice,
            @Nullable UUID suggestedSupplierId,
            BigDecimal orderedQuantityBase) {
        static RequisitionLine from(ProcurementViews.RequisitionLine l) {
            return new RequisitionLine(
                    l.id(),
                    l.lineNo(),
                    l.variantId(),
                    l.description(),
                    l.quantity(),
                    l.uomId(),
                    l.quantityBase(),
                    l.estimatedUnitPrice(),
                    l.suggestedSupplierId(),
                    l.orderedQuantityBase());
        }
    }

    record Requisition(
            UUID id,
            @Nullable String number,
            String status,
            UUID branchId,
            @Nullable UUID departmentId,
            UUID requestedBy,
            @Nullable LocalDate neededBy,
            @Nullable UUID submittedBy,
            @Nullable OffsetDateTime submittedAt,
            @Nullable UUID approvedBy,
            @Nullable OffsetDateTime approvedAt,
            @Nullable String rejectionReason,
            @Nullable String cancelReason,
            @Nullable String notes,
            @Nullable List<RequisitionLine> lines,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            int version) {
        static Requisition from(
                ProcurementViews.Requisition r, @Nullable List<ProcurementViews.RequisitionLine> lines) {
            return new Requisition(
                    r.id(),
                    r.number(),
                    r.status(),
                    r.branchId(),
                    r.departmentId(),
                    r.requestedBy(),
                    r.neededBy(),
                    r.submittedBy(),
                    r.submittedAt(),
                    r.approvedBy(),
                    r.approvedAt(),
                    r.rejectionReason(),
                    r.cancelReason(),
                    r.notes(),
                    lines == null
                            ? null
                            : lines.stream().map(RequisitionLine::from).toList(),
                    r.createdAt(),
                    r.updatedAt(),
                    r.version());
        }

        static ResponseEntity<Requisition> entity(ProcurementViews.RequisitionDetail d) {
            return ResponseEntity.ok()
                    .eTag(EntityTags.forVersion(d.requisition().version()))
                    .body(from(d.requisition(), d.lines()));
        }
    }

    // ------------------------------------------------------------------------- purchase orders

    record PurchaseOrderLine(
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
            BigDecimal receivedQuantityBase,
            BigDecimal returnedQuantityBase,
            BigDecimal billedQuantityBase,
            @Nullable UUID requisitionLineId) {
        static PurchaseOrderLine from(ProcurementViews.PurchaseOrderLine l) {
            return new PurchaseOrderLine(
                    l.id(),
                    l.lineNo(),
                    l.variantId(),
                    l.description(),
                    l.stockable(),
                    l.quantity(),
                    l.uomId(),
                    l.quantityBase(),
                    l.unitPrice(),
                    l.discountPercent(),
                    l.taxCodeId(),
                    l.netAmount(),
                    l.taxAmount(),
                    l.totalAmount(),
                    l.receivedQuantityBase(),
                    l.returnedQuantityBase(),
                    l.billedQuantityBase(),
                    l.requisitionLineId());
        }
    }

    record PurchaseOrder(
            UUID id,
            @Nullable String number,
            String status,
            String billingStatus,
            UUID supplierId,
            UUID branchId,
            UUID warehouseId,
            @Nullable UUID departmentId,
            LocalDate orderDate,
            @Nullable LocalDate expectedDate,
            String currencyCode,
            @Nullable UUID paymentTermsId,
            boolean pricesIncludeTax,
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
            @Nullable List<PurchaseOrderLine> lines,
            @Nullable UUID createdBy,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            int version) {
        static PurchaseOrder from(
                ProcurementViews.PurchaseOrder o, @Nullable List<ProcurementViews.PurchaseOrderLine> lines) {
            return new PurchaseOrder(
                    o.id(),
                    o.number(),
                    o.status(),
                    o.billingStatus(),
                    o.supplierId(),
                    o.branchId(),
                    o.warehouseId(),
                    o.departmentId(),
                    o.orderDate(),
                    o.expectedDate(),
                    o.currencyCode(),
                    o.paymentTermsId(),
                    o.pricesIncludeTax(),
                    o.subtotal(),
                    o.taxTotal(),
                    o.total(),
                    o.submittedBy(),
                    o.submittedAt(),
                    o.approvedBy(),
                    o.approvedAt(),
                    o.rejectionReason(),
                    blankToNull(o.cancelReason()),
                    blankToNull(o.closeReason()),
                    o.notes(),
                    lines == null
                            ? null
                            : lines.stream().map(PurchaseOrderLine::from).toList(),
                    o.createdBy(),
                    o.createdAt(),
                    o.updatedAt(),
                    o.version());
        }

        static ResponseEntity<PurchaseOrder> entity(ProcurementViews.PurchaseOrderDetail d) {
            return ResponseEntity.ok()
                    .eTag(EntityTags.forVersion(d.order().version()))
                    .body(from(d.order(), d.lines()));
        }
    }

    // ------------------------------------------------------------------------- goods receipts

    record GoodsReceiptLine(
            UUID id,
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
            BigDecimal returnedQuantityBase,
            BigDecimal creditedQuantityBase) {
        static GoodsReceiptLine from(ProcurementViews.GoodsReceiptLine l) {
            return new GoodsReceiptLine(
                    l.id(),
                    l.lineNo(),
                    l.purchaseOrderLineId(),
                    l.variantId(),
                    l.locationId(),
                    l.quantity(),
                    l.uomId(),
                    l.quantityBase(),
                    l.unitCostDoc(),
                    l.unitCostBase(),
                    l.valueBase(),
                    l.billedQuantityBase(),
                    l.returnedQuantityBase(),
                    l.creditedQuantityBase());
        }
    }

    record GoodsReceipt(
            UUID id,
            @Nullable String number,
            String status,
            UUID purchaseOrderId,
            UUID supplierId,
            UUID branchId,
            UUID warehouseId,
            LocalDate receiptDate,
            String currencyCode,
            @Nullable BigDecimal exchangeRate,
            @Nullable UUID stockMovementId,
            @Nullable String supplierDeliveryNote,
            @Nullable String notes,
            @Nullable OffsetDateTime postedAt,
            @Nullable UUID postedBy,
            @Nullable List<GoodsReceiptLine> lines,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            int version) {
        static GoodsReceipt from(
                ProcurementViews.GoodsReceipt r, @Nullable List<ProcurementViews.GoodsReceiptLine> lines) {
            return new GoodsReceipt(
                    r.id(),
                    r.number(),
                    r.status(),
                    r.purchaseOrderId(),
                    r.supplierId(),
                    r.branchId(),
                    r.warehouseId(),
                    r.receiptDate(),
                    r.currencyCode(),
                    r.exchangeRate(),
                    r.stockMovementId(),
                    r.supplierDeliveryNote(),
                    r.notes(),
                    r.postedAt(),
                    r.postedBy(),
                    lines == null
                            ? null
                            : lines.stream().map(GoodsReceiptLine::from).toList(),
                    r.createdAt(),
                    r.updatedAt(),
                    r.version());
        }

        static ResponseEntity<GoodsReceipt> entity(ProcurementViews.GoodsReceiptDetail d) {
            return ResponseEntity.ok()
                    .eTag(EntityTags.forVersion(d.receipt().version()))
                    .body(from(d.receipt(), d.lines()));
        }
    }

    // ----------------------------------------------------------------------- purchase returns

    record PurchaseReturnLine(
            UUID id,
            int lineNo,
            UUID goodsReceiptLineId,
            UUID variantId,
            @Nullable UUID locationId,
            BigDecimal quantity,
            UUID uomId,
            BigDecimal quantityBase,
            BigDecimal unitCostBase,
            @Nullable BigDecimal valueBase) {
        static PurchaseReturnLine from(ProcurementViews.PurchaseReturnLine l) {
            return new PurchaseReturnLine(
                    l.id(),
                    l.lineNo(),
                    l.goodsReceiptLineId(),
                    l.variantId(),
                    l.locationId(),
                    l.quantity(),
                    l.uomId(),
                    l.quantityBase(),
                    l.unitCostBase(),
                    l.valueBase());
        }
    }

    record PurchaseReturn(
            UUID id,
            @Nullable String number,
            String status,
            UUID supplierId,
            UUID purchaseOrderId,
            UUID goodsReceiptId,
            UUID warehouseId,
            LocalDate returnDate,
            String reason,
            @Nullable UUID stockMovementId,
            @Nullable OffsetDateTime postedAt,
            @Nullable List<PurchaseReturnLine> lines,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            int version) {
        static PurchaseReturn from(
                ProcurementViews.PurchaseReturn r, @Nullable List<ProcurementViews.PurchaseReturnLine> lines) {
            return new PurchaseReturn(
                    r.id(),
                    r.number(),
                    r.status(),
                    r.supplierId(),
                    r.purchaseOrderId(),
                    r.goodsReceiptId(),
                    r.warehouseId(),
                    r.returnDate(),
                    r.reason(),
                    r.stockMovementId(),
                    r.postedAt(),
                    lines == null
                            ? null
                            : lines.stream().map(PurchaseReturnLine::from).toList(),
                    r.createdAt(),
                    r.updatedAt(),
                    r.version());
        }

        static ResponseEntity<PurchaseReturn> entity(ProcurementViews.PurchaseReturnDetail d) {
            return ResponseEntity.ok()
                    .eTag(EntityTags.forVersion(d.purchaseReturn().version()))
                    .body(from(d.purchaseReturn(), d.lines()));
        }
    }

    // ------------------------------------------------------------------------ supplier bills

    record SupplierBillLine(
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
            @Nullable UUID departmentId) {
        static SupplierBillLine from(ProcurementViews.SupplierBillLine l) {
            return new SupplierBillLine(
                    l.id(),
                    l.lineNo(),
                    l.lineKind(),
                    l.purchaseOrderLineId(),
                    l.goodsReceiptLineId(),
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
                    l.receiptValueBase(),
                    l.branchId(),
                    l.departmentId());
        }
    }

    record SupplierBillTax(
            UUID taxCodeId,
            BigDecimal ratePercent,
            BigDecimal taxableAmount,
            BigDecimal taxAmount,
            BigDecimal taxableAmountBase,
            BigDecimal taxAmountBase) {
        static SupplierBillTax from(ProcurementViews.SupplierBillTax t) {
            return new SupplierBillTax(
                    t.taxCodeId(),
                    t.ratePercent(),
                    t.taxableAmount(),
                    t.taxAmount(),
                    t.taxableAmountBase(),
                    t.taxAmountBase());
        }
    }

    record SupplierBill(
            UUID id,
            String documentType,
            @Nullable String number,
            String supplierInvoiceNumber,
            String status,
            String matchStatus,
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
            @Nullable List<SupplierBillLine> lines,
            @Nullable List<SupplierBillTax> taxes,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            int version) {
        static SupplierBill from(
                ProcurementViews.SupplierBill b,
                @Nullable List<ProcurementViews.SupplierBillLine> lines,
                @Nullable List<ProcurementViews.SupplierBillTax> taxes) {
            return new SupplierBill(
                    b.id(),
                    b.documentType(),
                    b.number(),
                    b.supplierInvoiceNumber(),
                    b.status(),
                    b.matchStatus(),
                    b.supplierId(),
                    b.purchaseOrderId(),
                    b.originalBillId(),
                    b.billDate(),
                    b.accountingDate(),
                    b.dueDate(),
                    b.currencyCode(),
                    b.exchangeRate(),
                    b.pricesIncludeTax(),
                    b.paymentTermsId(),
                    b.matchOverrideBy(),
                    b.matchOverrideReason(),
                    b.subtotal(),
                    b.taxTotal(),
                    b.total(),
                    b.subtotalBase(),
                    b.taxTotalBase(),
                    b.totalBase(),
                    b.notes(),
                    b.postedAt(),
                    lines == null
                            ? null
                            : lines.stream().map(SupplierBillLine::from).toList(),
                    taxes == null
                            ? null
                            : taxes.stream().map(SupplierBillTax::from).toList(),
                    b.createdAt(),
                    b.updatedAt(),
                    b.version());
        }

        static ResponseEntity<SupplierBill> entity(ProcurementViews.SupplierBillDetail d) {
            return ResponseEntity.ok()
                    .eTag(EntityTags.forVersion(d.bill().version()))
                    .body(from(d.bill(), d.lines(), d.taxes()));
        }
    }

    record MatchIssue(int lineNo, String problem, BigDecimal expected, BigDecimal actual) {}

    record MatchResult(String matchStatus, List<MatchIssue> issues) {
        static MatchResult from(ProcurementViews.MatchResult r) {
            return new MatchResult(
                    r.matchStatus(),
                    r.issues().stream()
                            .map(i -> new MatchIssue(i.lineNo(), i.problem(), i.expected(), i.actual()))
                            .toList());
        }
    }

    record Settlement(UUID billId, String status, @Nullable BigDecimal openAmount) {}

    private static @Nullable String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
