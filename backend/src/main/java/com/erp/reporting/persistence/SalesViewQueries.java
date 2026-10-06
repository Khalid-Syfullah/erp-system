package com.erp.reporting.persistence;

import static com.erp.db.accounting.tables.VRptOpenItems.V_RPT_OPEN_ITEMS;
import static com.erp.db.inventory.tables.VRptProducts.V_RPT_PRODUCTS;
import static com.erp.db.inventory.tables.VRptStockMovements.V_RPT_STOCK_MOVEMENTS;
import static com.erp.db.org.tables.VRptBranches.V_RPT_BRANCHES;
import static com.erp.db.partners.tables.VRptPartners.V_RPT_PARTNERS;
import static com.erp.db.sales.tables.VRptInvoices.V_RPT_INVOICES;
import static com.erp.db.sales.tables.VRptOrderBacklog.V_RPT_ORDER_BACKLOG;
import static com.erp.db.sales.tables.VRptSalesLines.V_RPT_SALES_LINES;
import static com.erp.reporting.persistence.ViewQueries.branchScope;
import static com.erp.reporting.persistence.ViewQueries.eq;
import static com.erp.reporting.persistence.ViewQueries.inRange;
import static com.erp.reporting.persistence.ViewQueries.percent;
import static com.erp.reporting.persistence.ViewQueries.sum;
import static com.erp.reporting.persistence.ViewQueries.sumIf;

import com.erp.reporting.application.ReportCatalog;
import com.erp.reporting.application.ReportScope;
import com.erp.reporting.domain.ReportParameters;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Locale;
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
 * Sales reports over {@code sales.v_rpt_*} (posted invoices net of credit notes, the order backlog),
 * Accounting's open items (payment status) and the inventory ledger (cost of goods sold).
 */
@Component
class SalesViewQueries implements ViewQueries {

    @Override
    public Map<String, BiFunction<ReportParameters, ReportScope, Select<? extends Record>>> queries() {
        return Map.of(
                "sales-summary", this::summary,
                "sales-by-customer", this::byCustomer,
                "sales-by-product", this::byProduct,
                "sales-by-branch", this::byBranch,
                "sales-by-period", this::byPeriod,
                "invoice-status", this::invoiceStatus,
                "payment-status", this::paymentStatus,
                "gross-margin", this::grossMargin,
                "order-backlog", this::backlog);
    }

    /** Posted invoice and credit note lines of the period and filters, in the branch scope. */
    static Condition lines(ReportParameters p, ReportScope s) {
        var l = V_RPT_SALES_LINES;
        return l.COMPANY_ID
                .eq(s.companyId())
                .and(inRange(l.INVOICE_DATE, p))
                .and(eq(l.CUSTOMER_ID, p.id("customerId")))
                .and(eq(l.BRANCH_ID, p.id("branchId")))
                .and(branchScope(s, l.BRANCH_ID))
                .and(variants(l.VARIANT_ID, p, s.companyId()));
    }

    /** The product and category filters as a variant subquery. */
    static Condition variants(Field<UUID> variant, ReportParameters p, UUID companyId) {
        UUID product = p.id("productId");
        UUID category = p.id("categoryId");
        if (product == null && category == null) {
            return DSL.noCondition();
        }
        var pr = V_RPT_PRODUCTS;
        return variant.in(DSL.select(pr.VARIANT_ID)
                .from(pr)
                .where(pr.COMPANY_ID.eq(companyId))
                .and(eq(pr.PRODUCT_ID, product))
                .and(eq(pr.CATEGORY_ID, category)));
    }

