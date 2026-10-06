package com.erp.reporting.persistence;

import static com.erp.db.inventory.tables.VRptProducts.V_RPT_PRODUCTS;
import static com.erp.db.inventory.tables.VRptStockMovements.V_RPT_STOCK_MOVEMENTS;
import static com.erp.db.inventory.tables.VRptStockOnHand.V_RPT_STOCK_ON_HAND;
import static com.erp.db.inventory.tables.VRptStockValuation.V_RPT_STOCK_VALUATION;
import static com.erp.db.inventory.tables.VRptWarehouseStock.V_RPT_WAREHOUSE_STOCK;
import static com.erp.db.inventory.tables.VRptWarehouses.V_RPT_WAREHOUSES;
import static com.erp.db.org.tables.VRptBranches.V_RPT_BRANCHES;
import static com.erp.reporting.persistence.ViewQueries.branchScope;
import static com.erp.reporting.persistence.ViewQueries.daysBetween;
import static com.erp.reporting.persistence.ViewQueries.eq;
import static com.erp.reporting.persistence.ViewQueries.inRange;
import static com.erp.reporting.persistence.ViewQueries.sum;
import static com.erp.reporting.persistence.ViewQueries.sumIf;

import com.erp.reporting.application.ReportCatalog;
import com.erp.reporting.application.ReportScope;
import com.erp.reporting.domain.ReportParameters;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
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
 * Inventory reports over {@code inventory.v_rpt_*}: current balances and valuations, and the stock
 * ledger for as-of figures and movement analyses. Stock and movements are scoped by the warehouse's
 * branch; the company-wide moving-average valuation is not branch-scoped.
 */
@Component
class InventoryViewQueries implements ViewQueries {

    private static final String[] TRANSFERS = {"TRANSFER", "TRANSFER_SHIP", "TRANSFER_RECEIVE"};
    private static final String[] PURCHASES = {"PURCHASE_RECEIPT", "PURCHASE_RETURN"};
    private static final String[] SALES = {"SALES_ISSUE", "SALES_RETURN"};
    private static final String[] ADJUSTMENTS = {"ADJUSTMENT", "SCRAP", "COUNT_ADJUSTMENT"};
    /** Outbound movements that count as usage (transfers only relocate; reversals undo). */
    private static final String[] ISSUES = {"SALES_ISSUE", "PURCHASE_RETURN", "ADJUSTMENT", "SCRAP", "COUNT_ADJUSTMENT"
    };

    private static final String[] RECEIPTS = {
        "PURCHASE_RECEIPT", "SALES_RETURN", "OPENING", "ADJUSTMENT", "COUNT_ADJUSTMENT"
    };

    @Override
    public Map<String, BiFunction<ReportParameters, ReportScope, Select<? extends Record>>> queries() {
        return Map.of(
                "stock-on-hand", this::stockOnHand,
                "stock-valuation", this::valuation,
                "stock-movements", this::movements,
                "slow-moving", this::slowMoving,
                "warehouse-summary", this::warehouses,
                "inventory-adjustments", this::adjustments,
                "inventory-transactions", this::transactions);
    }

    /** The product and category filters on a view that carries product_id and category_id. */
    private static Condition product(Field<UUID> productId, Field<UUID> categoryId, ReportParameters p) {
        return eq(productId, p.id("productId")).and(eq(categoryId, p.id("categoryId")));
    }

