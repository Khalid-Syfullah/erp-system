package com.erp.sales;

import static com.erp.support.AuthTestSupport.unsafe;
import static com.erp.support.OrgFixtures.etag;
import static com.erp.support.ProcurementFixtures.expect;
import static com.erp.support.ProcurementFixtures.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erp.sales.events.InvoicePosted;
import com.erp.support.IntegrationTest;
import com.erp.support.OrgFixtures;
import com.erp.support.SalesFixtures;
import com.erp.support.SalesFixtures.O2C;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Invoices (SAL-5) and credit notes (SAL-6): invoiceable quantities, direct invoices of services,
 * credit limits per invoice line, drafts and invalid transitions, foreign currency.
 */
@RecordApplicationEvents
class InvoiceIntegrationTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    SalesFixtures sales;

    @Autowired
    ApplicationEvents events;

    private O2C o;

    @BeforeEach
    void setUp() throws Exception {
        o = sales.setup();
        sales.stock(o, "50", "10");
    }

    @Test
    void invoicesNeverExceedTheInvoiceableQuantity() throws Exception {
        UUID order = sales.confirmedOrder(o, sales.line(o.variant(), "6", null));
        UUID line = sales.orderLines(o, order).getFirst();
        // Nothing delivered yet: nothing to invoice under the DELIVERED policy.
        sales.create(o, "/invoices/from-order", OrgFixtures.map("salesOrderId", order))
                .andExpect(status().isConflict());
        sales.postedDelivery(o, order, List.of(sales.deliveryLine(line, "4")));
        invoice(o.session(), order, line, "5")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].code").value("QUANTITY_EXCEEDS_REMAINING"));
        UUID first = id(invoice(o.session(), order, line, "3"), 201);
        UUID second = id(invoice(o.session(), order, line, "3"), 201);
        expect(sales.action(o, o.session(), "/invoices/" + first + "/post", 0, "post-" + first, null), 200);
        String refused = expect(
                        sales.action(o, o.session(), "/invoices/" + second + "/post", 0, "post-" + second, null), 422)
                .getResponse()
                .getContentAsString();
        assertThat((String) JsonPath.read(refused, "$.code")).isEqualTo("QUANTITY_EXCEEDS_REMAINING");
        assertThat(sales.<String>read(o, "/sales-orders/" + order, "$.lines[0].invoicedQuantityBase"))
                .isEqualTo("3.000000");
        // A different price on an order line needs override_price.
        Map<String, Object> repriced = invoiceBody(order, line, "1");
        lineOf(repriced).put("unitPrice", "20");
        sales.create(o, "/invoices", repriced).andExpect(status().isForbidden());
        sales.create(o, o.manager(), "/invoices", repriced).andExpect(status().isCreated());
    }

    @Test
    void invoicesFollowTheOrderAndItsCustomer() throws Exception {
        UUID draft = sales.draftOrder(o, sales.line(o.variant(), "1", null));
        UUID draftLine = sales.orderLines(o, draft).getFirst();
        invoice(o.session(), draft, draftLine, "1").andExpect(status().isConflict());

        UUID order = sales.confirmedOrder(o, sales.line(o.variant(), "2", null));
        UUID line = sales.orderLines(o, order).getFirst();
        sales.postedDelivery(o, order, null);
        UUID other = sales.customer(o.inv(), "OTHER", "USD", o.terms(), o.taxCode(), null);
        Map<String, Object> wrongCustomer = invoiceBody(order, line, "1");
        wrongCustomer.put("customerId", other);
        sales.create(o, "/invoices", wrongCustomer)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].code").value("CUSTOMER_MISMATCH"));
        invoice(o.session(), order, draftLine, "1")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].code").value("UNKNOWN_LINE"));

        // Net 30 terms from the order.
        UUID invoice = id(invoice(o.session(), order, line, "2"), 201);
        String body = sales.body(o, "/invoices/" + invoice);
        LocalDate date = LocalDate.parse(JsonPath.read(body, "$.invoiceDate"));
        assertThat((String) JsonPath.read(body, "$.dueDate"))
                .isEqualTo(date.plusDays(30).toString());
        assertThat((String) JsonPath.read(body, "$.paymentTermsId"))
                .isEqualTo(o.terms().toString());
    }

    @Test
    void openOrdersOfABlockedCustomerAreStillInvoiced() throws Exception {
        UUID order = sales.confirmedOrder(o, sales.line(o.variant(), "2", null));
        sales.postedDelivery(o, order, null);
        mvc.perform(unsafe(post(o.path("/partners/" + o.customer() + "/block")))
                        .cookie(o.session())
                        .header("If-Match", etag(0)))
                .andExpect(status().isOk());
        UUID invoice = sales.postedInvoice(o, order);
        assertThat(sales.<String>read(o, "/invoices/" + invoice, "$.status")).isEqualTo("POSTED");
        UUID service = sales.service(o, "SUPPORT");
        sales.create(
                        o,
                        o.manager(),
                        "/invoices",
                        OrgFixtures.map(
                                "documentType",
                                "INVOICE",
                                "customerId",
                                o.customer(),
                                "lines",
                                List.of(directLine(service, "1", "10"))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("PARTNER_BLOCKED"));
    }

    @Test
    void directInvoicesAreForServicesAndNeedThePermission() throws Exception {
        UUID service = sales.service(o, "CONSULT");
        Map<String, Object> direct = OrgFixtures.map(
                "documentType",
                "INVOICE",
                "customerId",
                o.customer(),
                "lines",
                List.of(directLine(service, "3", "120")));
        sales.create(o, "/invoices", direct).andExpect(status().isForbidden());
        Map<String, Object> goods = OrgFixtures.map(
                "documentType",
                "INVOICE",
                "customerId",
                o.customer(),
                "lines",
                List.of(directLine(o.variant(), "1", "25")));
        sales.create(o, o.manager(), "/invoices", goods)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].code").value("NOT_SERVICE"));
        UUID invoice = id(sales.create(o, o.manager(), "/invoices", direct), 201);
        String body = sales.body(o, "/invoices/" + invoice);
        assertThat((String) JsonPath.read(body, "$.total")).isEqualTo("396.0000");
        assertThat(JsonPath.<String>read(body, "$.salesOrderId")).isNull();
        expect(sales.action(o, o.manager(), "/invoices/" + invoice + "/post", 0, "post-" + invoice, null), 200);
        assertThat(events.stream(InvoicePosted.class)).singleElement().satisfies(e -> {
            assertThat(e.salesOrderId()).isNull();
            assertThat(e.lines())
                    .singleElement()
                    .satisfies(l -> assertThat(l.salesOrderLineId()).isNull());
        });
    }

    @Test
    void creditNotesNeverExceedWhatWasInvoiced() throws Exception {
        UUID order = sales.confirmedOrder(o, sales.line(o.variant(), "4", null));
        sales.postedDelivery(o, order, null);
        UUID draftInvoice = id(sales.create(o, "/invoices/from-order", OrgFixtures.map("salesOrderId", order)), 201);
        UUID draftLine =
                sales.ids(o, "/invoices/" + draftInvoice, "$.lines[*].id").getFirst();
        credit(draftInvoice, draftLine, "1", null)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].pointer").value("/originalInvoiceId"));
        expect(
                sales.action(o, o.session(), "/invoices/" + draftInvoice + "/post", 0, "post-" + draftInvoice, null),
                200);
        UUID invoice = draftInvoice;
        UUID invoiceLine = draftLine;

        credit(invoice, invoiceLine, "5", null)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].code").value("QUANTITY_EXCEEDS_REMAINING"));
        credit(invoice, invoiceLine, "1", "30")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].code").value("EXCEEDS_ORIGINAL"));

        // A price-only credit (no goods back) leaves the order's invoiced quantity alone.
        UUID priceCredit = id(credit(invoice, invoiceLine, "4", "5"), 201);
        assertThat(sales.<String>read(o, "/invoices/" + priceCredit, "$.total")).isEqualTo("22.0000");
        expect(sales.action(o, o.session(), "/invoices/" + priceCredit + "/post", 0, "post-" + priceCredit, null), 200);
        assertThat(sales.<String>read(o, "/sales-orders/" + order, "$.lines[0].invoicedQuantityBase"))
                .isEqualTo("4.000000");
        assertThat(sales.<String>read(o, "/invoices/" + invoice, "$.lines[0].creditedQuantityBase"))
                .isEqualTo("4.000000");
        // Fully credited: nothing more, not even as a draft.
        credit(invoice, invoiceLine, "1", "1")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].code").value("QUANTITY_EXCEEDS_REMAINING"));
        // Credit notes are not credited themselves.
        UUID creditLine =
                sales.ids(o, "/invoices/" + priceCredit, "$.lines[*].id").getFirst();
        credit(priceCredit, creditLine, "1", null).andExpect(status().isUnprocessableContent());
    }

    @Test
    void draftInvoicesAreEditedAndPostedOnce() throws Exception {
        UUID order = sales.confirmedOrder(o, sales.line(o.variant(), "4", null));
        sales.postedDelivery(o, order, null);
        UUID line = sales.orderLines(o, order).getFirst();
        UUID invoice = id(sales.create(o, "/invoices/from-order", OrgFixtures.map("salesOrderId", order)), 201);
        String created = sales.body(o, "/invoices/" + invoice);
        LocalDate date = LocalDate.parse(JsonPath.read(created, "$.invoiceDate"));
        mvc.perform(unsafe(patch(o.path("/invoices/" + invoice)))
                        .cookie(o.session())
                        .header("If-Match", etag(0))
                        .contentType("application/merge-patch+json")
                        .content(OrgFixtures.JSON_MAPPER.writeValueAsString(OrgFixtures.map(
                                "dueDate",
                                date.plusDays(10).toString(),
                                "notes",
                                "Partial",
                                "lines",
                                List.of(OrgFixtures.map("salesOrderLineId", line, "quantity", "2"))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value("55.0000"))
                .andExpect(jsonPath("$.dueDate").value(date.plusDays(10).toString()));
        mvc.perform(unsafe(patch(o.path("/invoices/" + invoice)))
                        .cookie(o.session())
                        .header("If-Match", etag(1))
                        .contentType("application/merge-patch+json")
                        .content(OrgFixtures.json("dueDate", date.minusDays(1).toString())))
                .andExpect(status().isUnprocessableContent());
        mvc.perform(unsafe(delete(o.path("/invoices/" + invoice)))
                        .cookie(o.session())
                        .header("If-Match", etag(1)))
                .andExpect(status().isNoContent());

        UUID cancelled = id(sales.create(o, "/invoices/from-order", OrgFixtures.map("salesOrderId", order)), 201);
        expect(sales.action(o, o.session(), "/invoices/" + cancelled + "/cancel", 0, null, null), 200);
        expect(sales.action(o, o.session(), "/invoices/" + cancelled + "/post", 1, "post-" + cancelled, null), 409);

        UUID posted = sales.postedInvoice(o, order);
        mvc.perform(unsafe(patch(o.path("/invoices/" + posted)))
                        .cookie(o.session())
                        .header("If-Match", etag(1))
                        .contentType("application/merge-patch+json")
                        .content(OrgFixtures.json("notes", "late")))
                .andExpect(status().isConflict());
        mvc.perform(unsafe(delete(o.path("/invoices/" + posted)))
                        .cookie(o.session())
                        .header("If-Match", etag(1)))
                .andExpect(status().isConflict());
        expect(sales.action(o, o.session(), "/invoices/" + posted + "/cancel", 1, null, null), 409);
        expect(sales.action(o, o.session(), "/invoices/" + posted + "/post", 1, "again-" + posted, null), 409);
        mvc.perform(get(o.path("/invoices")).cookie(o.session()).param("filter[status]", "POSTED"))
                .andExpect(jsonPath("$.data.length()").value(1));
        // Fully invoiced: no further invoice for the order.
        invoice(o.session(), order, line, "1").andExpect(status().isUnprocessableContent());
    }

    @Test
    void foreignCurrencyOrdersNeedARateAndBookInBaseCurrency() throws Exception {
        UUID euro = sales.customer(o.inv(), "EUROCUST", "EUR", o.terms(), o.taxCode(), null);
        Map<String, Object> body = sales.order(o, sales.line(o.variant(), "2", "40"));
        body.put("customerId", euro);
        UUID order = id(sales.create(o, o.manager(), "/sales-orders", body), 201);
        assertThat(sales.<String>read(o, "/sales-orders/" + order, "$.currencyCode"))
                .isEqualTo("EUR");
        expect(sales.action(o, o.session(), "/sales-orders/" + order + "/confirm", 0, "confirm-" + order, null), 422)
                .getResponse()
                .getContentAsString()
                .contains("EXCHANGE_RATE_MISSING");
        sales.rate(o, "EUR", "1.1");
        expect(sales.action(o, o.session(), "/sales-orders/" + order + "/confirm", 0, "confirm2-" + order, null), 200);
        assertThat(sales.<String>read(o, "/sales-orders/" + order, "$.exchangeRate"))
                .isEqualTo("1.1000000000");
        sales.postedDelivery(o, order, null);
        UUID invoice = sales.postedInvoice(o, order);
        String posted = sales.body(o, "/invoices/" + invoice);
        assertThat((String) JsonPath.read(posted, "$.total")).isEqualTo("88.0000");
        assertThat((String) JsonPath.read(posted, "$.totalBase")).isEqualTo("96.8000");
        assertThat((String) JsonPath.read(posted, "$.subtotalBase")).isEqualTo("88.0000");
    }

    // ------------------------------------------------------------------------------ helpers

    private ResultActions invoice(Cookie session, UUID order, UUID line, String quantity) throws Exception {
        return sales.create(o, session, "/invoices", invoiceBody(order, line, quantity));
    }

    private Map<String, Object> invoiceBody(UUID order, UUID line, String quantity) {
        return OrgFixtures.map(
                "documentType",
                "INVOICE",
                "customerId",
                o.customer(),
                "salesOrderId",
                order,
                "lines",
                List.of(OrgFixtures.map("salesOrderLineId", line, "quantity", quantity)));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> lineOf(Map<String, Object> body) {
        return ((List<Map<String, Object>>) body.get("lines")).getFirst();
    }

    private Map<String, Object> directLine(UUID variant, String quantity, String price) throws Exception {
        return OrgFixtures.map(
                "variantId",
                variant,
                "quantity",
                quantity,
                "uomId",
                UUID.fromString(sales.read(o, "/price-lists/" + o.priceList() + "/items", "$.data[0].uomId")),
                "unitPrice",
                price);
    }

    private ResultActions credit(UUID invoice, UUID invoiceLine, String quantity, String price) throws Exception {
        return sales.create(o, "/invoices", creditBody(invoice, invoiceLine, quantity, price));
    }

    private Map<String, Object> creditBody(UUID invoice, UUID invoiceLine, String quantity, String price) {
        Map<String, Object> line = OrgFixtures.map("originalInvoiceLineId", invoiceLine, "quantity", quantity);
        if (price != null) {
            line.put("unitPrice", price);
        }
        return OrgFixtures.map(
                "documentType",
                "CREDIT_NOTE",
                "customerId",
                o.customer(),
                "originalInvoiceId",
                invoice,
                "lines",
                List.of(line));
    }
}