    private Select<? extends Record> summary(ReportParameters p, ReportScope s) {
        var l = V_RPT_SALES_LINES;
        Condition invoice = l.DOCUMENT_TYPE.eq("INVOICE");
        Condition credit = l.DOCUMENT_TYPE.eq("CREDIT_NOTE");
        return DSL.select(
                        DSL.countDistinct(l.INVOICE_ID).filterWhere(invoice).as("invoiceCount"),
                        DSL.countDistinct(l.INVOICE_ID).filterWhere(credit).as("creditNoteCount"),
                        DSL.countDistinct(l.CUSTOMER_ID).as("customerCount"),
                        sumIf(l.NET_AMOUNT_BASE, invoice).as("invoicedBase"),
                        sumIf(l.NET_AMOUNT_BASE, credit).as("creditedBase"),
                        sum(l.NET_AMOUNT_BASE).as("netSalesBase"),
                        sum(l.TAX_AMOUNT_BASE).as("taxBase"),
                        sum(l.NET_AMOUNT_BASE.plus(l.TAX_AMOUNT_BASE)).as("grossBase"))
                .from(l)
                .where(lines(p, s));
    }

    private Select<? extends Record> byCustomer(ReportParameters p, ReportScope s) {
        var l = V_RPT_SALES_LINES;
        var c = V_RPT_PARTNERS;
        return DSL.select(
                        l.CUSTOMER_ID.as("customerId"),
                        c.PARTNER_CODE.as("customerCode"),
                        c.PARTNER_NAME.as("customerName"),
                        DSL.countDistinct(l.INVOICE_ID).as("invoiceCount"),
                        sum(l.NET_AMOUNT_BASE).as("netSalesBase"),
                        sum(l.TAX_AMOUNT_BASE).as("taxBase"),
                        sum(l.NET_AMOUNT_BASE.plus(l.TAX_AMOUNT_BASE)).as("grossBase"))
                .from(l)
                .join(c)
                .on(c.COMPANY_ID.eq(l.COMPANY_ID))
                .and(c.PARTNER_ID.eq(l.CUSTOMER_ID))
                .where(lines(p, s))
                .groupBy(l.CUSTOMER_ID, c.PARTNER_CODE, c.PARTNER_NAME);
    }

    private Select<? extends Record> byProduct(ReportParameters p, ReportScope s) {
        var l = V_RPT_SALES_LINES;
        var pr = V_RPT_PRODUCTS;
        Field<BigDecimal> qty = sum(l.QUANTITY_BASE);
        Field<BigDecimal> net = sum(l.NET_AMOUNT_BASE);
        return DSL.select(
                        l.VARIANT_ID.as("variantId"),
                        pr.SKU.as("sku"),
                        pr.PRODUCT_NAME.as("productName"),
                        pr.CATEGORY_CODE.as("categoryCode"),
                        pr.UOM_CODE.as("uomCode"),
                        qty.as("quantityBase"),
                        net.as("netSalesBase"),
                        DSL.when(qty.ne(BigDecimal.ZERO), DSL.round(net.div(qty), 4))
                                .else_(DSL.castNull(SQLDataType.NUMERIC))
                                .as("averagePriceBase"))
                .from(l)
                .join(pr)
                .on(pr.COMPANY_ID.eq(l.COMPANY_ID))
                .and(pr.VARIANT_ID.eq(l.VARIANT_ID))
                .where(lines(p, s))
                .groupBy(l.VARIANT_ID, pr.SKU, pr.PRODUCT_NAME, pr.CATEGORY_CODE, pr.UOM_CODE);
    }

    private Select<? extends Record> byBranch(ReportParameters p, ReportScope s) {
        var l = V_RPT_SALES_LINES;
        var b = V_RPT_BRANCHES;
        return DSL.select(
                        l.BRANCH_ID.as("branchId"),
                        DSL.coalesce(b.BRANCH_CODE, DSL.inline("")).as("branchCode"),
                        DSL.coalesce(b.BRANCH_NAME, DSL.inline("No branch")).as("branchName"),
                        DSL.countDistinct(l.INVOICE_ID).as("invoiceCount"),
                        sum(l.NET_AMOUNT_BASE).as("netSalesBase"),
                        sum(l.TAX_AMOUNT_BASE).as("taxBase"),
                        sum(l.NET_AMOUNT_BASE.plus(l.TAX_AMOUNT_BASE)).as("grossBase"))
                .from(l)
                .leftJoin(b)
                .on(b.COMPANY_ID.eq(l.COMPANY_ID))
                .and(b.BRANCH_ID.eq(l.BRANCH_ID))
                .where(lines(p, s))
                .groupBy(l.BRANCH_ID, b.BRANCH_CODE, b.BRANCH_NAME);
    }

