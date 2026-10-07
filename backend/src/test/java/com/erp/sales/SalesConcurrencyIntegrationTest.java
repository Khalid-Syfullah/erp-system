package com.erp.sales;

import static com.erp.support.AuthTestSupport.unsafe;
import static com.erp.support.OrgFixtures.etag;
import static com.erp.support.ProcurementFixtures.expect;
import static com.erp.support.ProcurementFixtures.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erp.inventory.api.InventoryFacade;
import com.erp.inventory.application.InventoryInvariantCheck;
import com.erp.support.IntegrationTest;
import com.erp.support.OrgFixtures;
import com.erp.support.SalesFixtures;
import com.erp.support.SalesFixtures.O2C;
import com.erp.support.TestDatabase;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Race-prone sales operations run in parallel (released by a barrier): concurrent deliveries never
 * over-deliver an order, a document is posted once, invoices and credit notes never exceed their
 * limits, concurrent confirmations respect the credit limit and the stock, a quotation is accepted
 * once. At most four parallel requests: the test connection pool has five connections.
 */
class SalesConcurrencyIntegrationTest extends IntegrationTest {

    private static final int THREADS = 4;

    @Autowired
    MockMvc mvc;

    @Autowired
    SalesFixtures sales;

    @Autowired
    InventoryFacade inventory;

    @Autowired
    InventoryInvariantCheck invariants;

    private final ExecutorService pool = Executors.newFixedThreadPool(THREADS);
    private O2C o;

    @BeforeEach
    void setUp() throws Exception {
        o = sales.setup();
        sales.stock(o, "20", "10");
    }

    @AfterEach
    void consistent() {
        pool.shutdownNow();
        assertThat(invariants.check(o.inv().company()).clean()).isTrue();
    }

    @Test
    void concurrentDeliveriesOfOneOrderNeverOverDeliver() throws Exception {
        UUID order = sales.confirmedOrder(o, sales.line(o.variant(), "10", null));
        UUID line = sales.orderLines(o, order).getFirst();
        List<UUID> deliveries = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            deliveries.add(id(sales.createDelivery(o, order, List.of(sales.deliveryLine(line, "6"))), 201));
        }

        List<MockHttpServletResponse> results =
                race(i -> postRequest("/deliveries/" + deliveries.get(i) + "/post", 0, "deliver-" + i + "-" + order));

