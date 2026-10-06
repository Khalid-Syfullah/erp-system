package com.erp.reporting;

import static com.erp.support.AuthTestSupport.unsafe;
import static com.erp.support.ProcurementFixtures.expect;
import static com.erp.support.ProcurementFixtures.id;
import static com.erp.support.ReportingFixtures.decimal;
import static com.erp.support.ReportingFixtures.rows;
import static com.erp.support.ReportingFixtures.totals;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erp.support.AccountingFixtures;
import com.erp.support.AccountingFixtures.Books;
import com.erp.support.IntegrationTest;
import com.erp.support.InventoryFixtures;
import com.erp.support.OrgFixtures;
import com.erp.support.ProcurementFixtures;
import com.erp.support.ProcurementFixtures.P2P;
import com.erp.support.ReportingFixtures;
import com.erp.support.SalesFixtures;
import com.erp.support.SalesFixtures.O2C;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Saved report parameters (owner, sharing, visibility, versioning) and role dashboards: each widget
 * equals the corresponding report or ledger figure, and widgets without permission are omitted.
 */
class SavedReportAndDashboardIntegrationTest extends IntegrationTest {

    private static final String MERGE_PATCH = "application/merge-patch+json";
    private static final Map<String, String> YEAR = Map.of("from", "2026-01-01", "to", "2026-12-31");

    @Autowired
    MockMvc mvc;

    @Autowired
    SalesFixtures sales;

    @Autowired
    ProcurementFixtures proc;

    @Autowired
    InventoryFixtures inv;

    @Autowired
    AccountingFixtures acc;

    @Autowired
    ReportingFixtures rep;

    @Test
    void savedReportsArePrivateUnlessSharedAndChangedOnlyByTheirOwner() throws Exception {
        UUID company = sales.setup().inv().company();
        Cookie owner = rep.user(company, "reporting.sales.read", "reporting.saved_report.share");
        Cookie colleague = rep.user(company, "reporting.sales.read");
        Cookie stranger = rep.user(company, "reporting.inventory.read");

        UUID mine = id(
                save(
                        owner,
                        company,
                        "sales-by-customer",
                        "My customers",
                        Map.of("from", "2026-01-01", "to", "2026-01-31"),
                        false),
                201);
        expect(save(owner, company, "sales-by-customer", "My customers", YEAR, false), 409);
        save(owner, company, "sales-by-customer", "Broken", Map.of("from", "yesterday"), false)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].pointer").value("/parameters/from"));
        expect(save(owner, company, "no-such-report", "Nothing", Map.of(), false), 404);
        expect(save(stranger, company, "sales-summary", "Not mine", YEAR, false), 403);
        // Sharing needs its permission.
        expect(save(colleague, company, "sales-summary", "Team", YEAR, true), 403);
        UUID shared = id(save(owner, company, "sales-summary", "Team summary", YEAR, true), 201);

        assertThat(names(owner, company)).containsExactly("My customers", "Team summary");
        assertThat(names(colleague, company)).containsExactly("Team summary");
        assertThat(names(stranger, company)).isEmpty();
        expect(
                mvc.perform(get(ReportingFixtures.path(company, "/saved-reports/" + mine))
                        .cookie(colleague)),
                404);
        mvc.perform(get(ReportingFixtures.path(company, "/saved-reports/" + shared))
                        .cookie(colleague))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.owned").value(false))
                .andExpect(jsonPath("$.isShared").value(true));

