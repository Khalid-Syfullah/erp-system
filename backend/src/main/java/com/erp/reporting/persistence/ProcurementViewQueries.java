package com.erp.reporting.persistence;

import static com.erp.db.accounting.tables.VRptOpenItems.V_RPT_OPEN_ITEMS;
import static com.erp.db.inventory.tables.VRptProducts.V_RPT_PRODUCTS;
import static com.erp.db.inventory.tables.VRptWarehouses.V_RPT_WAREHOUSES;
import static com.erp.db.org.tables.VRptBranches.V_RPT_BRANCHES;
import static com.erp.db.partners.tables.VRptPartners.V_RPT_PARTNERS;
import static com.erp.db.procurement.tables.VRptPurchaseLines.V_RPT_PURCHASE_LINES;
import static com.erp.db.procurement.tables.VRptPurchaseOrders.V_RPT_PURCHASE_ORDERS;
import static com.erp.db.procurement.tables.VRptReceiptLines.V_RPT_RECEIPT_LINES;
import static com.erp.db.procurement.tables.VRptSupplierBillLines.V_RPT_SUPPLIER_BILL_LINES;
import static com.erp.db.procurement.tables.VRptSupplierBills.V_RPT_SUPPLIER_BILLS;
import static com.erp.reporting.persistence.ViewQueries.branchScope;
import static com.erp.reporting.persistence.ViewQueries.daysBetween;
import static com.erp.reporting.persistence.ViewQueries.eq;
import static com.erp.reporting.persistence.ViewQueries.inRange;
import static com.erp.reporting.persistence.ViewQueries.month;
import static com.erp.reporting.persistence.ViewQueries.nullId;
import static com.erp.reporting.persistence.ViewQueries.percent;
import static com.erp.reporting.persistence.ViewQueries.sum;

import com.erp.reporting.application.ReportCatalog;
import com.erp.reporting.application.ReportScope;
import com.erp.reporting.domain.ReportParameters;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;
import org.jooq.Condition;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Select;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.springframework.stereotype.Component;

/**
 * Procurement reports over {@code procurement.v_rpt_*} (orders, receipts, posted bills) and
 * Accounting's payables (open items).
 */
@Component
class ProcurementViewQueries implements ViewQueries {

    private static final String[] OPEN_ORDER = {"APPROVED", "PARTIALLY_RECEIVED", "RECEIVED"};

    @Override
    public Map<String, BiFunction<ReportParameters, ReportScope, Select<? extends Record>>> queries() {
        return Map.of(
                "purchases", this::purchases,
                "supplier-analysis", this::supplierAnalysis,
                "purchase-orders", this::purchaseOrders,
                "receiving", this::receiving,
                "grni", this::grni,
                "outstanding-supplier-bills", this::outstandingBills);
    }

    private Select<? extends Record> purchases(ReportParameters p, ReportScope s) {
        var l = V_RPT_SUPPLIER_BILL_LINES;
        var sp = V_RPT_PARTNERS;
        var pr = V_RPT_PRODUCTS;
        var b = V_RPT_BRANCHES;
        Field<UUID> id;
        Field<String> code;
        Field<String> name;
        switch (p.choice(ReportCatalog.GROUP_BY)) {
            case "PRODUCT" -> {
                id = l.VARIANT_ID;
                code = pr.SKU;
                name = pr.PRODUCT_NAME;
            }
            case "CATEGORY" -> {
                id = pr.CATEGORY_ID;
                code = pr.CATEGORY_CODE;
                name = pr.CATEGORY_NAME;
            }
            case "BRANCH" -> {
                id = l.BRANCH_ID;
                code = DSL.coalesce(b.BRANCH_CODE, DSL.inline(""));
                name = DSL.coalesce(b.BRANCH_NAME, DSL.inline("No branch"));
            }
            case "MONTH" -> {
                id = nullId();
                code = month(l.BILL_DATE);
                name = month(l.BILL_DATE);
            }
            default -> {
                id = l.SUPPLIER_ID;
                code = sp.PARTNER_CODE;
                name = sp.PARTNER_NAME;
            }
        }
        return DSL.select(
                        id.as("groupId"),
                        code.as("groupCode"),
                        name.as("groupName"),
                        DSL.countDistinct(l.SUPPLIER_BILL_ID).as("billCount"),
                        sum(l.QUANTITY_BASE).as("quantityBase"),
                        sum(l.NET_AMOUNT_BASE).as("netBase"),
                        sum(l.TAX_AMOUNT_BASE).as("taxBase"),
                        sum(l.NET_AMOUNT_BASE.plus(l.TAX_AMOUNT_BASE)).as("grossBase"))
                .from(l)
                .join(sp)
                .on(sp.COMPANY_ID.eq(l.COMPANY_ID))
                .and(sp.PARTNER_ID.eq(l.SUPPLIER_ID))
                .join(pr)
                .on(pr.COMPANY_ID.eq(l.COMPANY_ID))
                .and(pr.VARIANT_ID.eq(l.VARIANT_ID))
                .leftJoin(b)
                .on(b.COMPANY_ID.eq(l.COMPANY_ID))
                .and(b.BRANCH_ID.eq(l.BRANCH_ID))
                .where(l.COMPANY_ID.eq(s.companyId()))
                .and(inRange(l.BILL_DATE, p))
                .and(eq(l.SUPPLIER_ID, p.id("supplierId")))
                .and(eq(l.BRANCH_ID, p.id("branchId")))
                .and(branchScope(s, l.BRANCH_ID))
                .and(SalesViewQueries.variants(l.VARIANT_ID, p, s.companyId()))
                .groupBy(id, code, name);
    }