    private Select<? extends Record> stockOnHand(ReportParameters p, ReportScope s) {
        boolean byLocation = "LOCATION".equals(p.choice(ReportCatalog.GROUP_BY)) || p.id("locationId") != null;
        LocalDate asOf = p.date(ReportCatalog.AS_OF);
        boolean includeZero = p.flag("includeZero");
        var w = V_RPT_WAREHOUSES;
        var pr = V_RPT_PRODUCTS;
        if (asOf != null) {
            var m = V_RPT_STOCK_MOVEMENTS;
            Field<String> locationCode = byLocation ? m.LOCATION_CODE : DSL.inline("");
            Field<BigDecimal> onHand = sum(m.QUANTITY_BASE);
            Table<?> balances = DSL.select(
                            m.WAREHOUSE_ID.as("warehouse"),
                            locationCode.as("location_code"),
                            m.VARIANT_ID.as("variant"),
                            onHand.as("on_hand"))
                    .from(m)
                    .where(m.COMPANY_ID.eq(s.companyId()))
                    .and(m.TRANSACTION_DATE.le(asOf))
                    .and(eq(m.WAREHOUSE_ID, p.id("warehouseId")))
                    .and(eq(m.LOCATION_ID, p.id("locationId")))
                    .and(branchScope(s, m.BRANCH_ID))
                    .and(product(m.PRODUCT_ID, m.CATEGORY_ID, p))
                    .groupBy(
                            byLocation
                                    ? List.of(m.WAREHOUSE_ID, m.LOCATION_ID, m.LOCATION_CODE, m.VARIANT_ID)
                                    : List.of(m.WAREHOUSE_ID, m.VARIANT_ID))
                    .having(includeZero ? DSL.noCondition() : onHand.ne(BigDecimal.ZERO))
                    .asTable("b");
            Field<UUID> warehouse = balances.field("warehouse", UUID.class);
            Field<UUID> variant = balances.field("variant", UUID.class);
            return DSL.select(
                            warehouse.as("warehouseId"),
                            w.WAREHOUSE_CODE.as("warehouseCode"),
                            balances.field("location_code", String.class).as("locationCode"),
                            variant.as("variantId"),
                            pr.SKU.as("sku"),
                            pr.PRODUCT_NAME.as("productName"),
                            pr.CATEGORY_CODE.as("categoryCode"),
                            pr.UOM_CODE.as("uomCode"),
                            balances.field("on_hand", BigDecimal.class).as("onHand"),
                            DSL.castNull(SQLDataType.NUMERIC).as("reserved"),
                            DSL.castNull(SQLDataType.NUMERIC).as("available"))
                    .from(balances)
                    .join(w)
                    .on(w.COMPANY_ID.eq(s.companyId()))
                    .and(w.WAREHOUSE_ID.eq(warehouse))
                    .join(pr)
                    .on(pr.COMPANY_ID.eq(s.companyId()))
                    .and(pr.VARIANT_ID.eq(variant));
        }
        if (byLocation) {
            var b = V_RPT_STOCK_ON_HAND;
            return DSL.select(
                            b.WAREHOUSE_ID.as("warehouseId"),
                            w.WAREHOUSE_CODE.as("warehouseCode"),
                            b.LOCATION_CODE.as("locationCode"),
                            b.VARIANT_ID.as("variantId"),
                            b.SKU.as("sku"),
                            b.PRODUCT_NAME.as("productName"),
                            b.CATEGORY_CODE.as("categoryCode"),
                            b.UOM_CODE.as("uomCode"),
                            b.ON_HAND.as("onHand"),
                            DSL.castNull(SQLDataType.NUMERIC).as("reserved"),
                            DSL.castNull(SQLDataType.NUMERIC).as("available"))
                    .from(b)
                    .join(w)
                    .on(w.COMPANY_ID.eq(b.COMPANY_ID))
                    .and(w.WAREHOUSE_ID.eq(b.WAREHOUSE_ID))
                    .where(b.COMPANY_ID.eq(s.companyId()))
                    .and(eq(b.WAREHOUSE_ID, p.id("warehouseId")))
                    .and(eq(b.LOCATION_ID, p.id("locationId")))
                    .and(branchScope(s, b.BRANCH_ID))
                    .and(product(b.PRODUCT_ID, b.CATEGORY_ID, p))
                    .and(includeZero ? DSL.noCondition() : b.ON_HAND.ne(BigDecimal.ZERO));
        }
        var ws = V_RPT_WAREHOUSE_STOCK;
        return DSL.select(
                        ws.WAREHOUSE_ID.as("warehouseId"),
                        w.WAREHOUSE_CODE.as("warehouseCode"),
                        DSL.inline("").as("locationCode"),
                        ws.VARIANT_ID.as("variantId"),
                        ws.SKU.as("sku"),
                        ws.PRODUCT_NAME.as("productName"),
                        ws.CATEGORY_CODE.as("categoryCode"),
                        ws.UOM_CODE.as("uomCode"),
                        ws.ON_HAND.as("onHand"),
                        ws.RESERVED.as("reserved"),
                        ws.AVAILABLE.as("available"))
                .from(ws)
                .join(w)
                .on(w.COMPANY_ID.eq(ws.COMPANY_ID))
                .and(w.WAREHOUSE_ID.eq(ws.WAREHOUSE_ID))
                .where(ws.COMPANY_ID.eq(s.companyId()))
                .and(eq(ws.WAREHOUSE_ID, p.id("warehouseId")))
                .and(branchScope(s, ws.BRANCH_ID))
                .and(product(ws.PRODUCT_ID, ws.CATEGORY_ID, p))
                .and(
                        includeZero
                                ? DSL.noCondition()
                                : ws.ON_HAND.ne(BigDecimal.ZERO).or(ws.RESERVED.ne(BigDecimal.ZERO)));
    }