        assertThat(results).filteredOn(r -> r.getStatus() == 200).hasSize(1);
        assertThat(results)
                .filteredOn(r -> r.getStatus() != 200)
                .allSatisfy(r -> assertThat((String) JsonPath.read(r.getContentAsString(), "$.code"))
                        .isEqualTo("QUANTITY_EXCEEDS_REMAINING"));
        assertThat(sales.onHand(o)).isEqualByComparingTo("14");
        assertThat(sales.<String>read(o, "/sales-orders/" + order, "$.lines[0].deliveredQuantityBase"))
                .isEqualTo("6.000000");
        assertThat(sales.<String>read(o, "/sales-orders/" + order, "$.status")).isEqualTo("PARTIALLY_DELIVERED");
    }

    /**
     * Cancelling an order locks the order, then its drafts; posting a delivery locks the delivery, then
     * the order. A cancellation that meets a posting in flight fails at once (NOWAIT) instead of
     * deadlocking with it, and leaves the posting to finish.
     */
    @Test
    void cancellingAnOrderWhoseDeliveryIsBeingPostedFailsFast() throws Exception {
        UUID order = sales.confirmedOrder(o, sales.line(o.variant(), "2", null));
        UUID delivery = id(sales.createDelivery(o, order, null), 201);
        int version = sales.<Integer>read(o, "/sales-orders/" + order, "$.version");

        try (Connection posting = TestDatabase.connectAs("erp_app", TestDatabase.APP_PASSWORD)) {
            posting.setAutoCommit(false);
            try (PreparedStatement lock = posting.prepareStatement(
                    "SELECT set_config('app.company_id', ?, true), (SELECT id FROM sales.deliveries WHERE id = ?::uuid FOR UPDATE)")) {
                lock.setString(1, o.inv().company().toString());
                lock.setString(2, delivery.toString());
                lock.execute();
            }
            long started = System.nanoTime();
            MockHttpServletResponse cancelled = sales.action(
                            o, o.session(), "/sales-orders/" + order + "/cancel", version, null, null)
                    .andReturn()
                    .getResponse();
            Duration took = Duration.ofNanos(System.nanoTime() - started);
            posting.rollback();

            assertThat(cancelled.getStatus()).isEqualTo(409);
            assertThat((String) JsonPath.read(cancelled.getContentAsString(), "$.code"))
                    .isEqualTo("RESOURCE_BUSY");
            assertThat(took).as("no wait for the lock timeout").isLessThan(Duration.ofSeconds(3));
        }
        // Without a posting in flight the order is cancelled together with its draft delivery.
        expect(sales.action(o, o.session(), "/sales-orders/" + order + "/cancel", version, null, null), 200);
        assertThat(sales.<String>read(o, "/deliveries/" + delivery, "$.status")).isEqualTo("CANCELLED");
    }

    @Test
    void aDeliveryIsPostedOnceEvenWhenPostedConcurrently() throws Exception {
        UUID order = sales.confirmedOrder(o, sales.line(o.variant(), "3", null));
        UUID delivery = id(sales.createDelivery(o, order, null), 201);

        List<MockHttpServletResponse> results =
                race(i -> postRequest("/deliveries/" + delivery + "/post", 0, "double-" + i + "-" + delivery));

        assertThat(results).filteredOn(r -> r.getStatus() == 200).hasSize(1);
        assertThat(results)
                .filteredOn(r -> r.getStatus() != 200)
                .extracting(MockHttpServletResponse::getStatus)
                .allMatch(s -> s == 409 || s == 412);
        assertThat(sales.onHand(o)).isEqualByComparingTo("17");
    }

    @Test
    void concurrentInvoicesNeverInvoiceTwice() throws Exception {
        UUID order = sales.confirmedOrder(o, sales.line(o.variant(), "4", null));
        sales.postedDelivery(o, order, null);
        List<UUID> invoices = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            invoices.add(id(sales.create(o, "/invoices/from-order", OrgFixtures.map("salesOrderId", order)), 201));
        }

        List<MockHttpServletResponse> results =
                race(2, i -> postRequest("/invoices/" + invoices.get(i) + "/post", 0, "invoice-" + i + "-" + order));

        assertThat(results).filteredOn(r -> r.getStatus() == 200).hasSize(1);
        assertThat(results)
                .filteredOn(r -> r.getStatus() != 200)
                .allSatisfy(r -> assertThat((String) JsonPath.read(r.getContentAsString(), "$.code"))
                        .isEqualTo("QUANTITY_EXCEEDS_REMAINING"));
        assertThat(sales.<String>read(o, "/sales-orders/" + order, "$.lines[0].invoicedQuantityBase"))
                .isEqualTo("4.000000");
    }

    @Test
    void concurrentCreditNotesNeverCreditMoreThanInvoiced() throws Exception {
        UUID order = sales.confirmedOrder(o, sales.line(o.variant(), "4", null));
        sales.postedDelivery(o, order, null);
        UUID invoice = sales.postedInvoice(o, order);
        UUID invoiceLine = sales.ids(o, "/invoices/" + invoice, "$.lines[*].id").getFirst();
        List<UUID> credits = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            credits.add(id(
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
                                    "lines",
                                    List.of(OrgFixtures.map("originalInvoiceLineId", invoiceLine, "quantity", "3")))),
                    201));
        }

        List<MockHttpServletResponse> results =
                race(2, i -> postRequest("/invoices/" + credits.get(i) + "/post", 0, "credit-" + i + "-" + invoice));

        assertThat(results).filteredOn(r -> r.getStatus() == 200).hasSize(1);
        assertThat(results)
                .filteredOn(r -> r.getStatus() != 200)
                .allSatisfy(r -> assertThat((String) JsonPath.read(r.getContentAsString(), "$.code"))
                        .isEqualTo("QUANTITY_EXCEEDS_REMAINING"));
        assertThat(sales.<String>read(o, "/invoices/" + invoice, "$.lines[0].creditedQuantityBase"))
                .isEqualTo("3.000000");
    }

    @Test
    void concurrentConfirmationsRespectTheCreditLimit() throws Exception {
        mvc.perform(unsafe(put(o.path("/settings/sales")))
                        .cookie(o.session())
                        .header("If-Match", etag(0))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OrgFixtures.json(
                                "defaultInvoicePolicy",
                                "DELIVERED",
                                "creditCheckMode",
                                "BLOCK",
                                "quotationValidityDays",
                                30,
                                "reserveOnConfirm",
                                false)))
                .andExpect(status().isOk());
        UUID customer = sales.customer(o.inv(), "TIGHT", "USD", o.terms(), o.taxCode(), "150");
        List<UUID> orders = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            Map<String, Object> body = sales.order(o, sales.line(o.variant(), "4", null));
            body.put("customerId", customer);
            orders.add(id(sales.create(o, "/sales-orders", body), 201));
        }

        List<MockHttpServletResponse> results = race(
                2, i -> postRequest("/sales-orders/" + orders.get(i) + "/confirm", 0, "confirm-" + i + "-" + customer));

        assertThat(results).filteredOn(r -> r.getStatus() == 200).hasSize(1);
        assertThat(results)
                .filteredOn(r -> r.getStatus() != 200)
                .allSatisfy(r -> assertThat((String) JsonPath.read(r.getContentAsString(), "$.code"))
                        .isEqualTo("CREDIT_LIMIT_EXCEEDED"));
    }

    @Test
    void concurrentConfirmationsNeverReserveMoreThanTheStock() throws Exception {
        List<UUID> orders = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            orders.add(sales.draftOrder(o, sales.line(o.variant(), "8", null)));
        }

        List<MockHttpServletResponse> results =
                race(i -> postRequest("/sales-orders/" + orders.get(i) + "/confirm", 0, "confirm-" + i + "-stock"));

        assertThat(results).allSatisfy(r -> assertThat(r.getStatus()).isEqualTo(200));
        BigDecimal reserved = BigDecimal.ZERO;
        for (UUID order : orders) {
            reserved = reserved.add(
                    new BigDecimal(sales.<String>read(o, "/sales-orders/" + order, "$.lines[0].reservedQuantityBase")));
        }
        assertThat(reserved).isEqualByComparingTo("20");
        assertThat(sales.inCompany(
                        o,
                        () -> inventory.availability(o.variant(), o.warehouse()).available()))
                .isEqualByComparingTo("0");
    }

    @Test
    void aQuotationIsAcceptedOnce() throws Exception {
        UUID quotation = id(sales.create(o, "/quotations", sales.order(o, sales.line(o.variant(), "1", null))), 201);
        expect(sales.action(o, o.session(), "/quotations/" + quotation + "/send", 0, null, null), 200);

        List<MockHttpServletResponse> results =
                race(i -> postRequest("/quotations/" + quotation + "/accept", 1, "accept-" + i + "-" + quotation));

        assertThat(results).filteredOn(r -> r.getStatus() == 201).hasSize(1);
        assertThat(results)
                .filteredOn(r -> r.getStatus() != 201)
                .extracting(MockHttpServletResponse::getStatus)
                .allMatch(s -> s == 409 || s == 412);
        assertThat(sales.<List<?>>read(o, "/sales-orders", "$.data")).hasSize(1);
    }

    // ------------------------------------------------------------------------------------------

    interface Request {
        MockHttpServletRequestBuilder create(int index) throws Exception;
    }

    private MockHttpServletRequestBuilder postRequest(String path, int version, String key) {
        return unsafe(post(o.path(path)))
                .cookie(o.session())
                .header("If-Match", etag(version))
                .header("Idempotency-Key", key);
    }

    private List<MockHttpServletResponse> race(Request request) throws Exception {
        return race(THREADS, request);
    }

    private List<MockHttpServletResponse> race(int count, Request request) throws Exception {
        CyclicBarrier start = new CyclicBarrier(count);
        List<Future<MockHttpServletResponse>> futures = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            MockHttpServletRequestBuilder builder = request.create(i);
            Callable<MockHttpServletResponse> task = () -> {
                start.await(10, TimeUnit.SECONDS);
                return mvc.perform(builder).andReturn().getResponse();
            };
            futures.add(pool.submit(task));
        }
        List<MockHttpServletResponse> results = new ArrayList<>();
        for (Future<MockHttpServletResponse> future : futures) {
            results.add(future.get(60, TimeUnit.SECONDS));
        }
        return results;
    }
}