    private Select<? extends Record> supplierAnalysis(ReportParameters p, ReportScope s) {
        UUID company = s.companyId();
        UUID supplier = p.id("supplierId");
        var po = V_RPT_PURCHASE_ORDERS;
        Table<?> orders = DSL.select(po.SUPPLIER_ID.as("supplier"), DSL.count().as("orders"))
                .from(po)
                .where(po.COMPANY_ID.eq(company))
                .and(po.STATUS.ne("CANCELLED"))
                .and(inRange(po.ORDER_DATE, p))
                .and(eq(po.SUPPLIER_ID, supplier))
                .and(branchScope(s, po.BRANCH_ID))
                .groupBy(po.SUPPLIER_ID)
                .asTable("o");
        var rl = V_RPT_RECEIPT_LINES;
        Condition receiptsInRange = rl.COMPANY_ID
                .eq(company)
                .and(inRange(rl.RECEIPT_DATE, p))
                .and(eq(rl.SUPPLIER_ID, supplier))
                .and(branchScope(s, rl.BRANCH_ID));
        Table<?> receiptValues = DSL.select(
                        rl.SUPPLIER_ID.as("supplier"), sum(rl.VALUE_BASE).as("received"))
                .from(rl)
                .where(receiptsInRange)
                .groupBy(rl.SUPPLIER_ID)
                .asTable("rv");
        Table<?> receipts = DSL.selectDistinct(
                        rl.SUPPLIER_ID.as("supplier"),
                        rl.GOODS_RECEIPT_ID.as("receipt"),
                        rl.RECEIPT_DATE.as("receipt_date"),
                        rl.ORDER_DATE.as("order_date"),
                        rl.EXPECTED_DATE.as("expected_date"))
                .from(rl)
                .where(receiptsInRange)
                .asTable("rd");
        Field<LocalDate> receiptDate = receipts.field("receipt_date", LocalDate.class);
        Field<LocalDate> expected = receipts.field("expected_date", LocalDate.class);
        Field<BigDecimal> onTime = DSL.count()
                .filterWhere(expected.isNotNull().and(receiptDate.le(expected)))
                .cast(SQLDataType.NUMERIC);
        Field<BigDecimal> withExpected =
                DSL.count().filterWhere(expected.isNotNull()).cast(SQLDataType.NUMERIC);
        Table<?> receiptStats = DSL.select(
                        receipts.field("supplier", UUID.class).as("supplier"),
                        DSL.count().as("receipts"),
                        percent(onTime, withExpected).as("on_time"),
                        DSL.round(DSL.avg(daysBetween(receiptDate, receipts.field("order_date", LocalDate.class))), 1)
                                .as("lead_time"))
                .from(receipts)
                .groupBy(receipts.field("supplier"))
                .asTable("rs");
        var bl = V_RPT_SUPPLIER_BILL_LINES;
        Table<?> bills = DSL.select(
                        bl.SUPPLIER_ID.as("supplier"), sum(bl.NET_AMOUNT_BASE).as("billed"))
                .from(bl)
                .where(bl.COMPANY_ID.eq(company))
                .and(inRange(bl.BILL_DATE, p))
                .and(eq(bl.SUPPLIER_ID, supplier))
                .and(branchScope(s, bl.BRANCH_ID))
                .groupBy(bl.SUPPLIER_ID)
                .asTable("b");
        var oi = V_RPT_OPEN_ITEMS;
        Table<?> payables = DSL.select(
                        oi.PARTNER_ID.as("supplier"), sum(oi.OPEN_AMOUNT_BASE).as("open"))
                .from(oi)
                .where(oi.COMPANY_ID.eq(company))
                .and(oi.KIND.eq("PAYABLE"))
                .and(oi.STATUS.notIn("SETTLED", "VOIDED"))
                .and(eq(oi.PARTNER_ID, supplier))
                .groupBy(oi.PARTNER_ID)
                .asTable("ap");
        var sp = V_RPT_PARTNERS;
        Field<Integer> orderCount = DSL.coalesce(orders.field("orders", Integer.class), 0);
        Field<Integer> receiptCount = DSL.coalesce(receiptStats.field("receipts", Integer.class), 0);
        Field<BigDecimal> billed = DSL.coalesce(bills.field("billed", BigDecimal.class), BigDecimal.ZERO);
        Field<BigDecimal> open = DSL.coalesce(payables.field("open", BigDecimal.class), BigDecimal.ZERO);
        return DSL.select(
                        sp.PARTNER_ID.as("supplierId"),
                        sp.PARTNER_CODE.as("supplierCode"),
                        sp.PARTNER_NAME.as("supplierName"),
                        orderCount.as("orderCount"),
                        receiptCount.as("receiptCount"),
                        DSL.coalesce(receiptValues.field("received", BigDecimal.class), BigDecimal.ZERO)
                                .as("receivedValueBase"),
                        billed.as("billedNetBase"),
                        receiptStats.field("on_time", BigDecimal.class).as("onTimeReceiptPercent"),
                        receiptStats.field("lead_time", BigDecimal.class).as("averageLeadTimeDays"),
                        open.as("openPayablesBase"))
                .from(sp)
                .leftJoin(orders)
                .on(orders.field("supplier", UUID.class).eq(sp.PARTNER_ID))
                .leftJoin(receiptValues)
                .on(receiptValues.field("supplier", UUID.class).eq(sp.PARTNER_ID))
                .leftJoin(receiptStats)
                .on(receiptStats.field("supplier", UUID.class).eq(sp.PARTNER_ID))
                .leftJoin(bills)
                .on(bills.field("supplier", UUID.class).eq(sp.PARTNER_ID))
                .leftJoin(payables)
                .on(payables.field("supplier", UUID.class).eq(sp.PARTNER_ID))
                .where(sp.COMPANY_ID.eq(company))
                .and(eq(sp.PARTNER_ID, supplier))
                .and(orderCount
                        .gt(0)
                        .or(receiptCount.gt(0))
                        .or(billed.ne(BigDecimal.ZERO))
                        .or(open.ne(BigDecimal.ZERO)));
    }