    private Select<? extends Record> valuation(ReportParameters p, ReportScope s) {
        LocalDate asOf = p.date(ReportCatalog.AS_OF);
        Table<?> values;
        if (asOf == null) {
            var v = V_RPT_STOCK_VALUATION;
            values = DSL.select(v.VARIANT_ID.as("variant"), v.QTY.as("qty"), v.TOTAL_VALUE_BASE.as("value"))
                    .from(v)
                    .where(v.COMPANY_ID.eq(s.companyId()))
                    .and(v.QTY.ne(BigDecimal.ZERO).or(v.TOTAL_VALUE_BASE.ne(BigDecimal.ZERO)))
                    .asTable("v");
        } else {
            // The ledger as of the date, as InventoryFacade/StockRepository.valuationsAsOf computes it.
            var m = V_RPT_STOCK_MOVEMENTS;
            Field<BigDecimal> qty = sum(m.QUANTITY_BASE);
            Field<BigDecimal> value = sum(m.VALUE_BASE);
            values = DSL.select(m.VARIANT_ID.as("variant"), qty.as("qty"), value.as("value"))
                    .from(m)
                    .where(m.COMPANY_ID.eq(s.companyId()))
                    .and(m.TRANSACTION_DATE.le(asOf))
                    .groupBy(m.VARIANT_ID)
                    .having(qty.ne(BigDecimal.ZERO).or(value.ne(BigDecimal.ZERO)))
                    .asTable("v");
        }
        var pr = V_RPT_PRODUCTS;
        Field<UUID> variant = values.field("variant", UUID.class);
        Field<BigDecimal> qty = values.field("qty", BigDecimal.class);
        Field<BigDecimal> value = values.field("value", BigDecimal.class);
        Condition filter = pr.COMPANY_ID.eq(s.companyId()).and(product(pr.PRODUCT_ID, pr.CATEGORY_ID, p));
        if ("CATEGORY".equals(p.choice(ReportCatalog.GROUP_BY))) {
            return DSL.select(
                            pr.CATEGORY_ID.as("groupId"),
                            pr.CATEGORY_CODE.as("groupCode"),
                            pr.CATEGORY_NAME.as("groupName"),
                            DSL.castNull(SQLDataType.VARCHAR).as("uomCode"),
                            sum(qty).as("quantityBase"),
                            sum(value).as("valueBase"),
                            DSL.castNull(SQLDataType.NUMERIC).as("averageCostBase"))
                    .from(values)
                    .join(pr)
                    .on(pr.VARIANT_ID.eq(variant))
                    .where(filter)
                    .groupBy(pr.CATEGORY_ID, pr.CATEGORY_CODE, pr.CATEGORY_NAME);
        }
        return DSL.select(
                        variant.as("groupId"),
                        pr.SKU.as("groupCode"),
                        pr.PRODUCT_NAME.as("groupName"),
                        pr.UOM_CODE.as("uomCode"),
                        qty.as("quantityBase"),
                        value.as("valueBase"),
                        DSL.when(qty.ne(BigDecimal.ZERO), DSL.round(value.div(qty), 6))
                                .else_(DSL.castNull(SQLDataType.NUMERIC))
                                .as("averageCostBase"))
                .from(values)
                .join(pr)
                .on(pr.VARIANT_ID.eq(variant))
                .where(filter);
    }

