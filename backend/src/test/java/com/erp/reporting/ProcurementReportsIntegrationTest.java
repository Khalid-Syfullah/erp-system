package com.erp.reporting;

import static com.erp.support.AuthTestSupport.unsafe;
import static com.erp.support.ProcurementFixtures.expect;
import static com.erp.support.ProcurementFixtures.id;
import static com.erp.support.ReportingFixtures.decimal;
import static com.erp.support.ReportingFixtures.rows;
import static com.erp.support.ReportingFixtures.totals;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erp.accounting.application.LedgerInvariantCheck;
import com.erp.support.AccountingFixtures;
import com.erp.support.AccountingFixtures.Books;
import com.erp.support.AuthTestSupport;
import com.erp.support.IntegrationTest;
import com.erp.support.OrgFixtures;
import com.erp.support.ProcurementFixtures;
import com.erp.support.ProcurementFixtures.P2P;
import com.erp.support.ReportingFixtures;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Procurement reports (PRODUCT_SPEC.md §13): the GRNI report reconciles with the GRNI account
 * through receipts, bills, returns and debit notes (DEVELOPMENT_PLAN.md Phase 10 exit criterion);
 * purchases with the payables booked; outstanding bills with Accounting's open items.
 */
class ProcurementReportsIntegrationTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ProcurementFixtures proc;

    @Autowired
    AccountingFixtures acc;

    @Autowired
    ReportingFixtures rep;

    @Autowired
    AuthTestSupport auth;

    @Autowired
    LedgerInvariantCheck invariants;

    private P2P p;
    private Books b;
    private UUID company;
    private Cookie analyst;
    private LocalDate today;
    private String period;

    @BeforeEach
    void setUp() throws Exception {
        p = proc.setup();
        company = p.inv().company();
        b = acc.books(company);
        today = p.inv().today();
        period = "from=" + today + "&to=" + today;
        analyst = rep.user(company, "reporting.procurement.read");
    }

    @Test
    void theGrniReportEqualsTheGrniAccountThroughBillsReturnsAndDebitNotes() throws Exception {
        UUID order = proc.approvedOrder(p, proc.orderLine(p.inv().variant(), "10", "5.00", p.taxCode()));
        UUID first = proc.postedReceipt(
                p, order, List.of(proc.receiptLine(proc.orderLines(p, order).getFirst(), "6")));
        UUID second = proc.postedReceipt(p, order, null);
        assertGrniReconciles("50");

        UUID bill = bill(List.of(first), "INV-1");
        assertGrniReconciles("20");
        List<Map<String, Object>> open = rows(rep.run(analyst, company, "grni", ""));
        assertThat(open).singleElement().satisfies(line -> {
            assertThat(line).containsEntry("supplierCode", "ACME");
            assertThat(decimal(line.get("unbilledQuantityBase"))).isEqualByComparingTo("4");
        });

        // Two billed units go back: GRNI waits for the supplier's credit, then clears.
        UUID receiptLine = proc.receiptLines(p, first).getFirst();
        UUID purchaseReturn = id(
                mvc.perform(unsafe(post(p.path("/purchase-returns")))
                        .cookie(p.session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OrgFixtures.json(
                                "goodsReceiptId",
                                first,
                                "reason",
                                "Damaged",
                                "lines",
                                List.of(OrgFixtures.map("goodsReceiptLineId", receiptLine, "quantity", "2"))))),
                201);
        expect(
                proc.action(
                        p,
                        p.session(),
                        "/purchase-returns/" + purchaseReturn + "/post",
                        0,
                        "r-" + purchaseReturn,
                        null),
                200);
        assertGrniReconciles("10");
        debitNote(bill, receiptLine, "2");
        assertGrniReconciles("20");

        bill(List.of(second), "INV-2");
        assertGrniReconciles("0");
        assertThat(rows(rep.run(analyst, company, "grni", ""))).isEmpty();
        assertThat(invariants.check(company).clean()).isTrue();
    }

    @Test
    void purchasesAndOutstandingBillsReconcileWithPayables() throws Exception {
        UUID order = proc.approvedOrder(p, proc.orderLine(p.inv().variant(), "10", "5.00", p.taxCode()));
        UUID receipt = proc.postedReceipt(p, order, null);
        UUID bill = bill(List.of(receipt), "INV-9");
        UUID receiptLine = proc.receiptLines(p, receipt).getFirst();
        returnUnits(receipt, receiptLine, "2");
        debitNote(bill, receiptLine, "2");
        BigDecimal payables = acc.balances(b).get("2000").negate();

        for (String groupBy : List.of("SUPPLIER", "PRODUCT", "CATEGORY", "BRANCH", "MONTH")) {
            String purchases = rep.run(analyst, company, "purchases", period + "&groupBy=" + groupBy);
            assertThat(rows(purchases)).as(groupBy).hasSize(1);
            assertThat(decimal(totals(purchases).get("netBase"))).as(groupBy).isEqualByComparingTo("40");
            assertThat(decimal(totals(purchases).get("taxBase"))).isEqualByComparingTo("4");
            assertThat(decimal(totals(purchases).get("grossBase")))
                    .isEqualByComparingTo("44")
                    .isEqualByComparingTo(payables);
        }
        assertThat(rows(rep.run(analyst, company, "purchases", period + "&groupBy=MONTH"))
                        .getFirst())
                .containsEntry("groupCode", today.toString().substring(0, 7));

        String outstanding = rep.run(analyst, company, "outstanding-supplier-bills", "");
        assertThat(rows(outstanding)).hasSize(2);
        assertThat(decimal(totals(outstanding).get("openAmountBase"))).isEqualByComparingTo(payables);
        assertThat(rows(rep.run(analyst, company, "outstanding-supplier-bills", "overdueOnly=true")))
                .isEmpty();
        assertThat(rows(rep.run(
                        analyst, company, "outstanding-supplier-bills", "overdueOnly=true&asOf=" + today.plusDays(40))))
                .singleElement()
                .satisfies(r -> assertThat(r).containsEntry("daysOverdue", 10).containsEntry("documentType", "BILL"));

        Map<String, Object> supplier =
                rows(rep.run(analyst, company, "supplier-analysis", period)).getFirst();
        assertThat(supplier)
                .containsEntry("supplierCode", "ACME")
                .containsEntry("orderCount", 1)
                .containsEntry("receiptCount", 1);
        assertThat(decimal(supplier.get("receivedValueBase"))).isEqualByComparingTo("50");
        assertThat(decimal(supplier.get("billedNetBase"))).isEqualByComparingTo("40");
        assertThat(decimal(supplier.get("openPayablesBase"))).isEqualByComparingTo(payables);
        assertThat(decimal(supplier.get("averageLeadTimeDays"))).isEqualByComparingTo("0");
        assertThat(invariants.check(company).clean()).isTrue();
    }

    @Test
    void ordersAndReceiptsShowTheirProgress() throws Exception {
        UUID order = proc.approvedOrder(p, proc.orderLine(p.inv().variant(), "10", "5.00", p.taxCode()));
        UUID other = proc.approvedOrder(p, proc.orderLine(p.inv().variant(), "3", "4.00", p.taxCode()));
        proc.postedReceipt(
                p, order, List.of(proc.receiptLine(proc.orderLines(p, order).getFirst(), "4")));

        List<Map<String, Object>> orders = rows(rep.run(analyst, company, "purchase-orders", "openOnly=true"));
        assertThat(orders).hasSize(2);
        Map<String, Object> partial = orders.stream()
                .filter(o -> order.toString().equals(o.get("purchaseOrderId")))
                .findFirst()
                .orElseThrow();
        assertThat(partial).containsEntry("status", "PARTIALLY_RECEIVED").containsEntry("billingStatus", "NOT_BILLED");
        assertThat(decimal(partial.get("receivedPercent"))).isEqualByComparingTo("40");
        assertThat(decimal(partial.get("total"))).isEqualByComparingTo("55");
        assertThat(rows(rep.run(analyst, company, "purchase-orders", "status=APPROVED")))
                .extracting(o -> o.get("purchaseOrderId"))
                .containsExactly(other.toString());

        List<Map<String, Object>> receiving = rows(rep.run(analyst, company, "receiving", period));
        assertThat(receiving).singleElement().satisfies(line -> {
            assertThat(line).containsEntry("warehouseCode", "WH1").containsEntry("sku", "WIDGET");
            assertThat(decimal(line.get("valueBase"))).isEqualByComparingTo("20");
            assertThat(decimal(line.get("unbilledQuantityBase"))).isEqualByComparingTo("4");
        });
        assertThat(rows(rep.run(analyst, company, "receiving", period + "&pendingBillingOnly=true")))
                .hasSize(1);
        assertThat(rows(rep.run(
                        analyst, company, "receiving", "from=" + today.plusDays(1) + "&to=" + today.plusDays(1))))
                .isEmpty();

        // Branch scope: the documents are in MAIN.
        Cookie elsewhere = rep.user(company, new UUID[] {auth.branch(company, "B2")}, "reporting.procurement.read");
        assertThat(rows(rep.run(elsewhere, company, "purchase-orders", ""))).isEmpty();
        assertThat(rows(rep.run(elsewhere, company, "receiving", period))).isEmpty();
        assertThat(rows(rep.run(elsewhere, company, "grni", ""))).isEmpty();

        Cookie buyer = rep.user(company, "procurement.purchase_order.read");
        rep.get(buyer, company, "purchase-orders", "").andExpect(status().isForbidden());
        Cookie foreign = rep.user(auth.company(), "reporting.procurement.read");
        rep.get(foreign, company, "purchase-orders", "").andExpect(status().isNotFound());
    }

    private void assertGrniReconciles(String expected) throws Exception {
        String grni = rep.run(analyst, company, "grni", "");
        BigDecimal account =
                acc.balances(b).getOrDefault("2050", BigDecimal.ZERO).negate();
        BigDecimal total =
                totals(grni) == null ? BigDecimal.ZERO : decimal(totals(grni).get("grniValueBase"));
        assertThat(total).isEqualByComparingTo(expected).isEqualByComparingTo(account);
    }

    private UUID bill(List<UUID> receipts, String number) throws Exception {
        UUID bill = UUID.fromString(JsonPath.read(
                expect(
                                mvc.perform(unsafe(post(p.path("/supplier-bills/from-receipts")))
                                        .cookie(p.session())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(OrgFixtures.json(
                                                "goodsReceiptIds", receipts,
                                                "supplierInvoiceNumber", number,
                                                "billDate", today))),
                                201)
                        .getResponse()
                        .getContentAsString(),
                "$.id"));
        expect(proc.action(p, p.session(), "/supplier-bills/" + bill + "/post", 0, "post-" + bill, null), 200);
        return bill;
    }

    private void returnUnits(UUID receipt, UUID receiptLine, String quantity) throws Exception {
        UUID purchaseReturn = id(
                mvc.perform(unsafe(post(p.path("/purchase-returns")))
                        .cookie(p.session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OrgFixtures.json(
                                "goodsReceiptId",
                                receipt,
                                "reason",
                                "Damaged",
                                "lines",
                                List.of(OrgFixtures.map("goodsReceiptLineId", receiptLine, "quantity", quantity))))),
                201);
        expect(
                proc.action(
                        p,
                        p.session(),
                        "/purchase-returns/" + purchaseReturn + "/post",
                        0,
                        "r-" + purchaseReturn,
                        null),
                200);
    }

    private void debitNote(UUID bill, UUID receiptLine, String quantity) throws Exception {
        UUID note = id(
                mvc.perform(unsafe(post(p.path("/supplier-bills")))
                        .cookie(p.session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OrgFixtures.json(
                                "documentType",
                                "DEBIT_NOTE",
                                "supplierId",
                                p.supplier(),
                                "supplierInvoiceNumber",
                                "CN-" + bill,
                                "billDate",
                                today,
                                "originalBillId",
                                bill,
                                "lines",
                                List.of(OrgFixtures.map(
                                        "goodsReceiptLineId",
                                        receiptLine,
                                        "quantity",
                                        quantity,
                                        "unitPrice",
                                        "5.00",
                                        "taxCodeId",
                                        p.taxCode()))))),
                201);
        expect(proc.action(p, p.session(), "/supplier-bills/" + note + "/post", 0, "post-" + note, null), 200);
    }
}