        // Changes: the owner only, with If-Match.
        expect(change(colleague, company, shared, 0, Map.of("name", "Hijacked")), 403);
        expect(change(owner, company, mine, null, Map.of("name", "Renamed")), 428);
        expect(change(owner, company, mine, 3, Map.of("name", "Renamed")), 412);
        change(
                        owner,
                        company,
                        mine,
                        0,
                        Map.of("name", "Renamed", "parameters", Map.of("from", "2026-02-01", "to", "2026-02-28")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.parameters.from").value("2026-02-01"));
        expect(
                change(owner, company, mine, 1, Map.of("parameters", Map.of("to", "2026-02-28", "from", "2026-03-01"))),
                422);
        expect(change(owner, company, mine, 1, Map.of("reportCode", "sales-summary")), 400);
        expect(
                mvc.perform(unsafe(delete(ReportingFixtures.path(company, "/saved-reports/" + shared)))
                        .cookie(colleague)
                        .header("If-Match", OrgFixtures.etag(0))),
                403);
        expect(
                mvc.perform(unsafe(delete(ReportingFixtures.path(company, "/saved-reports/" + mine)))
                        .cookie(owner)
                        .header("If-Match", OrgFixtures.etag(1))),
                204);
        assertThat(names(owner, company)).containsExactly("Team summary");
    }

    @Test
    void dashboardWidgetsEqualTheirReportsAndFollowPermissions() throws Exception {
        P2P p = proc.setup();
        UUID company = p.inv().company();
        Books b = acc.books(company);
        LocalDate today = p.inv().today();
        UUID order = proc.approvedOrder(p, proc.orderLine(p.inv().variant(), "10", "5.00", p.taxCode()));
        proc.postedReceipt(
                p, order, List.of(proc.receiptLine(proc.orderLines(p, order).getFirst(), "4")));
        // A second product that ran out: low stock.
        UUID gadget = inv.variant(p.inv(), "GADGET", "EA");
        inv.opening(p.inv(), gadget, p.inv().warehouse().stock(), "1", "3");
        inv.postMovement(
                p.inv(),
                inv.movement(
                        p.inv(),
                        "ADJUSTMENT",
                        p.inv().warehouse().id(),
                        "reasonCodeId",
                        p.inv().adjustmentReason(),
                        "lines",
                        List.of(inv.line(gadget, p.inv().warehouse().stock(), null, "1"))));
        Cookie manager = rep.user(
                company,
                "reporting.procurement.read",
                "reporting.inventory.read",
                "inventory.valuation.read",
                "accounting.ap.read");

        String operations = dashboard(manager, company, "operations");
        assertThat(JsonPath.<List<String>>read(operations, "$.widgets[*].code"))
                .containsExactly("inventory.stock_value", "inventory.low_stock", "procurement.open_orders");
        assertThat(decimal(widget(operations, "inventory.stock_value").get("amount")))
                .isEqualByComparingTo("20")
                .isEqualByComparingTo(acc.balances(b).get("1200"))
                .isEqualByComparingTo(decimal(
                        totals(rep.run(manager, company, "stock-valuation", "")).get("valueBase")));
        assertThat(widget(operations, "inventory.stock_value")).containsEntry("currencyCode", "USD");
        assertThat(widget(operations, "inventory.low_stock")).containsEntry("count", 1);
        assertThat(widget(operations, "procurement.open_orders"))
                .containsEntry("count", 1)
                .containsEntry("secondaryCount", 0);

        // Widgets without permission are omitted (finance: AP only for this user).
        String finance = dashboard(manager, company, "finance");
        assertThat(JsonPath.<List<String>>read(finance, "$.widgets[*].code"))
                .containsExactly("ap.due_7_days", "inventory.stock_value");
        assertThat(widget(finance, "ap.due_7_days")).containsEntry("count", 0);
        assertThat(JsonPath.<List<?>>read(dashboard(manager, company, "hr"), "$.widgets"))
                .isEmpty();
        mvc.perform(get(ReportingFixtures.path(company, "/dashboards/nope")).cookie(manager))
                .andExpect(status().isNotFound());
        String list = mvc.perform(
                        get(ReportingFixtures.path(company, "/dashboards")).cookie(manager))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(JsonPath.<List<Integer>>read(list, "$[?(@.code=='operations')].visibleWidgets"))
                .containsExactly(3);
        assertThat(today).isNotNull();
    }

    @Test
    void salesAndReceivableWidgetsAgreeWithReportsAndOpenItems() throws Exception {
        O2C o = sales.setup();
        LocalDate today = o.inv().today();
        assumeTrue(today.getDayOfYear() > 11, "needs a past invoice date in the current year");
        UUID company = o.inv().company();
        Books b = acc.books(company);
        sales.stock(o, "50", "10");
        UUID order = sales.confirmedOrder(o, sales.line(o.variant(), "3", null));
        sales.postedDelivery(o, order, null);
        sales.postedInvoice(o, order);
        // A service invoiced ten days ago, due five days ago: overdue.
        UUID service = sales.service(o, "SUPPORT");
        UUID late = id(
                sales.create(
                        o,
                        o.manager(),
                        "/invoices",
                        OrgFixtures.map(
                                "documentType",
                                "INVOICE",
                                "customerId",
                                o.customer(),
                                "invoiceDate",
                                today.minusDays(10),
                                "dueDate",
                                today.minusDays(5),
                                "lines",
                                List.of(OrgFixtures.map(
                                        "variantId",
                                        service,
                                        "quantity",
                                        "1",
                                        "uomId",
                                        inv.uom("EA"),
                                        "unitPrice",
                                        "40")))),
                201);
        expect(sales.action(o, o.session(), "/invoices/" + late + "/post", 0, "post-" + late, null), 200);
        Cookie seller = rep.user(company, "reporting.sales.read", "accounting.ar.read");

        String dashboard = dashboard(seller, company, "sales");
        Map<String, Object> mtd = widget(dashboard, "sales.mtd");
        String month = "from=" + today.withDayOfMonth(1) + "&to=" + today;
        assertThat(decimal(mtd.get("amount")))
                .isEqualByComparingTo(decimal(rows(rep.run(seller, company, "sales-summary", month))
                        .getFirst()
                        .get("netSalesBase")));
        Map<String, Object> overdue = widget(dashboard, "ar.overdue");
        assertThat(overdue).containsEntry("count", 1).containsEntry("reportCode", "ar-ageing");
        assertThat(decimal(overdue.get("amount")))
                .isEqualByComparingTo("44")
                .isEqualByComparingTo(
                        decimal(acc.openItem(b, "receivables", late).get("openAmountBase")));
    }

    private String dashboard(Cookie session, UUID company, String code) throws Exception {
        return mvc.perform(get(ReportingFixtures.path(company, "/dashboards/" + code))
                        .cookie(session))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private static Map<String, Object> widget(String dashboard, String code) {
        List<Map<String, Object>> widgets = JsonPath.read(dashboard, "$.widgets[?(@.code=='" + code + "')]");
        assertThat(widgets).hasSize(1);
        return widgets.getFirst();
    }

    private ResultActions save(
            Cookie session, UUID company, String report, String name, Map<String, String> parameters, boolean shared)
            throws Exception {
        return mvc.perform(unsafe(post(ReportingFixtures.path(company, "/saved-reports")))
                .cookie(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content(OrgFixtures.JSON_MAPPER.writeValueAsString(OrgFixtures.map(
                        "reportCode", report, "name", name, "parameters", parameters, "isShared", shared))));
    }

    private ResultActions change(Cookie session, UUID company, UUID id, Integer version, Map<String, Object> body)
            throws Exception {
        var request = unsafe(patch(ReportingFixtures.path(company, "/saved-reports/" + id)))
                .cookie(session)
                .contentType(MediaType.parseMediaType(MERGE_PATCH))
                .content(OrgFixtures.JSON_MAPPER.writeValueAsString(body));
        if (version != null) {
            request.header("If-Match", OrgFixtures.etag(version));
        }
        return mvc.perform(request);
    }

    private List<String> names(Cookie session, UUID company) throws Exception {
        String body = mvc.perform(
                        get(ReportingFixtures.path(company, "/saved-reports")).cookie(session))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JsonPath.read(body, "$.data[*].name");
    }
}