    private Select<? extends Record> movements(ReportParameters p, ReportScope s) {
        var m = V_RPT_STOCK_MOVEMENTS;
        LocalDate from = p.requireDate(ReportCatalog.FROM);
        Condition inPeriod = m.TRANSACTION_DATE.ge(from);
        Field<String> type = m.EFFECTIVE_MOVEMENT_TYPE;
        Field<BigDecimal> opening = sumIf(m.QUANTITY_BASE, m.TRANSACTION_DATE.lt(from));
        Table<?> totals = DSL.select(
                        m.WAREHOUSE_ID.as("warehouse"),
                        m.VARIANT_ID.as("variant"),
                        opening.as("opening"),
                        sumIf(m.QUANTITY_BASE, inPeriod.and(type.eq("OPENING"))).as("opening_entries"),
                        sumIf(m.QUANTITY_BASE, inPeriod.and(type.in(PURCHASES))).as("purchases"),
                        sumIf(m.QUANTITY_BASE, inPeriod.and(type.in(SALES))).as("sales"),
                        sumIf(m.QUANTITY_BASE, inPeriod.and(type.in(TRANSFERS))).as("transfers"),
                        sumIf(m.QUANTITY_BASE, inPeriod.and(type.in(ADJUSTMENTS)))
                                .as("adjustments"),
                        sum(m.QUANTITY_BASE).as("closing"))
                .from(m)
                .where(m.COMPANY_ID.eq(s.companyId()))
                .and(m.TRANSACTION_DATE.le(p.requireDate(ReportCatalog.TO)))
                .and(eq(m.WAREHOUSE_ID, p.id("warehouseId")))
                .and(branchScope(s, m.BRANCH_ID))
                .and(product(m.PRODUCT_ID, m.CATEGORY_ID, p))
                .groupBy(m.WAREHOUSE_ID, m.VARIANT_ID)
                .having(opening.ne(BigDecimal.ZERO)
                        .or(DSL.count().filterWhere(inPeriod).gt(0)))
                .asTable("t");
        var w = V_RPT_WAREHOUSES;
        var pr = V_RPT_PRODUCTS;
        return DSL.select(
                        w.WAREHOUSE_CODE.as("warehouseCode"),
                        pr.SKU.as("sku"),
                        pr.PRODUCT_NAME.as("productName"),
                        pr.UOM_CODE.as("uomCode"),
                        totals.field("opening").as("openingQuantity"),
                        totals.field("opening_entries").as("openingEntriesQuantity"),
                        totals.field("purchases").as("purchasesQuantity"),
                        totals.field("sales").as("salesQuantity"),
                        totals.field("transfers").as("transfersQuantity"),
                        totals.field("adjustments").as("adjustmentsQuantity"),
                        totals.field("closing").as("closingQuantity"))
                .from(totals)
                .join(w)
                .on(w.COMPANY_ID.eq(s.companyId()))
                .and(w.WAREHOUSE_ID.eq(totals.field("warehouse", UUID.class)))
                .join(pr)
                .on(pr.COMPANY_ID.eq(s.companyId()))
                .and(pr.VARIANT_ID.eq(totals.field("variant", UUID.class)));
    }