    private Select<? extends Record> purchaseOrders(ReportParameters p, ReportScope s) {
        var po = V_RPT_PURCHASE_ORDERS;
        var pl = V_RPT_PURCHASE_LINES;
        var sp = V_RPT_PARTNERS;
        Table<?> progress = DSL.select(
                        pl.PURCHASE_ORDER_ID.as("po"),
                        sum(pl.QUANTITY_BASE).as("ordered"),
                        sum(pl.RECEIVED_QUANTITY_BASE).as("received"),
                        sum(pl.BILLED_QUANTITY_BASE).as("billed"))
                .from(pl)
                .where(pl.COMPANY_ID.eq(s.companyId()))
                .groupBy(pl.PURCHASE_ORDER_ID)
                .asTable("q");
        Field<BigDecimal> ordered = DSL.coalesce(progress.field("ordered", BigDecimal.class), BigDecimal.ZERO);
        Field<BigDecimal> received = DSL.coalesce(progress.field("received", BigDecimal.class), BigDecimal.ZERO);
        Field<BigDecimal> billed = DSL.coalesce(progress.field("billed", BigDecimal.class), BigDecimal.ZERO);
        return DSL.select(
                        po.PURCHASE_ORDER_ID.as("purchaseOrderId"),
                        po.PO_NUMBER.as("poNumber"),
                        po.ORDER_DATE.as("orderDate"),
                        po.EXPECTED_DATE.as("expectedDate"),
                        sp.PARTNER_CODE.as("supplierCode"),
                        sp.PARTNER_NAME.as("supplierName"),
                        po.STATUS.as("status"),
                        po.BILLING_STATUS.as("billingStatus"),
                        po.CURRENCY_CODE.as("currencyCode"),
                        po.TOTAL.as("total"),
                        ordered.as("orderedQuantityBase"),
                        received.as("receivedQuantityBase"),
                        billed.as("billedQuantityBase"),
                        percent(received, ordered).as("receivedPercent"),
                        percent(billed, ordered).as("billedPercent"))
                .from(po)
                .join(sp)
                .on(sp.COMPANY_ID.eq(po.COMPANY_ID))
                .and(sp.PARTNER_ID.eq(po.SUPPLIER_ID))
                .leftJoin(progress)
                .on(progress.field("po", UUID.class).eq(po.PURCHASE_ORDER_ID))
                .where(po.COMPANY_ID.eq(s.companyId()))
                .and(inRange(po.ORDER_DATE, p))
                .and(eq(po.STATUS, p.text("status")))
                .and(p.flag("openOnly") ? po.STATUS.in(OPEN_ORDER) : DSL.noCondition())
                .and(eq(po.SUPPLIER_ID, p.id("supplierId")))
                .and(eq(po.BRANCH_ID, p.id("branchId")))
                .and(eq(po.WAREHOUSE_ID, p.id("warehouseId")))
                .and(branchScope(s, po.BRANCH_ID));
    }

