package com.erp.procurement.events;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Contract snapshots of the Procurement events v1 (ARCHITECTURE.md §7). Accounting (Phase 8) books
 * from these fields; a change needs a new schema version.
 */
class ProcurementEventContractTest {

    @Test
    void typesAndVersions() {
        assertThat(SupplierBillPosted.BILL_TYPE).isEqualTo("procurement.supplier_bill.posted");
        assertThat(SupplierBillPosted.DEBIT_NOTE_TYPE).isEqualTo("procurement.debit_note.posted");
        assertThat(GoodsReceiptPosted.TYPE).isEqualTo("procurement.goods_receipt.posted");
        assertThat(PurchaseOrderApproved.TYPE).isEqualTo("procurement.purchase_order.approved");
        assertThat(List.of(
                        SupplierBillPosted.SCHEMA_VERSION,
                        GoodsReceiptPosted.SCHEMA_VERSION,
                        PurchaseOrderApproved.SCHEMA_VERSION))
                .containsOnly(1);
    }

    @Test
    void supplierBillPosted() {
        assertThat(shape(SupplierBillPosted.class))
                .containsExactly(
                        "metadata:EventMetadata",
                        "billId:UUID",
                        "documentType:String",
                        "number:String",
                        "supplierInvoiceNumber:String",
                        "supplierId:UUID",
                        "supplierGroupId:UUID",
                        "originalBillId:UUID",
                        "purchaseOrderId:UUID",
                        "documentDate:LocalDate",
                        "accountingDate:LocalDate",
                        "dueDate:LocalDate",
                        "currencyCode:String",
                        "exchangeRate:BigDecimal",
                        "totals:Totals",
                        "lines:List",
                        "taxLines:List");
        assertThat(shape(SupplierBillPosted.Totals.class))
                .containsExactly(
                        "subtotal:BigDecimal",
                        "taxTotal:BigDecimal",
                        "total:BigDecimal",
                        "subtotalBase:BigDecimal",
                        "taxTotalBase:BigDecimal",
                        "totalBase:BigDecimal");
        assertThat(shape(SupplierBillPosted.Line.class))
                .containsExactly(
                        "lineId:UUID",
                        "type:String",
                        "variantId:UUID",
                        "categoryId:UUID",
                        "purchaseOrderLineId:UUID",
                        "goodsReceiptLineId:UUID",
                        "quantityBase:BigDecimal",
                        "netDoc:BigDecimal",
                        "netBase:BigDecimal",
                        "receiptValueBase:BigDecimal",
                        "taxCodeId:UUID",
                        "branchId:UUID",
                        "departmentId:UUID");
        assertThat(shape(SupplierBillPosted.TaxLine.class))
                .containsExactly(
                        "taxCodeId:UUID",
                        "ratePercent:BigDecimal",
                        "taxableDoc:BigDecimal",
                        "taxDoc:BigDecimal",
                        "taxableBase:BigDecimal",
                        "taxBase:BigDecimal");
    }

    @Test
    void goodsReceiptAndOrderEvents() {
        assertThat(shape(GoodsReceiptPosted.class))
                .containsExactly(
                        "metadata:EventMetadata",
                        "goodsReceiptId:UUID",
                        "number:String",
                        "purchaseOrderId:UUID",
                        "supplierId:UUID",
                        "stockMovementId:UUID",
                        "receiptDate:LocalDate",
                        "currencyCode:String",
                        "exchangeRate:BigDecimal",
                        "lines:List");
        assertThat(shape(GoodsReceiptPosted.Line.class))
                .containsExactly(
                        "goodsReceiptLineId:UUID",
                        "purchaseOrderLineId:UUID",
                        "variantId:UUID",
                        "quantityBase:BigDecimal",
                        "poUnitPrice:BigDecimal",
                        "valueBase:BigDecimal");
        assertThat(shape(PurchaseOrderApproved.class))
                .containsExactly(
                        "metadata:EventMetadata",
                        "purchaseOrderId:UUID",
                        "number:String",
                        "supplierId:UUID",
                        "currencyCode:String",
                        "subtotal:BigDecimal",
                        "taxTotal:BigDecimal",
                        "total:BigDecimal");
    }

    private static List<String> shape(Class<? extends Record> type) {
        return Arrays.stream(type.getRecordComponents())
                .map(RecordComponent.class::cast)
                .map(c -> c.getName() + ":" + c.getType().getSimpleName())
                .toList();
    }
}
