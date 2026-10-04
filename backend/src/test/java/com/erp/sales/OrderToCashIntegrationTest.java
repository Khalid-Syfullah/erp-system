package com.erp.sales;

import static com.erp.support.ProcurementFixtures.expect;
import static com.erp.support.ProcurementFixtures.id;
import static org.assertj.core.api.Assertions.assertThat;

import com.erp.inventory.api.InventoryFacade;
import com.erp.inventory.application.InventoryInvariantCheck;
import com.erp.sales.events.InvoicePosted;
import com.erp.sales.events.SalesOrderConfirmed;
import com.erp.support.IntegrationTest;
import com.erp.support.OrgFixtures;
import com.erp.support.SalesFixtures;
import com.erp.support.SalesFixtures.O2C;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

/**
 * The order-to-cash flow through the API (DEVELOPMENT_PLAN.md Phase 7): quotation → acceptance →
 * confirmed order (credit check, reservation) → delivery (stock issue at cost) → invoice (event for
 * Accounting) → return (stock back at the delivery cost) → credit note. Payments are Accounting's
 * (Phase 8, ADR-037): the settlement port answers UNKNOWN until then.
 */
@RecordApplicationEvents
class OrderToCashIntegrationTest extends IntegrationTest {

    @Autowired
    SalesFixtures sales;

    @Autowired
    InventoryFacade inventory;

    @Autowired
    InventoryInvariantCheck invariants;

    @Autowired
    DSLContext dsl;

    @Autowired
    ApplicationEvents events;

    private O2C o;

    @BeforeEach
    void setUp() throws Exception {
        o = sales.setup();
        sales.stock(o, "20", "10");
    }

    @AfterEach
    void inventoryStaysConsistent() {
        assertThat(invariants.check(o.inv().company()).clean()).isTrue();
    }