    private Select<? extends Record> receiving(ReportParameters p, ReportScope s) {
        var rl = V_RPT_RECEIPT_LINES;
        var sp = V_RPT_PARTNERS;
        var pr = V_RPT_PRODUCTS;
        var w = V_RPT_WAREHOUSES;
        return DSL.select(
                        rl.LINE_ID.as("lineId"),
                        rl.RECEIPT_NUMBER.as("receiptNumber"),
                        rl.RECEIPT_DATE.as("receiptDate"),
                        rl.PO_NUMBER.as("poNumber"),
                        sp.PARTNER_CODE.as("supplierCode"),
                        sp.PARTNER_NAME.as("supplierName"),
                        w.WAREHOUSE_CODE.as("warehouseCode"),
                        pr.SKU.as("sku"),
                        pr.PRODUCT_NAME.as("productName"),
                        rl.QUANTITY_BASE.as("quantityBase"),
                        rl.VALUE_BASE.as("valueBase"),
                        rl.BILLED_QUANTITY_BASE.as("billedQuantityBase"),
                        rl.RETURNED_QUANTITY_BASE.as("returnedQuantityBase"),
                        rl.UNBILLED_QUANTITY_BASE.as("unbilledQuantityBase"),
                        rl.GRNI_VALUE_BASE.as("grniValueBase"))
                .from(rl)
                .join(sp)
                .on(sp.COMPANY_ID.eq(rl.COMPANY_ID))
                .and(sp.PARTNER_ID.eq(rl.SUPPLIER_ID))
                .join(pr)
                .on(pr.COMPANY_ID.eq(rl.COMPANY_ID))
                .and(pr.VARIANT_ID.eq(rl.VARIANT_ID))
                .join(w)
                .on(w.COMPANY_ID.eq(rl.COMPANY_ID))
                .and(w.WAREHOUSE_ID.eq(rl.WAREHOUSE_ID))
                .where(rl.COMPANY_ID.eq(s.companyId()))
                .and(inRange(rl.RECEIPT_DATE, p))
                .and(eq(rl.SUPPLIER_ID, p.id("supplierId")))
                .and(eq(rl.WAREHOUSE_ID, p.id("warehouseId")))
                .and(eq(rl.BRANCH_ID, p.id("branchId")))
                .and(branchScope(s, rl.BRANCH_ID))
                .and(SalesViewQueries.variants(rl.VARIANT_ID, p, s.companyId()))
                .and(p.flag("pendingBillingOnly") ? rl.UNBILLED_QUANTITY_BASE.gt(BigDecimal.ZERO) : DSL.noCondition());
    }

