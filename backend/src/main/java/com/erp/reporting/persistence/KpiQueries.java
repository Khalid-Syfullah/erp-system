package com.erp.reporting.persistence;

import static com.erp.db.accounting.tables.VRptOpenItems.V_RPT_OPEN_ITEMS;
import static com.erp.db.hr.tables.VRptHeadcount.V_RPT_HEADCOUNT;
import static com.erp.db.inventory.tables.VRptProducts.V_RPT_PRODUCTS;
import static com.erp.db.inventory.tables.VRptStockValuation.V_RPT_STOCK_VALUATION;
import static com.erp.db.inventory.tables.VRptWarehouseStock.V_RPT_WAREHOUSE_STOCK;
import static com.erp.db.procurement.tables.VRptPurchaseOrders.V_RPT_PURCHASE_ORDERS;
import static com.erp.db.sales.tables.VRptSalesLines.V_RPT_SALES_LINES;
import static com.erp.reporting.persistence.ViewQueries.branchScope;
import static com.erp.reporting.persistence.ViewQueries.sum;

import com.erp.reporting.application.ReportScope;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.function.Function;
import org.jooq.DSLContext;
import org.jooq.Record2;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Component;

/**
 * The dashboard KPIs (PRODUCT_SPEC.md §13): indexed aggregates over the reporting views, each a
 * single small query on the reporting database. A KPI is a value and, where meaningful, a count.
 */
@Component
public class KpiQueries {

    /** An aggregate figure: an amount (or null) and a count. */
    public record Figure(BigDecimal amount, long count) {}

    /** Open purchase orders (approved, not closed or cancelled) and orders awaiting approval. */
    public record OrderCounts(long open, long pendingApproval) {}

    private final ReportingDatabase database;

    KpiQueries(ReportingDatabase database) {
        this.database = database;
    }

    /** Runs several KPI queries in one read-only snapshot. */
    public <T> T read(Duration timeout, Function<Kpis, T> work) {
        return database.read(timeout, dsl -> work.apply(new Kpis(dsl)));
    }

    /** The KPI queries bound to one reporting transaction. */
    public static final class Kpis {

        private final DSLContext dsl;

        Kpis(DSLContext dsl) {
            this.dsl = dsl;
        }

        /** Net sales (invoices less credit notes) from the first of the month to today, in the branch scope. */
        public Figure salesMonthToDate(ReportScope s) {
            var l = V_RPT_SALES_LINES;
            LocalDate today = s.today();
            return figure(dsl.select(
                            sum(l.NET_AMOUNT_BASE),
                            DSL.countDistinct(l.INVOICE_ID).cast(Long.class))
                    .from(l)
                    .where(l.COMPANY_ID.eq(s.companyId()))
                    .and(l.INVOICE_DATE.between(today.withDayOfMonth(1), today))
                    .and(branchScope(s, l.BRANCH_ID))
                    .fetchSingle());
        }

        /** Open receivables (invoices, not unapplied credits) past their due date. */
        public Figure receivablesOverdue(ReportScope s) {
            var o = V_RPT_OPEN_ITEMS;
            return figure(dsl.select(sum(o.OPEN_AMOUNT_BASE), DSL.count().cast(Long.class))
                    .from(o)
                    .where(o.COMPANY_ID.eq(s.companyId()))
                    .and(o.KIND.eq("RECEIVABLE"))
                    .and(o.STATUS.in("OPEN", "PARTIALLY_SETTLED"))
                    .and(o.OPEN_AMOUNT_BASE.gt(BigDecimal.ZERO))
                    .and(o.DUE_DATE.lt(s.today()))
                    .fetchSingle());
        }

        /** Open payables (bills, not debit notes) due from today to seven days ahead. */
        public Figure payablesDueNextSevenDays(ReportScope s) {
            var o = V_RPT_OPEN_ITEMS;
            return figure(dsl.select(sum(o.OPEN_AMOUNT_BASE), DSL.count().cast(Long.class))
                    .from(o)
                    .where(o.COMPANY_ID.eq(s.companyId()))
                    .and(o.KIND.eq("PAYABLE"))
                    .and(o.STATUS.in("OPEN", "PARTIALLY_SETTLED"))
                    .and(o.OPEN_AMOUNT_BASE.gt(BigDecimal.ZERO))
                    .and(o.DUE_DATE.between(s.today(), s.today().plusDays(7)))
                    .fetchSingle());
        }

        /** The inventory value at the moving average (company-wide, equal to the GL inventory accounts). */
        public Figure stockValue(ReportScope s) {
            var v = V_RPT_STOCK_VALUATION;
            return figure(dsl.select(
                            sum(v.TOTAL_VALUE_BASE),
                            DSL.count().filterWhere(v.QTY.ne(BigDecimal.ZERO)).cast(Long.class))
                    .from(v)
                    .where(v.COMPANY_ID.eq(s.companyId()))
                    .fetchSingle());
        }

        /**
         * Active variants per warehouse with nothing available (on hand minus reserved ≤ 0): out of
         * stock or fully committed. The v1 data model has no reorder points.
         */
        public long lowStockCount(ReportScope s) {
            var w = V_RPT_WAREHOUSE_STOCK;
            var p = V_RPT_PRODUCTS;
            return dsl.selectCount()
                    .from(w)
                    .join(p)
                    .on(p.COMPANY_ID.eq(w.COMPANY_ID))
                    .and(p.VARIANT_ID.eq(w.VARIANT_ID))
                    .where(w.COMPANY_ID.eq(s.companyId()))
                    .and(p.VARIANT_STATUS.eq("ACTIVE"))
                    .and(w.AVAILABLE.le(BigDecimal.ZERO))
                    .and(branchScope(s, w.BRANCH_ID))
                    .fetchSingle()
                    .value1();
        }

        public OrderCounts openPurchaseOrders(ReportScope s) {
            var po = V_RPT_PURCHASE_ORDERS;
            Record2<Integer, Integer> counts = dsl.select(
                            DSL.count().filterWhere(po.STATUS.in("APPROVED", "PARTIALLY_RECEIVED", "RECEIVED")),
                            DSL.count().filterWhere(po.STATUS.eq("PENDING_APPROVAL")))
                    .from(po)
                    .where(po.COMPANY_ID.eq(s.companyId()))
                    .and(branchScope(s, po.BRANCH_ID))
                    .fetchSingle();
            return new OrderCounts(counts.value1(), counts.value2());
        }

        /** Employees employed today, in the branch scope (as the headcount report). */
        public long headcount(ReportScope s) {
            var h = V_RPT_HEADCOUNT;
            return dsl.select(DSL.countDistinct(h.EMPLOYEE_ID))
                    .from(h)
                    .where(h.COMPANY_ID.eq(s.companyId()))
                    .and(HrViewQueries.employedOn(h, DSL.val(s.today())))
                    .and(branchScope(s, h.BRANCH_ID))
                    .fetchSingle()
                    .value1();
        }

        private static Figure figure(Record2<BigDecimal, Long> record) {
            return new Figure(record.value1(), record.value2());
        }
    }
}