    private Select<? extends Record> byPeriod(ReportParameters p, ReportScope s) {
        var l = V_RPT_SALES_LINES;
        // The unit comes from the parameter's allowlist (DAY, WEEK, MONTH), never from free text.
        String unit = p.choice("granularity").toLowerCase(Locale.ROOT);
        Field<LocalDate> period =
                DSL.field("date_trunc({0}, {1})::date", SQLDataType.LOCALDATE, DSL.inline(unit), l.INVOICE_DATE);
        return DSL.select(
                        period.as("periodStart"),
                        DSL.countDistinct(l.INVOICE_ID).as("invoiceCount"),
                        sum(l.NET_AMOUNT_BASE).as("netSalesBase"),
                        sum(l.TAX_AMOUNT_BASE).as("taxBase"),
                        sum(l.NET_AMOUNT_BASE.plus(l.TAX_AMOUNT_BASE)).as("grossBase"))
                .from(l)
                .where(lines(p, s))
                .groupBy(period);
    }

    /**
     * UNPAID, PARTIALLY_PAID, OVERDUE (open and past due; credit notes never are), PAID or
     * NOT_APPLICABLE (not posted).
     */
    private static Field<String> paymentStatus(LocalDate asOf) {
        var i = V_RPT_INVOICES;
        var o = V_RPT_OPEN_ITEMS;
        return DSL.when(i.STATUS.ne("POSTED").or(o.OPEN_ITEM_ID.isNull()), DSL.inline("NOT_APPLICABLE"))
                .when(o.OPEN_AMOUNT.eq(BigDecimal.ZERO), DSL.inline("PAID"))
                .when(o.DUE_DATE.lt(asOf).and(o.OPEN_AMOUNT.gt(BigDecimal.ZERO)), DSL.inline("OVERDUE"))
                .when(o.OPEN_AMOUNT.ne(o.ORIGINAL_AMOUNT), DSL.inline("PARTIALLY_PAID"))
                .else_(DSL.inline("UNPAID"));
    }

    private static Table<?> invoicesWithOpenItems() {
        var i = V_RPT_INVOICES;
        var o = V_RPT_OPEN_ITEMS;
        return i.leftJoin(o)
                .on(o.COMPANY_ID.eq(i.COMPANY_ID))
                .and(o.SOURCE_MODULE.eq("sales"))
                .and(o.SOURCE_TYPE.eq(i.DOCUMENT_TYPE))
                .and(o.SOURCE_ID.eq(i.INVOICE_ID));
    }

    private Select<? extends Record> invoiceStatus(ReportParameters p, ReportScope s) {
        var i = V_RPT_INVOICES;
        var o = V_RPT_OPEN_ITEMS;
        var c = V_RPT_PARTNERS;
        LocalDate asOf = p.requireDate(ReportCatalog.AS_OF);
        Field<String> status = paymentStatus(asOf);
        Field<BigDecimal> sign = DSL.when(i.DOCUMENT_TYPE.eq("CREDIT_NOTE"), BigDecimal.ONE.negate())
                .else_(BigDecimal.ONE);
        return DSL.select(
                        i.INVOICE_ID.as("invoiceId"),
                        i.NUMBER.as("number"),
                        i.DOCUMENT_TYPE.as("documentType"),
                        i.INVOICE_DATE.as("invoiceDate"),
                        i.DUE_DATE.as("dueDate"),
                        c.PARTNER_CODE.as("customerCode"),
                        c.PARTNER_NAME.as("customerName"),
                        i.STATUS.as("status"),
                        i.CURRENCY_CODE.as("currencyCode"),
                        i.TOTAL.mul(sign).as("total"),
                        DSL.coalesce(i.TOTAL_BASE, BigDecimal.ZERO).mul(sign).as("totalBase"),
                        DSL.coalesce(o.OPEN_AMOUNT_BASE, BigDecimal.ZERO).as("openAmountBase"),
                        status.as("paymentStatus"),
                        DSL.when(status.eq("OVERDUE"), ViewQueries.daysBetween(DSL.val(asOf), o.DUE_DATE))
                                .else_(0)
                                .as("daysOverdue"))
                .from(invoicesWithOpenItems())
                .join(c)
                .on(c.COMPANY_ID.eq(i.COMPANY_ID))
                .and(c.PARTNER_ID.eq(i.CUSTOMER_ID))
                .where(i.COMPANY_ID.eq(s.companyId()))
                .and(inRange(i.INVOICE_DATE, p))
                .and(eq(i.CUSTOMER_ID, p.id("customerId")))
                .and(eq(i.STATUS, p.text("status")))
                .and(eq(i.DOCUMENT_TYPE, p.text("documentType")))
                .and(p.text("paymentStatus") == null ? DSL.noCondition() : status.eq(p.text("paymentStatus")));
    }

