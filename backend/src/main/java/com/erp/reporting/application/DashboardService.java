package com.erp.reporting.application;

import com.erp.org.api.OrgFacade;
import com.erp.platform.web.ApiException;
import com.erp.reporting.ReportingPermissions;
import com.erp.reporting.persistence.KpiQueries;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Role dashboards with KPI widgets (PRODUCT_SPEC.md §13, API.md §17.11): sales month to date, AR
 * overdue, AP due in the next seven days, stock value, low-stock count, open purchase orders and
 * headcount. Each widget has its own permission; widgets the caller may not see are omitted, never
 * computed. All widgets of a dashboard are read in one snapshot of the reporting database. Values
 * are not cached: they are cheap indexed aggregates, and a cache would show stale figures right
 * after a posting.
 */
@Service
public class DashboardService {

    /** A KPI: an amount (base currency) and/or a count, with the report that details it. */
    public record Widget(
            String code,
            String label,
            @Nullable BigDecimal amount,
            @Nullable String currencyCode,
            @Nullable Long count,
            @Nullable Long secondaryCount,
            @Nullable String reportCode) {}

    public record Dashboard(
            String code, String name, LocalDate asOf, List<Widget> widgets, OffsetDateTime generatedAt) {}

    public record DashboardSummary(String code, String name, int visibleWidgets) {}

    private record WidgetDefinition(
            String code,
            String label,
            List<String> permissions,
            @Nullable String reportCode,
            BiFunction<KpiQueries.Kpis, Context, Widget> compute) {}

    private record Context(ReportScope scope, String currency) {}

    private record DashboardDefinition(String code, String name, List<String> widgets) {}

    private static final Map<String, WidgetDefinition> WIDGETS = new LinkedHashMap<>();
    private static final Map<String, DashboardDefinition> DASHBOARDS = new LinkedHashMap<>();

    static {
        widget(
                "sales.mtd",
                "Sales month to date",
                List.of(ReportingPermissions.SALES_READ),
                "sales-by-period",
                (k, c) -> {
                    KpiQueries.Figure f = k.salesMonthToDate(c.scope());
                    return new Widget(
                            "sales.mtd",
                            "Sales month to date",
                            f.amount(),
                            c.currency(),
                            f.count(),
                            null,
                            "sales-by-period");
                });
        widget(
                "ar.overdue",
                "Receivables overdue",
                List.of(ReportingPermissions.ACCOUNTING_AR_READ),
                "ar-ageing",
                (k, c) -> {
                    KpiQueries.Figure f = k.receivablesOverdue(c.scope());
                    return new Widget(
                            "ar.overdue",
                            "Receivables overdue",
                            f.amount(),
                            c.currency(),
                            f.count(),
                            null,
                            "ar-ageing");
                });
        widget(
                "ap.due_7_days",
                "Payables due in 7 days",
                List.of(ReportingPermissions.ACCOUNTING_AP_READ),
                "ap-ageing",
                (k, c) -> {
                    KpiQueries.Figure f = k.payablesDueNextSevenDays(c.scope());
                    return new Widget(
                            "ap.due_7_days",
                            "Payables due in 7 days",
                            f.amount(),
                            c.currency(),
                            f.count(),
                            null,
                            "ap-ageing");
                });
        widget(
                "inventory.stock_value",
                "Stock value",
                List.of(ReportingPermissions.INVENTORY_VALUATION_READ),
                "stock-valuation",
                (k, c) -> {
                    KpiQueries.Figure f = k.stockValue(c.scope());
                    return new Widget(
                            "inventory.stock_value",
                            "Stock value",
                            f.amount(),
                            c.currency(),
                            f.count(),
                            null,
                            "stock-valuation");
                });
        widget(
                "inventory.low_stock",
                "Items out of stock or fully committed",
                List.of(ReportingPermissions.INVENTORY_READ),
                "stock-on-hand",
                (k, c) -> new Widget(
                        "inventory.low_stock",
                        "Items out of stock or fully committed",
                        null,
                        null,
                        k.lowStockCount(c.scope()),
                        null,
                        "stock-on-hand"));
        widget(
                "procurement.open_orders",
                "Open purchase orders",
                List.of(ReportingPermissions.PROCUREMENT_READ),
                "purchase-orders",
                (k, c) -> {
                    KpiQueries.OrderCounts o = k.openPurchaseOrders(c.scope());
                    return new Widget(
                            "procurement.open_orders",
                            "Open purchase orders",
                            null,
                            null,
                            o.open(),
                            o.pendingApproval(),
                            "purchase-orders");
                });
        widget(
                "hr.headcount",
                "Headcount",
                List.of(ReportingPermissions.HR_READ),
                "headcount",
                (k, c) ->
                        new Widget("hr.headcount", "Headcount", null, null, k.headcount(c.scope()), null, "headcount"));

        dashboard("executive", "Executive", List.copyOf(WIDGETS.keySet()));
        dashboard("sales", "Sales", List.of("sales.mtd", "ar.overdue"));
        dashboard("finance", "Finance", List.of("sales.mtd", "ar.overdue", "ap.due_7_days", "inventory.stock_value"));
        dashboard(
                "operations",
                "Operations",
                List.of("inventory.stock_value", "inventory.low_stock", "procurement.open_orders"));
        dashboard("hr", "HR", List.of("hr.headcount"));
    }

    private static void widget(
            String code,
            String label,
            List<String> permissions,
            @Nullable String reportCode,
            BiFunction<KpiQueries.Kpis, Context, Widget> compute) {
        WIDGETS.put(code, new WidgetDefinition(code, label, permissions, reportCode, compute));
    }

    private static void dashboard(String code, String name, List<String> widgets) {
        DASHBOARDS.put(code, new DashboardDefinition(code, name, widgets));
    }

    private final KpiQueries kpis;
    private final ReportingContext context;
    private final ReportingProperties properties;
    private final OrgFacade org;

    DashboardService(KpiQueries kpis, ReportingContext context, ReportingProperties properties, OrgFacade org) {
        this.kpis = kpis;
        this.context = context;
        this.properties = properties;
        this.org = org;
    }

    public List<DashboardSummary> list() {
        return DASHBOARDS.values().stream()
                .map(d -> new DashboardSummary(d.code(), d.name(), visible(d).size()))
                .toList();
    }

    public Dashboard get(String code) {
        DashboardDefinition definition = DASHBOARDS.get(code);
        if (definition == null) {
            throw ApiException.notFound();
        }
        List<WidgetDefinition> visible = visible(definition);
        ReportScope scope = context.scope();
        String currency =
                org.findCompany(scope.companyId()).map(c -> c.baseCurrency()).orElse(null);
        List<Widget> widgets = visible.isEmpty()
                ? List.of()
                : kpis.read(properties.queryTimeout(), k -> {
                    List<Widget> values = new ArrayList<>();
                    for (WidgetDefinition widget : visible) {
                        values.add(widget.compute().apply(k, new Context(scope, currency)));
                    }
                    return values;
                });
        return new Dashboard(definition.code(), definition.name(), scope.today(), widgets, context.now());
    }

    private List<WidgetDefinition> visible(DashboardDefinition dashboard) {
        return dashboard.widgets().stream()
                .map(WIDGETS::get)
                .filter(w -> context.isGrantedAll(w.permissions()))
                .toList();
    }
}
