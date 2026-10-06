package com.erp.reporting;

import static com.erp.support.ProcurementFixtures.expect;
import static com.erp.support.ProcurementFixtures.id;
import static com.erp.support.ReportingFixtures.decimal;
import static com.erp.support.ReportingFixtures.rows;
import static com.erp.support.ReportingFixtures.sum;
import static com.erp.support.ReportingFixtures.totals;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erp.accounting.application.LedgerInvariantCheck;
import com.erp.support.AccountingFixtures;
import com.erp.support.AccountingFixtures.Books;
import com.erp.support.AuthTestSupport;
import com.erp.support.IntegrationTest;
import com.erp.support.OrgFixtures;
import com.erp.support.ReportingFixtures;
import com.erp.support.SalesFixtures;
import com.erp.support.SalesFixtures.O2C;
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
import org.springframework.test.web.servlet.MockMvc;

/**
 * Sales reports (PRODUCT_SPEC.md §13) against the authoritative data: two customers' invoices, a
 * credit note for a return, a partial payment and an open order. Every figure is checked against
 * the general ledger or Accounting's open items; filters, date boundaries, pagination, branch scope,
 * permissions and company isolation are covered.
 */
class SalesReportsIntegrationTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    SalesFixtures sales;

    @Autowired
    AccountingFixtures acc;

    @Autowired
    ReportingFixtures rep;

    @Autowired
    AuthTestSupport auth;

    @Autowired
    LedgerInvariantCheck invariants;

    private O2C o;
    private Books b;
    private UUID company;
    private Cookie analyst;
    private LocalDate today;
    private UUID firstInvoice;
    private UUID secondInvoice;
    private UUID creditNote;
    private UUID customer2;
    private String period;

    @BeforeEach
    void setUp() throws Exception {
        o = sales.setup();
        company = o.inv().company();
        b = acc.books(company);
        today = o.inv().today();
        period = "from=" + today + "&to=" + today;
        sales.stock(o, "100", "10");
        customer2 = sales.customer(o.inv(), "CUST2", "USD", o.terms(), o.taxCode(), null);

        // CUST1: 4 × 25, one returned and credited. CUST2: 2 × 30, paid 30 of 66.
        UUID order1 = sales.confirmedOrder(o, sales.line(o.variant(), "4", null));
        UUID delivery1 = sales.postedDelivery(o, order1, null);
        firstInvoice = sales.postedInvoice(o, order1);
        UUID order2 = confirmedOrder(customer2, "2", "30");
        sales.postedDelivery(o, order2, null);
        secondInvoice = sales.postedInvoice(o, order2);
        creditNote = creditOneUnit(delivery1, firstInvoice);
        String item = (String) acc.openItem(b, "receivables", secondInvoice).get("id");
        acc.postedPayment(
                b, acc.payment(b, "INBOUND", customer2, b.bankAccount(), "30", Map.of(UUID.fromString(item), "30")));

        // Open order: 5 ordered, 2 delivered, nothing invoiced.
        UUID order3 = sales.confirmedOrder(o, sales.line(o.variant(), "5", null));
        UUID line = sales.orderLines(o, order3).getFirst();
        sales.postedDelivery(o, order3, List.of(sales.deliveryLine(line, "2")));

        analyst = rep.user(company, "reporting.sales.read");
    }

    @Test
    void summaryAndBreakdownsReconcileWithTheGeneralLedger() throws Exception {
        Map<String, BigDecimal> gl = acc.balances(b);
        BigDecimal revenue =
                gl.get("4000").add(gl.getOrDefault("4100", BigDecimal.ZERO)).negate();
        BigDecimal outputTax = gl.get("2100").negate();

        Map<String, Object> summary =
                rows(rep.run(analyst, company, "sales-summary", period)).getFirst();
        assertThat(summary)
                .containsEntry("invoiceCount", 2)
                .containsEntry("creditNoteCount", 1)
                .containsEntry("customerCount", 2);
        assertThat(decimal(summary.get("invoicedBase"))).isEqualByComparingTo("160");
        assertThat(decimal(summary.get("creditedBase"))).isEqualByComparingTo("-25");
        assertThat(decimal(summary.get("netSalesBase")))
                .isEqualByComparingTo("135")
                .isEqualByComparingTo(revenue);
        assertThat(decimal(summary.get("taxBase"))).isEqualByComparingTo("13.5").isEqualByComparingTo(outputTax);
        assertThat(decimal(summary.get("grossBase"))).isEqualByComparingTo("148.5");

        String byCustomer = rep.run(analyst, company, "sales-by-customer", period);
        List<Map<String, Object>> customers = rows(byCustomer);
        assertThat(customers).extracting(r -> r.get("customerCode")).containsExactly("CUST1", "CUST2");
        assertThat(customers)
                .extracting(r -> decimal(r.get("netSalesBase")))
                .containsExactly(new BigDecimal("75"), new BigDecimal("60"));
        assertThat(decimal(totals(byCustomer).get("netSalesBase"))).isEqualByComparingTo(revenue);

        Map<String, Object> product =
                rows(rep.run(analyst, company, "sales-by-product", period)).getFirst();
        assertThat(decimal(product.get("quantityBase"))).isEqualByComparingTo("5");
        assertThat(decimal(product.get("netSalesBase"))).isEqualByComparingTo("135");
        assertThat(decimal(product.get("averagePriceBase"))).isEqualByComparingTo("27");
        assertThat(product).containsEntry("sku", "WIDGET").containsEntry("uomCode", "EA");

        Map<String, Object> branch =
                rows(rep.run(analyst, company, "sales-by-branch", period)).getFirst();
        assertThat(branch).containsEntry("branchCode", "MAIN").containsEntry("invoiceCount", 3);
        assertThat(decimal(branch.get("netSalesBase"))).isEqualByComparingTo("135");

        List<Map<String, Object>> days = rows(rep.run(
                analyst,
                company,
                "sales-by-period",
                "from=" + today.minusDays(3) + "&to=" + today + "&granularity=DAY"));
        assertThat(days).singleElement().satisfies(d -> {
            assertThat(d).containsEntry("periodStart", today.toString());
            assertThat(decimal(d.get("netSalesBase"))).isEqualByComparingTo("135");
        });
        assertThat(rows(rep.run(analyst, company, "sales-by-period", period)))
                .singleElement()
                .satisfies(m -> assertThat(m)
                        .containsEntry("periodStart", today.withDayOfMonth(1).toString()));

        // Filters: the customer, the product's category.
        assertThat(decimal(rows(rep.run(analyst, company, "sales-summary", period + "&customerId=" + customer2))
                        .getFirst()
                        .get("netSalesBase")))
                .isEqualByComparingTo("60");
        assertThat(decimal(rows(rep.run(
                                analyst,
                                company,
                                "sales-summary",
                                period + "&categoryId=" + o.inv().category()))
                        .getFirst()
                        .get("netSalesBase")))
                .isEqualByComparingTo("135");
        assertThat(rows(rep.run(analyst, company, "sales-by-customer", period + "&productId=" + UUID.randomUUID())))
                .isEmpty();
        assertThat(invariants.check(company).clean()).isTrue();
    }

    @Test
    void dateRangesIncludeBothEnds() throws Exception {
        assertThat(rows(rep.run(analyst, company, "sales-by-customer", period))).hasSize(2);
        assertThat(rows(rep.run(analyst, company, "sales-by-customer", "from=" + today + "&to=" + today.plusDays(30))))
                .hasSize(2);
        assertThat(rows(rep.run(
                        analyst,
                        company,
                        "sales-by-customer",
                        "from=" + today.plusDays(1) + "&to=" + today.plusDays(30))))
                .isEmpty();
        assertThat(rows(rep.run(
                        analyst,
                        company,
                        "sales-by-customer",
                        "from=" + today.minusDays(30) + "&to=" + today.minusDays(1))))
                .isEmpty();
        Map<String, Object> empty = rows(rep.run(
                        analyst, company, "sales-summary", "from=" + today.plusDays(1) + "&to=" + today.plusDays(2)))
                .getFirst();
        assertThat(empty).containsEntry("invoiceCount", 0);
        assertThat(decimal(empty.get("netSalesBase"))).isZero();
    }

    @Test
    void invoiceAndPaymentStatusFollowAccountingsOpenItems() throws Exception {
        List<Map<String, Object>> invoices = rows(rep.run(analyst, company, "invoice-status", ""));
        assertThat(invoices).hasSize(3);
        for (Map<String, Object> invoice : invoices) {
            Map<String, Object> item =
                    acc.openItem(b, "receivables", UUID.fromString((String) invoice.get("invoiceId")));
            assertThat(decimal(invoice.get("openAmountBase")))
                    .isEqualByComparingTo(decimal(item.get("openAmountBase")));
        }
        Map<String, Object> partial = invoices.stream()
                .filter(i -> secondInvoice.toString().equals(i.get("invoiceId")))
                .findFirst()
                .orElseThrow();
        assertThat(partial).containsEntry("paymentStatus", "PARTIALLY_PAID").containsEntry("status", "POSTED");
        assertThat(decimal(partial.get("openAmountBase"))).isEqualByComparingTo("36");
        Map<String, Object> credit = invoices.stream()
                .filter(i -> creditNote.toString().equals(i.get("invoiceId")))
                .findFirst()
                .orElseThrow();
        assertThat(credit).containsEntry("documentType", "CREDIT_NOTE");
        assertThat(decimal(credit.get("totalBase"))).isEqualByComparingTo("-27.5");

        assertThat(rows(rep.run(analyst, company, "invoice-status", "paymentStatus=PARTIALLY_PAID")))
                .extracting(r -> r.get("invoiceId"))
                .containsExactly(secondInvoice.toString());
        assertThat(rows(rep.run(analyst, company, "invoice-status", "documentType=CREDIT_NOTE")))
                .hasSize(1);
        // Past the due date (net 30) the open invoices are overdue.
        assertThat(rows(rep.run(analyst, company, "invoice-status", "documentType=INVOICE&asOf=" + today.plusDays(45))))
                .allSatisfy(r -> assertThat(r.get("paymentStatus")).isIn("OVERDUE", "PAID"))
                .anySatisfy(r -> assertThat((Integer) r.get("daysOverdue")).isEqualTo(15));

        String payment = rep.run(analyst, company, "payment-status", "");
        BigDecimal invoiceOpen = decimal(
                        acc.openItem(b, "receivables", firstInvoice).get("openAmountBase"))
                .add(decimal(acc.openItem(b, "receivables", secondInvoice).get("openAmountBase")));
        assertThat(decimal(totals(payment).get("openAmountBase"))).isEqualByComparingTo(invoiceOpen);
        assertThat(totals(payment)).containsEntry("invoiceCount", 2);
        assertThat(decimal(totals(payment).get("totalBase"))).isEqualByComparingTo("176");
    }

    @Test
    void grossMarginSetsInvoiceRevenueAgainstTheCostOfGoodsDelivered() throws Exception {
        Map<String, BigDecimal> gl = acc.balances(b);
        String margin = rep.run(analyst, company, "gross-margin", period);
        Map<String, Object> row = rows(margin).getFirst();
        assertThat(row).containsEntry("groupCode", "WIDGET");
        assertThat(decimal(row.get("revenueBase"))).isEqualByComparingTo("135");
        // 4 + 2 + 2 delivered less 1 returned, at the average cost of 10: the COGS account.
        assertThat(decimal(row.get("cogsBase"))).isEqualByComparingTo("70").isEqualByComparingTo(gl.get("5000"));
        assertThat(decimal(row.get("marginBase"))).isEqualByComparingTo("65");
        assertThat(decimal(row.get("marginPercent"))).isEqualByComparingTo("48.15");
        assertThat(rows(rep.run(analyst, company, "gross-margin", period + "&groupBy=CATEGORY")))
                .singleElement()
                .satisfies(c -> assertThat(c).containsEntry("groupCode", "GOODS"));
    }

    @Test
    void theBacklogShowsWhatIsLeftToDeliverAndToInvoice() throws Exception {
        List<Map<String, Object>> backlog = rows(rep.run(analyst, company, "order-backlog", ""));
        assertThat(backlog).anySatisfy(line -> {
            assertThat(decimal(line.get("quantityBase"))).isEqualByComparingTo("5");
            assertThat(decimal(line.get("undeliveredQuantityBase"))).isEqualByComparingTo("3");
            assertThat(decimal(line.get("uninvoicedDeliveredQuantityBase"))).isEqualByComparingTo("2");
            assertThat(decimal(line.get("undeliveredNetAmount"))).isEqualByComparingTo("75");
        });
        assertThat(rows(rep.run(analyst, company, "order-backlog", "customerId=" + customer2)))
                .isEmpty();
    }

    @Test
    void pagesFollowSignedCursorsAndTotalsComeWithTheFirstPage() throws Exception {
        String first = rep.run(analyst, company, "sales-by-customer", period + "&limit=1");
        assertThat(rows(first)).hasSize(1);
        assertThat((Boolean) JsonPath.read(first, "$.page.hasMore")).isTrue();
        assertThat(totals(first)).isNotNull();
        String cursor = JsonPath.read(first, "$.page.nextCursor");
        String second = rep.run(analyst, company, "sales-by-customer", period + "&limit=1&cursor=" + cursor);
        assertThat(rows(second)).extracting(r -> r.get("customerCode")).containsExactly("CUST2");
        assertThat((Boolean) JsonPath.read(second, "$.page.hasMore")).isFalse();
        assertThat(JsonPath.<Object>read(second, "$.totals")).isNull();
        assertThat(sum(rep.allRows(analyst, company, "sales-by-customer", period, 1), "netSalesBase"))
                .isEqualByComparingTo("135");

        // A cursor belongs to its report, parameters and sort.
        rep.get(
                        analyst,
                        company,
                        "sales-by-customer",
                        "from=" + today.minusDays(1) + "&to=" + today + "&cursor=" + cursor)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("INVALID_CURSOR"));
        rep.get(analyst, company, "sales-by-customer", period + "&sort=customerCode&cursor=" + cursor)
                .andExpect(status().isBadRequest());

        assertThat(rows(rep.run(analyst, company, "sales-by-customer", period + "&sort=-customerCode")))
                .extracting(r -> r.get("customerCode"))
                .containsExactly("CUST2", "CUST1");
    }

    @Test
    void parametersAreValidatedAllAtOnce() throws Exception {
        rep.get(analyst, company, "sales-summary", "to=" + today + "&bogus=1&branchId=x")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].code")
                        .value(org.hamcrest.Matchers.containsInAnyOrder(
                                "REQUIRED", "UNKNOWN_PARAMETER", "INVALID_VALUE")));
        rep.get(analyst, company, "sales-summary", "from=" + today + "&to=" + today.minusDays(1))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("OUT_OF_RANGE"));
        rep.get(analyst, company, "sales-summary", "from=" + today.minusYears(11) + "&to=" + today)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("OUT_OF_RANGE"));
        rep.get(analyst, company, "sales-by-customer", period + "&sort=taxBase,-taxBase")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("DUPLICATE_SORT"));
        rep.get(analyst, company, "sales-by-customer", period + "&sort=customerId")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("UNSUPPORTED_SORT"));
        rep.get(analyst, company, "sales-by-period", period + "&granularity=YEAR")
                .andExpect(status().isBadRequest());
        rep.get(analyst, company, "sales-by-customer", period + "&limit=5001").andExpect(status().isBadRequest());
        rep.get(analyst, company, "no-such-report", "").andExpect(status().isNotFound());
    }

    @Test
    void reportsNeedTheirPermissionAndStayInTheCompanyAndBranchScope() throws Exception {
        Cookie seller = rep.user(company, "sales.order.read", "sales.invoice.read");
        rep.get(seller, company, "sales-summary", period).andExpect(status().isForbidden());
        String catalogue = mvc.perform(
                        get(ReportingFixtures.path(company, "/reports")).cookie(seller))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(JsonPath.<List<?>>read(catalogue, "$")).isEmpty();

        // Branch scope: invoice lines carry the order's branch (MAIN).
        UUID other = auth.branch(company, "B2");
        Cookie elsewhere = rep.user(company, new UUID[] {other}, "reporting.sales.read");
        assertThat(decimal(rows(rep.run(elsewhere, company, "sales-summary", period))
                        .getFirst()
                        .get("netSalesBase")))
                .isZero();
        assertThat(rows(rep.run(elsewhere, company, "order-backlog", ""))).isEmpty();
        Cookie main = rep.user(company, new UUID[] {o.inv().branch()}, "reporting.sales.read");
        assertThat(decimal(rows(rep.run(main, company, "sales-summary", period))
                        .getFirst()
                        .get("netSalesBase")))
                .isEqualByComparingTo("135");

        // Another company's sales stay there, and its users cannot reach this company.
        O2C second = sales.setup();
        sales.stock(second, "10", "10");
        sales.postedInvoice(second, deliveredOrder(second));
        assertThat(decimal(rows(rep.run(analyst, company, "sales-summary", period))
                        .getFirst()
                        .get("netSalesBase")))
                .isEqualByComparingTo("135");
        Cookie foreign = rep.user(second.inv().company(), "reporting.sales.read");
        rep.get(foreign, company, "sales-summary", period).andExpect(status().isNotFound());
        assertThat(decimal(rows(rep.run(foreign, second.inv().company(), "sales-summary", period))
                        .getFirst()
                        .get("netSalesBase")))
                .isEqualByComparingTo("25");
    }

    private UUID deliveredOrder(O2C other) throws Exception {
        UUID order = sales.confirmedOrder(other, sales.line(other.variant(), "1", null));
        sales.postedDelivery(other, order, null);
        return order;
    }

    private UUID confirmedOrder(UUID customer, String quantity, String price) throws Exception {
        UUID order = id(
                sales.create(
                        o,
                        o.manager(),
                        "/sales-orders",
                        OrgFixtures.map(
                                "customerId",
                                customer,
                                "warehouseId",
                                o.warehouse(),
                                "lines",
                                List.of(sales.line(o.variant(), quantity, price)))),
                201);
        expect(sales.action(o, o.manager(), "/sales-orders/" + order + "/confirm", 0, "confirm-" + order, null), 200);
        return order;
    }

    private UUID creditOneUnit(UUID delivery, UUID invoice) throws Exception {
        UUID deliveryLine =
                sales.ids(o, "/deliveries/" + delivery, "$.lines[*].id").getFirst();
        UUID salesReturn = id(
                sales.create(
                        o,
                        "/sales-returns",
                        OrgFixtures.map(
                                "deliveryId",
                                delivery,
                                "reason",
                                "Damaged",
                                "lines",
                                List.of(OrgFixtures.map("deliveryLineId", deliveryLine, "quantity", "1")))),
                201);
        expect(
                sales.action(
                        o, o.session(), "/sales-returns/" + salesReturn + "/receive", 0, "rcv-" + salesReturn, null),
                200);
        UUID invoiceLine = sales.ids(o, "/invoices/" + invoice, "$.lines[*].id").getFirst();
        UUID returnLine =
                sales.ids(o, "/sales-returns/" + salesReturn, "$.lines[*].id").getFirst();
        UUID note = id(
                sales.create(
                        o,
                        "/invoices",
                        OrgFixtures.map(
                                "documentType",
                                "CREDIT_NOTE",
                                "customerId",
                                o.customer(),
                                "originalInvoiceId",
                                invoice,
                                "salesReturnId",
                                salesReturn,
                                "lines",
                                List.of(OrgFixtures.map(
                                        "originalInvoiceLineId",
                                        invoiceLine,
                                        "salesReturnLineId",
                                        returnLine,
                                        "quantity",
                                        "1")))),
                201);
        expect(sales.action(o, o.session(), "/invoices/" + note + "/post", 0, "post-" + note, null), 200);
        return note;
    }
}
