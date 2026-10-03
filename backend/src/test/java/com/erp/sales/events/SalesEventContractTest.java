package com.erp.sales.events;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Contract snapshots of the Sales events v1 (ARCHITECTURE.md §7). Accounting (Phase 8) books AR
 * entries and open items from these fields; a change needs a new schema version.
 */
class SalesEventContractTest {

    @Test
    void typesAndVersions() {
        assertThat(InvoicePosted.INVOICE_TYPE).isEqualTo("sales.invoice.posted");
        assertThat(InvoicePosted.CREDIT_NOTE_TYPE).isEqualTo("sales.credit_note.posted");
        assertThat(SalesOrderConfirmed.TYPE).isEqualTo("sales.order.confirmed");
        assertThat(SalesOrderCancelled.TYPE).isEqualTo("sales.order.cancelled");
        assertThat(List.of(
                        InvoicePosted.SCHEMA_VERSION,
                        SalesOrderConfirmed.SCHEMA_VERSION,
                        SalesOrderCancelled.SCHEMA_VERSION))
                .containsOnly(1);
    }

    @Test
    void invoicePosted() {
        assertThat(shape(InvoicePosted.class))
                .containsExactly(
                        "metadata:EventMetadata",
                        "invoiceId:UUID",
                        "documentType:String",
                        "number:String",
                        "customerId:UUID",
                        "customerGroupId:UUID",
                        "salesOrderId:UUID",
                        "originalInvoiceId:UUID",
                        "salesReturnId:UUID",
                        "documentDate:LocalDate",
                        "accountingDate:LocalDate",
                        "dueDate:LocalDate",
                        "currencyCode:String",
                        "exchangeRate:BigDecimal",
                        "totals:Totals",
                        "lines:List",
                        "taxLines:List");
        assertThat(shape(InvoicePosted.Totals.class))
                .containsExactly(
                        "subtotal:BigDecimal",
                        "taxTotal:BigDecimal",
                        "total:BigDecimal",
                        "subtotalBase:BigDecimal",
                        "taxTotalBase:BigDecimal",
                        "totalBase:BigDecimal");
        assertThat(shape(InvoicePosted.Line.class))
                .containsExactly(
                        "lineId:UUID",
                        "variantId:UUID",
                        "categoryId:UUID",
                        "salesOrderLineId:UUID",
                        "quantityBase:BigDecimal",
                        "netDoc:BigDecimal",
                        "netBase:BigDecimal",
                        "taxCodeId:UUID",
                        "branchId:UUID",
                        "departmentId:UUID");
        assertThat(shape(InvoicePosted.TaxLine.class))
                .containsExactly(
                        "taxCodeId:UUID",
                        "ratePercent:BigDecimal",
                        "taxableDoc:BigDecimal",
                        "taxDoc:BigDecimal",
                        "taxableBase:BigDecimal",
                        "taxBase:BigDecimal");
    }

    @Test
    void orderEvents() {
        assertThat(shape(SalesOrderConfirmed.class))
                .containsExactly(
                        "metadata:EventMetadata",
                        "salesOrderId:UUID",
                        "number:String",
                        "customerId:UUID",
                        "currencyCode:String",
                        "total:BigDecimal",
                        "totalBase:BigDecimal",
                        "creditCheckResult:String");
        assertThat(shape(SalesOrderCancelled.class))
                .containsExactly(
                        "metadata:EventMetadata",
                        "salesOrderId:UUID",
                        "number:String",
                        "customerId:UUID",
                        "currencyCode:String",
                        "total:BigDecimal",
                        "reason:String");
    }

    private static List<String> shape(Class<? extends Record> type) {
        return Arrays.stream(type.getRecordComponents())
                .map(SalesEventContractTest::describe)
                .toList();
    }

    private static String describe(RecordComponent c) {
        return c.getName() + ":" + c.getType().getSimpleName();
    }
}