    private Select<? extends Record> slowMoving(ReportParameters p, ReportScope s) {
        LocalDate asOf = p.requireDate(ReportCatalog.AS_OF);
        LocalDate threshold = asOf.minusDays(p.integer("days"));
        var m = V_RPT_STOCK_MOVEMENTS;
        // Only ledger columns: the view's joins are dropped and the ledger index answers the query.
        Table<?> last = DSL.select(
                        m.WAREHOUSE_ID.as("warehouse"),
                        m.VARIANT_ID.as("variant"),
                        DSL.max(m.TRANSACTION_DATE)
                                .filterWhere(m.QUANTITY_BASE.gt(BigDecimal.ZERO).and(m.MOVEMENT_TYPE.in(RECEIPTS)))
                                .as("last_receipt"),
                        DSL.max(m.TRANSACTION_DATE)
                                .filterWhere(m.QUANTITY_BASE.lt(BigDecimal.ZERO).and(m.MOVEMENT_TYPE.in(ISSUES)))
                                .as("last_issue"))
                .from(m)
                .where(m.COMPANY_ID.eq(s.companyId()))
                .and(m.TRANSACTION_DATE.le(asOf))
                .and(eq(m.WAREHOUSE_ID, p.id("warehouseId")))
                .groupBy(m.WAREHOUSE_ID, m.VARIANT_ID)
                .asTable("l");
        var ws = V_RPT_WAREHOUSE_STOCK;
        var w = V_RPT_WAREHOUSES;
        Field<LocalDate> lastIssue = last.field("last_issue", LocalDate.class);
        return DSL.select(
                        w.WAREHOUSE_CODE.as("warehouseCode"),
                        ws.SKU.as("sku"),
                        ws.PRODUCT_NAME.as("productName"),
                        ws.CATEGORY_CODE.as("categoryCode"),
                        ws.UOM_CODE.as("uomCode"),
                        ws.ON_HAND.as("onHand"),
                        last.field("last_receipt", LocalDate.class).as("lastReceiptDate"),
                        lastIssue.as("lastIssueDate"),
                        daysBetween(DSL.val(asOf), lastIssue).as("daysSinceLastIssue"))
                .from(ws)
                .join(w)
                .on(w.COMPANY_ID.eq(ws.COMPANY_ID))
                .and(w.WAREHOUSE_ID.eq(ws.WAREHOUSE_ID))
                .leftJoin(last)
                .on(last.field("warehouse", UUID.class).eq(ws.WAREHOUSE_ID))
                .and(last.field("variant", UUID.class).eq(ws.VARIANT_ID))
                .where(ws.COMPANY_ID.eq(s.companyId()))
                .and(ws.ON_HAND.gt(BigDecimal.ZERO))
                .and(lastIssue.isNull().or(lastIssue.lt(threshold)))
                .and(eq(ws.WAREHOUSE_ID, p.id("warehouseId")))
                .and(eq(ws.CATEGORY_ID, p.id("categoryId")))
                .and(branchScope(s, ws.BRANCH_ID));
    }