    private Select<? extends Record> grni(ReportParameters p, ReportScope s) {
        var rl = V_RPT_RECEIPT_LINES;
        var sp = V_RPT_PARTNERS;
        var pr = V_RPT_PRODUCTS;
        return DSL.select(
                        rl.LINE_ID.as("lineId"),
                        rl.RECEIPT_NUMBER.as("receiptNumber"),
                        rl.RECEIPT_DATE.as("receiptDate"),
                        rl.PO_NUMBER.as("poNumber"),
                        sp.PARTNER_CODE.as("supplierCode"),
                        sp.PARTNER_NAME.as("supplierName"),
                        pr.SKU.as("sku"),
                        pr.PRODUCT_NAME.as("productName"),
                        rl.UNBILLED_QUANTITY_BASE.as("unbilledQuantityBase"),
                        rl.VALUE_BASE.as("valueBase"),
                        rl.BILLED_VALUE_BASE.as("billedValueBase"),
                        rl.RETURNED_VALUE_BASE.as("returnedValueBase"),
                        rl.CREDITED_VALUE_BASE.as("creditedValueBase"),
                        rl.GRNI_VALUE_BASE.as("grniValueBase"))
                .from(rl)
                .join(sp)
                .on(sp.COMPANY_ID.eq(rl.COMPANY_ID))
                .and(sp.PARTNER_ID.eq(rl.SUPPLIER_ID))
                .join(pr)
                .on(pr.COMPANY_ID.eq(rl.COMPANY_ID))
                .and(pr.VARIANT_ID.eq(rl.VARIANT_ID))
                .where(rl.COMPANY_ID.eq(s.companyId()))
                .and(rl.GRNI_VALUE_BASE.ne(BigDecimal.ZERO))
                .and(eq(rl.SUPPLIER_ID, p.id("supplierId")))
                .and(eq(rl.BRANCH_ID, p.id("branchId")))
                .and(eq(rl.WAREHOUSE_ID, p.id("warehouseId")))
                .and(branchScope(s, rl.BRANCH_ID));
    }

    private Select<? extends Record> outstandingBills(ReportParameters p, ReportScope s) {
        var b = V_RPT_SUPPLIER_BILLS;
        var oi = V_RPT_OPEN_ITEMS;
        var sp = V_RPT_PARTNERS;
        LocalDate asOf = p.requireDate(ReportCatalog.AS_OF);
        return DSL.select(
                        b.SUPPLIER_BILL_ID.as("billId"),
                        b.NUMBER.as("number"),
                        b.SUPPLIER_INVOICE_NUMBER.as("supplierInvoiceNumber"),
                        b.DOCUMENT_TYPE.as("documentType"),
                        b.BILL_DATE.as("billDate"),
                        oi.DUE_DATE.as("dueDate"),
                        sp.PARTNER_CODE.as("supplierCode"),
                        sp.PARTNER_NAME.as("supplierName"),
                        b.CURRENCY_CODE.as("currencyCode"),
                        b.TOTAL.as("total"),
                        oi.OPEN_AMOUNT.as("openAmount"),
                        oi.OPEN_AMOUNT_BASE.as("openAmountBase"),
                        // Debit notes (negative) are credits, never overdue.
                        DSL.when(
                                        oi.OPEN_AMOUNT.gt(BigDecimal.ZERO),
                                        DSL.greatest(daysBetween(DSL.val(asOf), oi.DUE_DATE), DSL.inline(0)))
                                .else_(DSL.inline(0))
                                .as("daysOverdue"))
                .from(b)
                .join(oi)
                .on(oi.COMPANY_ID.eq(b.COMPANY_ID))
                .and(oi.KIND.eq("PAYABLE"))
                .and(oi.SOURCE_MODULE.eq("procurement"))
                .and(oi.SOURCE_TYPE.eq(b.DOCUMENT_TYPE))
                .and(oi.SOURCE_ID.eq(b.SUPPLIER_BILL_ID))
                .join(sp)
                .on(sp.COMPANY_ID.eq(b.COMPANY_ID))
                .and(sp.PARTNER_ID.eq(b.SUPPLIER_ID))
                .where(b.COMPANY_ID.eq(s.companyId()))
                .and(oi.OPEN_AMOUNT.ne(BigDecimal.ZERO))
                .and(eq(b.SUPPLIER_ID, p.id("supplierId")))
                .and(
                        p.flag("overdueOnly")
                                ? oi.DUE_DATE.lt(asOf).and(oi.OPEN_AMOUNT.gt(BigDecimal.ZERO))
                                : DSL.noCondition());
    }
}
