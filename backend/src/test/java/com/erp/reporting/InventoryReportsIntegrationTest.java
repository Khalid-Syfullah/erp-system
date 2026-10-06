package com.erp.reporting;

import static com.erp.support.AuthTestSupport.unsafe;
import static com.erp.support.ReportingFixtures.decimal;
import static com.erp.support.ReportingFixtures.rows;
import static com.erp.support.ReportingFixtures.sum;
import static com.erp.support.ReportingFixtures.totals;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erp.accounting.application.LedgerInvariantCheck;
import com.erp.inventory.api.InventoryFacade;
import com.erp.support.AccountingFixtures;
import com.erp.support.AccountingFixtures.Books;
import com.erp.support.AuthTestSupport;
import com.erp.support.IntegrationTest;
import com.erp.support.InventoryFixtures;
import com.erp.support.InventoryFixtures.Setup;
import com.erp.support.InventoryFixtures.Warehouse;
import com.erp.support.OrgFixtures;
import com.erp.support.ReportingFixtures;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Inventory reports (PRODUCT_SPEC.md §13) on a ledger spread over ten days: openings, an adjustment
 * and scrap, a two-step transfer and a late adjustment. Current and as-of quantities agree with the
 * balances and the ledger, the valuation with the GL inventory account (exit criterion), adjustments
 * with the adjustment expense; date boundaries, branch scope and the valuation permission hold.
 */
class InventoryReportsIntegrationTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    InventoryFixtures inv;

    @Autowired
    AccountingFixtures acc;

    @Autowired
    ReportingFixtures rep;

    @Autowired
    AuthTestSupport auth;

    @Autowired
    InventoryFacade inventory;

    @Autowired
    LedgerInvariantCheck invariants;

    private Setup s;
    private Books b;
    private UUID company;
    private Cookie analyst;
    private LocalDate today;
    private Warehouse wh1;
    private Warehouse wh2;
    private UUID gadget;
    private UUID scrap;

    @BeforeEach
    void setUp() throws Exception {
        s = inv.setup();
        company = s.company();
        today = s.today();
        // The ledger spans the last ten days of the current fiscal year.
        assumeTrue(today.getDayOfYear() > 11, "needs ten days of the current year");
        b = acc.books(company);
        wh1 = s.warehouse();
        wh2 = inv.warehouse(s, "WH2");
        gadget = inv.variant(s, "GADGET", "EA");

        posted(inv.movement(
                s,
                "OPENING",
                wh1.id(),
                "movementDate",
                today.minusDays(10),
                "lines",
                List.of(opening(s.variant(), "100", "10"))));
        posted(inv.movement(
                s,
                "OPENING",
                wh1.id(),
                "movementDate",
                today.minusDays(10),
                "lines",
                List.of(opening(gadget, "10", "4"))));
        posted(inv.movement(
                s,
                "ADJUSTMENT",
                wh1.id(),
                "movementDate",
                today.minusDays(5),
                "reasonCodeId",
                s.adjustmentReason(),
                "lines",
                List.of(inv.line(s.variant(), wh1.stock(), null, "3"))));
        scrap = posted(inv.movement(
                s,
                "SCRAP",
                wh1.id(),
                "movementDate",
                today.minusDays(5),
                "reasonCodeId",
                s.scrapReason(),
                "lines",
                List.of(inv.line(s.variant(), wh1.stock(), null, "2"))));
        UUID ship = posted(inv.movement(
                s,
                "TRANSFER_SHIP",
                wh1.id(),
                "movementDate",
                today.minusDays(2),
                "destWarehouseId",
                wh2.id(),
                "lines",
                List.of(inv.line(s.variant(), wh1.stock(), wh2.transit(), "6"))));
        mvc.perform(unsafe(post(s.path("/stock-movements/" + ship + "/receive")))
                        .cookie(s.session())
                        .header("If-Match", OrgFixtures.etag(1))
                        .header("Idempotency-Key", "receive-" + ship))
                .andExpect(status().is2xxSuccessful());
        posted(inv.movement(
                s,
                "ADJUSTMENT",
                wh1.id(),
                "reasonCodeId",
                s.adjustmentReason(),
                "lines",
                List.of(inv.line(s.variant(), null, wh1.stock(), "1"))));
        analyst = rep.user(company, "reporting.inventory.read", "inventory.valuation.read");
    }

    @Test
    void stockOnHandAgreesWithBalancesNowAndWithTheLedgerAsOfADate() throws Exception {
        Map<String, BigDecimal> byWarehouse = onHand(rep.run(analyst, company, "stock-on-hand", ""));
        assertThat(byWarehouse)
                .isEqualTo(Map.of(
                        "WH1/WIDGET",
                        new BigDecimal("90"),
                        "WH1/GADGET",
                        new BigDecimal("10"),
                        "WH2/WIDGET",
                        new BigDecimal("6")));
        Map<String, Object> widget = rows(rep.run(
                        analyst, company, "stock-on-hand", "warehouseId=" + wh1.id() + "&productId=" + s.product()))
                .getFirst();
        assertThat(decimal(widget.get("available"))).isEqualByComparingTo("90");
        assertThat(decimal(widget.get("reserved"))).isZero();

        List<Map<String, Object>> locations = rows(rep.run(analyst, company, "stock-on-hand", "groupBy=LOCATION"));
        assertThat(locations).allSatisfy(l -> assertThat(l.get("locationCode")).isEqualTo("STOCK"));
        assertThat(decimal(locations.stream()
                        .filter(l -> "WH1".equals(l.get("warehouseCode")) && "WIDGET".equals(l.get("sku")))
                        .findFirst()
                        .orElseThrow()
                        .get("onHand")))
                .isEqualByComparingTo(inv.balance(s, s.variant(), wh1.stock()));
        assertThat(rows(rep.run(analyst, company, "stock-on-hand", "groupBy=LOCATION&includeZero=true")))
                .anySatisfy(l -> assertThat(l.get("locationCode")).isEqualTo("TRANSIT"));

        // As of a date: the ledger up to and including that day.
        assertThat(onHand(rep.run(analyst, company, "stock-on-hand", "asOf=" + today.minusDays(5))))
                .isEqualTo(Map.of("WH1/WIDGET", new BigDecimal("95"), "WH1/GADGET", new BigDecimal("10")));
        assertThat(onHand(rep.run(analyst, company, "stock-on-hand", "asOf=" + today.minusDays(6))))
                .isEqualTo(Map.of("WH1/WIDGET", new BigDecimal("100"), "WH1/GADGET", new BigDecimal("10")));
        assertThat(rows(rep.run(analyst, company, "stock-on-hand", "asOf=" + today.minusDays(11))))
                .isEmpty();
        assertThat(onHand(rep.run(analyst, company, "stock-on-hand", "asOf=" + today)))
                .isEqualTo(byWarehouse);
    }

    @Test
    void theValuationEqualsTheInventoryAccountNowAndAsOfADate() throws Exception {
        String valuation = rep.run(analyst, company, "stock-valuation", "");
        BigDecimal total = decimal(totals(valuation).get("valueBase"));
        assertThat(total)
                .isEqualByComparingTo("1000")
                .isEqualByComparingTo(acc.balances(b).get("1200"))
                .isEqualByComparingTo(inv.inCompany(s, inventory::valuationTotalBase));
        Map<String, Object> widget = rows(valuation).stream()
                .filter(r -> "WIDGET".equals(r.get("groupCode")))
                .findFirst()
                .orElseThrow();
        assertThat(decimal(widget.get("quantityBase"))).isEqualByComparingTo("96");
        assertThat(decimal(widget.get("averageCostBase"))).isEqualByComparingTo("10");
        assertThat(decimal(totals(rep.run(analyst, company, "stock-valuation", "groupBy=CATEGORY"))
                        .get("valueBase")))
                .isEqualByComparingTo(total);
        assertThat(decimal(totals(rep.run(analyst, company, "stock-valuation", "asOf=" + today.minusDays(6)))
                        .get("valueBase")))
                .isEqualByComparingTo("1040");
        assertThat(decimal(totals(rep.run(analyst, company, "stock-valuation", "asOf=" + today))
                        .get("valueBase")))
                .isEqualByComparingTo(total);
        assertThat(invariants.check(company).clean()).isTrue();

        // The valuation needs its own permission.
        Cookie clerk = rep.user(company, "reporting.inventory.read");
        rep.get(clerk, company, "stock-valuation", "").andExpect(status().isForbidden());
        rep.get(clerk, company, "stock-on-hand", "").andExpect(status().isOk());
    }

    @Test
    void movementsAdjustmentsAndTransactionsFollowTheLedger() throws Exception {
        String range = "from=" + today.minusDays(5) + "&to=" + today;
        Map<String, Map<String, Object>> movements = rows(rep.run(analyst, company, "stock-movements", range)).stream()
                .collect(Collectors.toMap(r -> r.get("warehouseCode") + "/" + r.get("sku"), r -> r));
        assertThat(movements).containsOnlyKeys("WH1/WIDGET", "WH1/GADGET", "WH2/WIDGET");
        Map<String, Object> w1 = movements.get("WH1/WIDGET");
        assertThat(decimal(w1.get("openingQuantity"))).isEqualByComparingTo("100");
        assertThat(decimal(w1.get("adjustmentsQuantity"))).isEqualByComparingTo("-4");
        assertThat(decimal(w1.get("transfersQuantity"))).isEqualByComparingTo("-6");
        assertThat(decimal(w1.get("closingQuantity"))).isEqualByComparingTo("90");
        assertThat(decimal(movements.get("WH2/WIDGET").get("closingQuantity"))).isEqualByComparingTo("6");
        assertThat(decimal(movements.get("WH1/GADGET").get("openingQuantity"))).isEqualByComparingTo("10");

        String all = "from=" + today.minusDays(10) + "&to=" + today;
        String adjustments = rep.run(analyst, company, "inventory-adjustments", all);
        assertThat(rows(adjustments)).hasSize(3);
        // The adjustment expense account carries exactly the adjusted value.
        assertThat(decimal(totals(adjustments).get("valueBase")))
                .isEqualByComparingTo("-40")
                .isEqualByComparingTo(acc.balances(b).get("5100").negate());
        assertThat(rows(rep.run(analyst, company, "inventory-adjustments", all + "&reasonCodeId=" + s.scrapReason())))
                .singleElement()
                .satisfies(r ->
                        assertThat(r).containsEntry("movementType", "SCRAP").containsEntry("reasonCode", "EXPIRED"));

        List<Map<String, Object>> ledger = rep.allRows(analyst, company, "inventory-transactions", all, 2);
        assertThat(ledger).hasSize(9);
        assertThat(sum(ledger, "quantityBase")).isEqualByComparingTo("106");
        assertThat(sum(ledger, "valueBase")).isEqualByComparingTo("1000");
        // Both ends of the range are included.
        String day = "from=" + today.minusDays(5) + "&to=" + today.minusDays(5);
        assertThat(rows(rep.run(analyst, company, "inventory-transactions", day)))
                .hasSize(2);
        assertThat(rows(rep.run(analyst, company, "inventory-transactions", all + "&movementType=OPENING")))
                .hasSize(2);
        assertThat(rows(rep.run(analyst, company, "inventory-transactions", all + "&locationId=" + wh2.transit())))
                .hasSize(2);

        // A reversed scrap counts as an adjustment of the day it is reversed.
        mvc.perform(unsafe(post(s.path("/stock-movements/" + scrap + "/reverse")))
                        .cookie(s.session())
                        .header("If-Match", OrgFixtures.etag(1))
                        .header("Idempotency-Key", "reverse-" + scrap))
                .andExpect(status().is2xxSuccessful());
        assertThat(rows(rep.run(analyst, company, "inventory-adjustments", all)))
                .hasSize(4)
                .anySatisfy(r -> assertThat(r).containsEntry("movementType", "REVERSAL"));
        assertThat(decimal(rows(rep.run(
                                analyst,
                                company,
                                "stock-movements",
                                range + "&warehouseId=" + wh1.id() + "&productId=" + s.product()))
                        .getFirst()
                        .get("adjustmentsQuantity")))
                .isEqualByComparingTo("-2");
    }

    @Test
    void slowMovingAndWarehouseSummaries() throws Exception {
        assertThat(rows(rep.run(analyst, company, "slow-moving", "days=30")))
                .extracting(r -> r.get("warehouseCode") + "/" + r.get("sku"))
                .containsExactly("WH1/GADGET", "WH2/WIDGET");
        assertThat(rows(rep.run(analyst, company, "slow-moving", "days=3")))
                .extracting(r -> r.get("warehouseCode") + "/" + r.get("sku"))
                .containsExactly("WH1/GADGET", "WH1/WIDGET", "WH2/WIDGET");
        Map<String, Object> widget =
                rows(rep.run(analyst, company, "slow-moving", "days=3&warehouseId=" + wh1.id())).stream()
                        .filter(r -> "WIDGET".equals(r.get("sku")))
                        .findFirst()
                        .orElseThrow();
        assertThat(widget)
                .containsEntry("lastIssueDate", today.minusDays(5).toString())
                .containsEntry("daysSinceLastIssue", 5);
        rep.get(analyst, company, "slow-moving", "days=0").andExpect(status().isBadRequest());

        Map<String, Map<String, Object>> warehouses =
                rows(rep.run(analyst, company, "warehouse-summary", "from=" + today.minusDays(10) + "&to=" + today))
                        .stream()
                        .collect(Collectors.toMap(r -> (String) r.get("warehouseCode"), r -> r));
        Map<String, Object> main = warehouses.get("WH1");
        assertThat(main)
                .containsEntry("skuCount", 2)
                .containsEntry("movementCount", 6)
                .containsEntry("branchCode", "MAIN");
        assertThat(decimal(main.get("onHandQuantity"))).isEqualByComparingTo("100");
        assertThat(decimal(main.get("inboundQuantity"))).isEqualByComparingTo("111");
        assertThat(decimal(main.get("outboundQuantity"))).isEqualByComparingTo("11");
        assertThat(decimal(warehouses.get("WH2").get("onHandQuantity"))).isEqualByComparingTo("6");
    }

    @Test
    void stockIsScopedByTheWarehousesBranch() throws Exception {
        UUID east = auth.branch(company, "EAST");
        Warehouse wh3 = inv.warehouse(s.session(), s.path(""), east, "WH3");
        inv.opening(s, s.variant(), wh3.stock(), "7", "10");
        Cookie eastOnly = rep.user(company, new UUID[] {east}, "reporting.inventory.read");
        assertThat(onHand(rep.run(eastOnly, company, "stock-on-hand", "")))
                .isEqualTo(Map.of("WH3/WIDGET", new BigDecimal("7")));
        assertThat(rows(rep.run(eastOnly, company, "warehouse-summary", "from=" + today + "&to=" + today)))
                .extracting(r -> r.get("warehouseCode"))
                .containsExactly("WH3");
        assertThat(rows(rep.run(
                        eastOnly, company, "inventory-transactions", "from=" + today.minusDays(10) + "&to=" + today)))
                .hasSize(1);
        assertThat(onHand(rep.run(analyst, company, "stock-on-hand", "")))
                .containsKey("WH3/WIDGET")
                .hasSize(4);
    }

    private UUID posted(Map<String, Object> body) throws Exception {
        return inv.postMovement(s, body);
    }

    private Map<String, Object> opening(UUID variant, String quantity, String unitCost) {
        Map<String, Object> line = inv.line(variant, null, wh1.stock(), quantity);
        line.put("unitCostBase", unitCost);
        return line;
    }

    private static Map<String, BigDecimal> onHand(String body) {
        return rows(body).stream()
                .collect(Collectors.toMap(
                        r -> r.get("warehouseCode") + "/" + r.get("sku"), r -> decimal(r.get("onHand"))));
    }
}