    private Select<? extends Record> paymentStatus(ReportParameters p, ReportScope s) {
        var i = V_RPT_INVOICES;
        var o = V_RPT_OPEN_ITEMS;
        Table<?> invoices = DSL.select(
                        paymentStatus(p.requireDate(ReportCatalog.AS_OF)).as("status"),
                        i.INVOICE_ID.as("invoice"),
                        DSL.coalesce(i.TOTAL_BASE, BigDecimal.ZERO).as("total"),
                        DSL.coalesce(o.OPEN_AMOUNT_BASE, BigDecimal.ZERO).as("open"))
                .from(invoicesWithOpenItems())
                .where(i.COMPANY_ID.eq(s.companyId()))
                .and(i.STATUS.eq("POSTED"))
                .and(i.DOCUMENT_TYPE.eq("INVOICE"))
                .and(inRange(i.INVOICE_DATE, p))
                .and(eq(i.CUSTOMER_ID, p.id("customerId")))
                .asTable("x");
        Field<String> status = invoices.field("status", String.class);
        return DSL.select(
                        status.as("paymentStatus"),
                        DSL.count().as("invoiceCount"),
                        sum(invoices.field("total", BigDecimal.class)).as("totalBase"),
                        sum(invoices.field("open", BigDecimal.class)).as("openAmountBase"))
                .from(invoices)
                .groupBy(status);
    }

    private Select<? extends Record> grossMargin(ReportParameters p, ReportScope s) {
        var l = V_RPT_SALES_LINES;
        var m = V_RPT_STOCK_MOVEMENTS;
        var pr = V_RPT_PRODUCTS;
        UUID branch = p.id("branchId");
        Table<?> revenue = DSL.select(
                        l.VARIANT_ID.as("variant"),
                        sum(l.QUANTITY_BASE).as("qty"),
                        sum(l.NET_AMOUNT_BASE).as("revenue"))
                .from(l)
                .where(lines(p, s))
                .groupBy(l.VARIANT_ID)
                .asTable("rev");
        // Cost of goods sold: the value of sales issues and returns (and their reversals) leaving stock.
        Table<?> cogs = DSL.select(
                        m.VARIANT_ID.as("variant"), sum(m.VALUE_BASE).neg().as("cogs"))
                .from(m)
                .where(m.COMPANY_ID.eq(s.companyId()))
                .and(m.TRANSACTION_DATE.between(p.requireDate(ReportCatalog.FROM), p.requireDate(ReportCatalog.TO)))
                .and(m.EFFECTIVE_MOVEMENT_TYPE.in("SALES_ISSUE", "SALES_RETURN"))
                .and(eq(m.BRANCH_ID, branch))
                .and(branchScope(s, m.BRANCH_ID))
                .and(variants(m.VARIANT_ID, p, s.companyId()))
                .groupBy(m.VARIANT_ID)
                .asTable("cogs");
        Field<UUID> revenueVariant = revenue.field("variant", UUID.class);
        Field<UUID> cogsVariant = cogs.field("variant", UUID.class);
        boolean byCategory = "CATEGORY".equals(p.choice(ReportCatalog.GROUP_BY));
        Field<UUID> groupId = byCategory ? pr.CATEGORY_ID : pr.VARIANT_ID;
        Field<String> groupCode = byCategory ? pr.CATEGORY_CODE : pr.SKU;
        Field<String> groupName = byCategory ? pr.CATEGORY_NAME : pr.PRODUCT_NAME;
        Field<BigDecimal> revenueTotal = sum(DSL.coalesce(revenue.field("revenue", BigDecimal.class), BigDecimal.ZERO));
        Field<BigDecimal> cogsTotal = sum(DSL.coalesce(cogs.field("cogs", BigDecimal.class), BigDecimal.ZERO));
        Field<BigDecimal> margin = revenueTotal.minus(cogsTotal);
        return DSL.select(
                        groupId.as("groupId"),
                        groupCode.as("groupCode"),
                        groupName.as("groupName"),
                        sum(DSL.coalesce(revenue.field("qty", BigDecimal.class), BigDecimal.ZERO))
                                .as("quantityBase"),
                        revenueTotal.as("revenueBase"),
                        cogsTotal.as("cogsBase"),
                        margin.as("marginBase"),
                        percent(margin, revenueTotal).as("marginPercent"))
                .from(revenue)
                .fullJoin(cogs)
                .on(cogsVariant.eq(revenueVariant))
                .join(pr)
                .on(pr.COMPANY_ID.eq(s.companyId()))
                .and(pr.VARIANT_ID.eq(DSL.coalesce(revenueVariant, cogsVariant)))
                .groupBy(groupId, groupCode, groupName);
    }