    private Select<? extends Record> warehouses(ReportParameters p, ReportScope s) {
        var ws = V_RPT_WAREHOUSE_STOCK;
        Table<?> stock = DSL.select(
                        ws.WAREHOUSE_ID.as("warehouse"),
                        DSL.count().filterWhere(ws.ON_HAND.ne(BigDecimal.ZERO)).as("skus"),
                        sum(ws.ON_HAND).as("on_hand"),
                        sum(ws.RESERVED).as("reserved"))
                .from(ws)
                .where(ws.COMPANY_ID.eq(s.companyId()))
                .groupBy(ws.WAREHOUSE_ID)
                .asTable("st");
        var m = V_RPT_STOCK_MOVEMENTS;
        Table<?> moves = DSL.select(
                        m.WAREHOUSE_ID.as("warehouse"),
                        sumIf(m.QUANTITY_BASE, m.QUANTITY_BASE.gt(BigDecimal.ZERO))
                                .as("inbound"),
                        sumIf(m.QUANTITY_BASE, m.QUANTITY_BASE.lt(BigDecimal.ZERO))
                                .neg()
                                .as("outbound"),
                        DSL.countDistinct(m.MOVEMENT_ID).as("movements"))
                .from(m)
                .where(m.COMPANY_ID.eq(s.companyId()))
                .and(inRange(m.TRANSACTION_DATE, p))
                .groupBy(m.WAREHOUSE_ID)
                .asTable("mv");
        var w = V_RPT_WAREHOUSES;
        var b = V_RPT_BRANCHES;
        return DSL.select(
                        w.WAREHOUSE_ID.as("warehouseId"),
                        w.WAREHOUSE_CODE.as("warehouseCode"),
                        w.WAREHOUSE_NAME.as("warehouseName"),
                        b.BRANCH_CODE.as("branchCode"),
                        DSL.coalesce(stock.field("skus", Integer.class), 0).as("skuCount"),
                        DSL.coalesce(stock.field("on_hand", BigDecimal.class), BigDecimal.ZERO)
                                .as("onHandQuantity"),
                        DSL.coalesce(stock.field("reserved", BigDecimal.class), BigDecimal.ZERO)
                                .as("reservedQuantity"),
                        DSL.coalesce(moves.field("inbound", BigDecimal.class), BigDecimal.ZERO)
                                .as("inboundQuantity"),
                        DSL.coalesce(moves.field("outbound", BigDecimal.class), BigDecimal.ZERO)
                                .as("outboundQuantity"),
                        DSL.coalesce(moves.field("movements", Integer.class), 0).as("movementCount"))
                .from(w)
                .leftJoin(b)
                .on(b.COMPANY_ID.eq(w.COMPANY_ID))
                .and(b.BRANCH_ID.eq(w.BRANCH_ID))
                .leftJoin(stock)
                .on(stock.field("warehouse", UUID.class).eq(w.WAREHOUSE_ID))
                .leftJoin(moves)
                .on(moves.field("warehouse", UUID.class).eq(w.WAREHOUSE_ID))
                .where(w.COMPANY_ID.eq(s.companyId()))
                .and(eq(w.WAREHOUSE_ID, p.id("warehouseId")))
                .and(branchScope(s, w.BRANCH_ID));
    }

    private static Select<? extends Record> ledger(ReportParameters p, ReportScope s, Condition condition) {
        var m = V_RPT_STOCK_MOVEMENTS;
        var w = V_RPT_WAREHOUSES;
        return DSL.select(
                        m.TRANSACTION_ID.as("transactionId"),
                        m.TRANSACTION_DATE.as("transactionDate"),
                        m.MOVEMENT_NUMBER.as("movementNumber"),
                        m.MOVEMENT_TYPE.as("movementType"),
                        m.REASON_CODE.as("reasonCode"),
                        m.SOURCE_TYPE.as("sourceType"),
                        m.SOURCE_NUMBER.as("sourceNumber"),
                        w.WAREHOUSE_CODE.as("warehouseCode"),
                        m.LOCATION_CODE.as("locationCode"),
                        m.SKU.as("sku"),
                        m.PRODUCT_NAME.as("productName"),
                        m.QUANTITY_BASE.as("quantityBase"),
                        m.UNIT_COST_BASE.as("unitCostBase"),
                        m.VALUE_BASE.as("valueBase"))
                .from(m)
                .join(w)
                .on(w.COMPANY_ID.eq(m.COMPANY_ID))
                .and(w.WAREHOUSE_ID.eq(m.WAREHOUSE_ID))
                .where(m.COMPANY_ID.eq(s.companyId()))
                .and(inRange(m.TRANSACTION_DATE, p))
                .and(eq(m.WAREHOUSE_ID, p.id("warehouseId")))
                .and(product(m.PRODUCT_ID, m.CATEGORY_ID, p))
                .and(branchScope(s, m.BRANCH_ID))
                .and(condition);
    }

    private Select<? extends Record> adjustments(ReportParameters p, ReportScope s) {
        var m = V_RPT_STOCK_MOVEMENTS;
        return ledger(p, s, m.EFFECTIVE_MOVEMENT_TYPE.in(ADJUSTMENTS).and(eq(m.REASON_CODE_ID, p.id("reasonCodeId"))));
    }

    private Select<? extends Record> transactions(ReportParameters p, ReportScope s) {
        var m = V_RPT_STOCK_MOVEMENTS;
        return ledger(p, s, eq(m.LOCATION_ID, p.id("locationId")).and(eq(m.MOVEMENT_TYPE, p.text("movementType"))));
    }
}