    @Test
    void quotationToCreditNote() throws Exception {
        // Quotation priced from the default price list (25 per EA) with the customer's 10 % tax code.
        UUID quotation = id(sales.create(o, "/quotations", sales.order(o, sales.line(o.variant(), "4", null))), 201);
        String q = sales.body(o, "/quotations/" + quotation);
        assertThat((String) JsonPath.read(q, "$.status")).isEqualTo("DRAFT");
        assertThat((String) JsonPath.read(q, "$.lines[0].unitPrice")).isEqualTo("25.000000");
        assertThat((String) JsonPath.read(q, "$.total")).isEqualTo("110.0000");
        assertThat((String) JsonPath.read(q, "$.priceListId"))
                .isEqualTo(o.priceList().toString());

        expect(sales.action(o, o.session(), "/quotations/" + quotation + "/send", 0, null, null), 200);
        assertThat(sales.<String>read(o, "/quotations/" + quotation, "$.number"))
                .startsWith("QT-");

        // Accepting creates a draft order with the quoted lines.
        UUID order = id(sales.action(o, o.session(), "/quotations/" + quotation + "/accept", 1, null, null), 201);
        assertThat(sales.<String>read(o, "/quotations/" + quotation, "$.status"))
                .isEqualTo("ACCEPTED");
        assertThat(sales.<String>read(o, "/quotations/" + quotation, "$.salesOrderId"))
                .isEqualTo(order.toString());
        String draft = sales.body(o, "/sales-orders/" + order);
        assertThat((String) JsonPath.read(draft, "$.status")).isEqualTo("DRAFT");
        assertThat((String) JsonPath.read(draft, "$.quotationId")).isEqualTo(quotation.toString());
        assertThat((String) JsonPath.read(draft, "$.total")).isEqualTo("110.0000");

        // Confirmation: numbered, credit-checked, stock reserved.
        expect(sales.action(o, o.session(), "/sales-orders/" + order + "/confirm", 0, "confirm-" + order, null), 200);
        String confirmed = sales.body(o, "/sales-orders/" + order);
        assertThat((String) JsonPath.read(confirmed, "$.status")).isEqualTo("CONFIRMED");
        assertThat((String) JsonPath.read(confirmed, "$.number")).startsWith("SO-");
        assertThat((String) JsonPath.read(confirmed, "$.creditCheckResult")).isEqualTo("PASSED");
        assertThat((String) JsonPath.read(confirmed, "$.lines[0].reservedQuantityBase"))
                .isEqualTo("4.000000");
        assertThat(sales.inCompany(
                        o,
                        () -> inventory.availability(o.variant(), o.warehouse()).reserved()))
                .isEqualByComparingTo("4");
        assertThat(events.stream(SalesOrderConfirmed.class)).hasSize(1);

        // Delivery: stock out at the moving average, the reservation consumed.
        UUID delivery = sales.postedDelivery(o, order, null);
        String d = sales.body(o, "/deliveries/" + delivery);
        assertThat((String) JsonPath.read(d, "$.status")).isEqualTo("POSTED");
        assertThat((String) JsonPath.read(d, "$.number")).startsWith("DL-");
        assertThat((String) JsonPath.read(d, "$.lines[0].unitCostBase")).isEqualTo("10.000000");
        assertThat(sales.onHand(o)).isEqualByComparingTo("16");
        assertThat(sales.inCompany(
                        o,
                        () -> inventory.availability(o.variant(), o.warehouse()).reserved()))
                .isEqualByComparingTo("0");
        String delivered = sales.body(o, "/sales-orders/" + order);
        assertThat((String) JsonPath.read(delivered, "$.status")).isEqualTo("DELIVERED");
        assertThat((String) JsonPath.read(delivered, "$.lines[0].deliveredQuantityBase"))
                .isEqualTo("4.000000");
        assertThat((String) JsonPath.read(delivered, "$.lines[0].reservedQuantityBase"))
                .isEqualTo("0.000000");

        // Invoice: the delivered quantity; posting closes the fully delivered and invoiced order.
        UUID invoice = sales.postedInvoice(o, order);
        String inv = sales.body(o, "/invoices/" + invoice);
        assertThat((String) JsonPath.read(inv, "$.number")).startsWith("INV-");
        assertThat((String) JsonPath.read(inv, "$.total")).isEqualTo("110.0000");
        assertThat((String) JsonPath.read(inv, "$.totalBase")).isEqualTo("110.0000");
        assertThat((String) JsonPath.read(inv, "$.taxes[0].taxAmount")).isEqualTo("10.0000");
        String closed = sales.body(o, "/sales-orders/" + order);
        assertThat((String) JsonPath.read(closed, "$.status")).isEqualTo("CLOSED");
        assertThat((String) JsonPath.read(closed, "$.invoiceStatus")).isEqualTo("INVOICED");
        InvoicePosted posted = events.stream(InvoicePosted.class).findFirst().orElseThrow();
        assertThat(posted.metadata().eventType()).isEqualTo(InvoicePosted.INVOICE_TYPE);
        assertThat(posted.totals().total()).isEqualByComparingTo("110");
        assertThat(posted.lines()).singleElement().satisfies(l -> {
            assertThat(l.categoryId()).isEqualTo(o.inv().category());
            assertThat(l.netBase()).isEqualByComparingTo("100");
        });
        assertThat(posted.taxLines())
                .singleElement()
                .satisfies(t -> assertThat(t.taxBase()).isEqualByComparingTo("10"));
        assertThat(sales.<String>read(o, "/invoices/" + invoice + "/settlement", "$.status"))
                .isEqualTo("OPEN");
        assertThat(sales.<String>read(o, "/invoices/" + invoice + "/settlement", "$.openAmount"))
                .isEqualTo("110.0000");

        // Return of 1 EA: stock back at the delivery's cost.
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
                                "Damaged in transit",
                                "lines",
                                List.of(OrgFixtures.map("deliveryLineId", deliveryLine, "quantity", "1")))),
                201);
        expect(
                sales.action(
                        o, o.session(), "/sales-returns/" + salesReturn + "/receive", 0, "rcv-" + salesReturn, null),
                200);
        assertThat(sales.onHand(o)).isEqualByComparingTo("17");
        assertThat(sales.<String>read(o, "/sales-returns/" + salesReturn, "$.lines[0].unitCostBase"))
                .isEqualTo("10.000000");
        assertThat(sales.<String>read(o, "/deliveries/" + delivery, "$.lines[0].returnedQuantityBase"))
                .isEqualTo("1.000000");

        // Credit note for the returned unit: the order line gets its invoiceable quantity back.
        UUID invoiceLine = sales.ids(o, "/invoices/" + invoice, "$.lines[*].id").getFirst();
        UUID returnLine =
                sales.ids(o, "/sales-returns/" + salesReturn, "$.lines[*].id").getFirst();
        UUID creditNote = id(
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
        assertThat(sales.<String>read(o, "/invoices/" + creditNote, "$.total")).isEqualTo("27.5000");
        expect(sales.action(o, o.session(), "/invoices/" + creditNote + "/post", 0, "post-" + creditNote, null), 200);
        assertThat(sales.<String>read(o, "/invoices/" + creditNote, "$.number")).startsWith("CN-");
        assertThat(sales.<String>read(o, "/invoices/" + invoice, "$.lines[0].creditedQuantityBase"))
                .isEqualTo("1.000000");
        String afterCredit = sales.body(o, "/sales-orders/" + order);
        assertThat((String) JsonPath.read(afterCredit, "$.lines[0].invoicedQuantityBase"))
                .isEqualTo("3.000000");
        assertThat((String) JsonPath.read(afterCredit, "$.lines[0].returnedQuantityBase"))
                .isEqualTo("1.000000");
        assertThat((String) JsonPath.read(afterCredit, "$.invoiceStatus")).isEqualTo("INVOICED");
        assertThat(events.stream(InvoicePosted.class)
                        .filter(e -> e.metadata().eventType().equals(InvoicePosted.CREDIT_NOTE_TYPE)))
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.originalInvoiceId()).isEqualTo(invoice);
                    assertThat(e.salesReturnId()).isEqualTo(salesReturn);
                    assertThat(e.totals().total()).isEqualByComparingTo("27.5");
                });

        // Every step left an audit record.
        List<String> audited = sales.inCompany(
                o,
                () -> dsl.fetch(
                                "SELECT entity_type || ':' || action FROM admin.audit_log"
                                        + " WHERE company_id = ? AND module = 'sales'",
                                o.inv().company())
                        .getValues(0, String.class));
        assertThat(audited)
                .contains(
                        "quotation:CREATE",
                        "quotation:STATE_CHANGE",
                        "sales_order:CREATE",
                        "sales_order:STATE_CHANGE",
                        "delivery:CREATE",
                        "delivery:POST",
                        "invoice:CREATE",
                        "invoice:POST",
                        "sales_return:RECEIVE");
    }

    @Test
    void partialDeliveriesFollowTheReservationAndBackorder() throws Exception {
        UUID order = sales.confirmedOrder(o, sales.line(o.variant(), "30", null));
        String confirmed = sales.body(o, "/sales-orders/" + order);
        assertThat((String) JsonPath.read(confirmed, "$.lines[0].reservedQuantityBase"))
                .isEqualTo("20.000000");
        assertThat((String) JsonPath.read(confirmed, "$.lines[0].backorderQuantityBase"))
                .isEqualTo("10.000000");
        UUID line = sales.orderLines(o, order).getFirst();

        sales.postedDelivery(o, order, List.of(sales.deliveryLine(line, "15")));
        String partial = sales.body(o, "/sales-orders/" + order);
        assertThat((String) JsonPath.read(partial, "$.status")).isEqualTo("PARTIALLY_DELIVERED");
        assertThat((String) JsonPath.read(partial, "$.lines[0].reservedQuantityBase"))
                .isEqualTo("5.000000");

        // More stock arrives: the backorder is reserved on request.
        sales.stock(o, "20", "13");
        int version = sales.version(o, "/sales-orders/" + order);
        expect(sales.action(o, o.session(), "/sales-orders/" + order + "/reserve", version, null, null), 200);
        assertThat(sales.<String>read(o, "/sales-orders/" + order, "$.lines[0].reservedQuantityBase"))
                .isEqualTo("15.000000");

        // Delivering more than is open is refused (SAL-4).
        UUID tooMuch = id(sales.createDelivery(o, order, List.of(sales.deliveryLine(line, "16"))), 201);
        String refused = expect(
                        sales.action(
                                o, o.session(), "/deliveries/" + tooMuch + "/post", 0, "too-much-" + tooMuch, null),
                        422)
                .getResponse()
                .getContentAsString();
        assertThat((String) JsonPath.read(refused, "$.code")).isEqualTo("QUANTITY_EXCEEDS_REMAINING");

        sales.postedDelivery(o, order, List.of(sales.deliveryLine(line, "15")));
        String done = sales.body(o, "/sales-orders/" + order);
        assertThat((String) JsonPath.read(done, "$.status")).isEqualTo("DELIVERED");
        assertThat((String) JsonPath.read(done, "$.invoiceStatus")).isEqualTo("NOT_INVOICED");
        assertThat(sales.inCompany(
                        o,
                        () -> inventory.availability(o.variant(), o.warehouse()).reserved()))
                .isEqualByComparingTo("0");
        assertThat(sales.onHand(o)).isEqualByComparingTo("10");

        // Invoicing in two steps: the order closes with the last one.
        UUID first = id(
                sales.create(
                        o,
                        "/invoices",
                        OrgFixtures.map(
                                "documentType",
                                "INVOICE",
                                "customerId",
                                o.customer(),
                                "salesOrderId",
                                order,
                                "lines",
                                List.of(OrgFixtures.map("salesOrderLineId", line, "quantity", "10")))),
                201);
        expect(sales.action(o, o.session(), "/invoices/" + first + "/post", 0, "post-" + first, null), 200);
        assertThat(sales.<String>read(o, "/sales-orders/" + order, "$.invoiceStatus"))
                .isEqualTo("PARTIALLY_INVOICED");
        UUID rest = sales.postedInvoice(o, order);
        assertThat(sales.<String>read(o, "/invoices/" + rest, "$.lines[0].quantityBase"))
                .isEqualTo("20.000000");
        assertThat(sales.<String>read(o, "/sales-orders/" + order, "$.status")).isEqualTo("CLOSED");
    }

    @Test
    void orderedPolicyInvoicesBeforeDelivery() throws Exception {
        java.util.Map<String, Object> body = sales.order(o, sales.line(o.variant(), "2", null));
        body.put("invoicePolicy", "ORDERED");
        UUID order = id(sales.create(o, "/sales-orders", body), 201);
        expect(sales.action(o, o.session(), "/sales-orders/" + order + "/confirm", 0, "confirm-" + order, null), 200);

        UUID invoice = sales.postedInvoice(o, order);
        assertThat(sales.<String>read(o, "/invoices/" + invoice, "$.total")).isEqualTo("55.0000");
        String invoiced = sales.body(o, "/sales-orders/" + order);
        assertThat((String) JsonPath.read(invoiced, "$.invoiceStatus")).isEqualTo("INVOICED");
        assertThat((String) JsonPath.read(invoiced, "$.status")).isEqualTo("CONFIRMED");

        sales.postedDelivery(o, order, null);
        assertThat(sales.<String>read(o, "/sales-orders/" + order, "$.status")).isEqualTo("CLOSED");
        assertThat(sales.onHand(o)).isEqualByComparingTo(new BigDecimal("18"));
    }
}