    private Select<? extends Record> backlog(ReportParameters p, ReportScope s) {
        var b = V_RPT_ORDER_BACKLOG;
        var c = V_RPT_PARTNERS;
        var pr = V_RPT_PRODUCTS;
        return DSL.select(
                        b.LINE_ID.as("lineId"),
                        b.ORDER_NUMBER.as("orderNumber"),
                        b.ORDER_DATE.as("orderDate"),
                        b.REQUESTED_DATE.as("requestedDate"),
                        c.PARTNER_CODE.as("customerCode"),
                        c.PARTNER_NAME.as("customerName"),
                        pr.SKU.as("sku"),
                        pr.PRODUCT_NAME.as("productName"),
                        b.STATUS.as("status"),
                        b.QUANTITY_BASE.as("quantityBase"),
                        b.DELIVERED_QUANTITY_BASE.as("deliveredQuantityBase"),
                        b.INVOICED_QUANTITY_BASE.as("invoicedQuantityBase"),
                        b.UNDELIVERED_QUANTITY_BASE.as("undeliveredQuantityBase"),
                        b.UNINVOICED_DELIVERED_QUANTITY_BASE.as("uninvoicedDeliveredQuantityBase"),
                        b.CURRENCY_CODE.as("currencyCode"),
                        b.UNDELIVERED_NET_AMOUNT.as("undeliveredNetAmount"))
                .from(b)
                .join(c)
                .on(c.COMPANY_ID.eq(b.COMPANY_ID))
                .and(c.PARTNER_ID.eq(b.CUSTOMER_ID))
                .join(pr)
                .on(pr.COMPANY_ID.eq(b.COMPANY_ID))
                .and(pr.VARIANT_ID.eq(b.VARIANT_ID))
                .where(b.COMPANY_ID.eq(s.companyId()))
                .and(b.UNDELIVERED_QUANTITY_BASE
                        .gt(BigDecimal.ZERO)
                        .or(b.UNINVOICED_DELIVERED_QUANTITY_BASE.gt(BigDecimal.ZERO)))
                .and(inRange(b.ORDER_DATE, p))
                .and(eq(b.CUSTOMER_ID, p.id("customerId")))
                .and(eq(b.BRANCH_ID, p.id("branchId")))
                .and(eq(b.WAREHOUSE_ID, p.id("warehouseId")))
                .and(branchScope(s, b.BRANCH_ID))
                .and(variants(b.VARIANT_ID, p, s.companyId()));
    }
}
