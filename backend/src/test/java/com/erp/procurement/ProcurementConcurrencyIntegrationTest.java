package com.erp.procurement;

import static com.erp.support.AuthTestSupport.unsafe;
import static com.erp.support.OrgFixtures.etag;
import static com.erp.support.ProcurementFixtures.expect;
import static com.erp.support.ProcurementFixtures.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.erp.inventory.application.InventoryInvariantCheck;
import com.erp.support.IntegrationTest;
import com.erp.support.InventoryFixtures;
import com.erp.support.OrgFixtures;
import com.erp.support.ProcurementFixtures;
import com.erp.support.ProcurementFixtures.P2P;
import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.List;
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
 * Race-prone procurement operations run in parallel (released by a barrier): concurrent receipts
 * of one order never over-receive, a document is posted once, concurrent bills never bill one
 * receipt twice, an order is approved once and a requisition converted once. At most four
 * parallel requests: the test connection pool has five connections.
 */
class ProcurementConcurrencyIntegrationTest extends IntegrationTest {

    private static final int THREADS = 4;

    @Autowired
    MockMvc mvc;

    @Autowired
    ProcurementFixtures proc;

    @Autowired
    InventoryFixtures inv;

    @Autowired
    InventoryInvariantCheck invariants;

    private final ExecutorService pool = Executors.newFixedThreadPool(THREADS);
    private P2P p;

    @BeforeEach
    void setUp() throws Exception {
        p = proc.setup();
    }

    @AfterEach
    void consistent() {
        pool.shutdownNow();
        assertThat(invariants.check(p.inv().company()).clean()).isTrue();
    }

    @Test
    void concurrentReceiptsOfOneOrderNeverOverReceive() throws Exception {
        UUID order = proc.approvedOrder(p, proc.orderLine(p.inv().variant(), "10", "1", null));
        UUID line = proc.orderLines(p, order).getFirst();
        List<UUID> receipts = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            receipts.add(id(proc.createReceipt(p, order, List.of(proc.receiptLine(line, "10"))), 201));
        }

        List<MockHttpServletResponse> results =
                race(i -> postRequest("/goods-receipts/" + receipts.get(i) + "/post", 0, "receive-" + i + "-" + order));

        assertThat(results).filteredOn(r -> r.getStatus() == 200).hasSize(1);
        assertThat(results)
                .filteredOn(r -> r.getStatus() != 200)
                .allSatisfy(r -> assertThat((String) JsonPath.read(r.getContentAsString(), "$.code"))
                        .isEqualTo("QUANTITY_EXCEEDS_REMAINING"));
        assertThat(inv.balance(p.inv(), p.inv().variant(), p.inv().warehouse().stock()))
                .isEqualByComparingTo("10");
        assertThat((String) JsonPath.read(proc.body(p, "/purchase-orders/" + order), "$.lines[0].receivedQuantityBase"))
                .isEqualTo("10.000000");
    }

    @Test
    void aReceiptIsPostedOnceEvenWhenPostedConcurrently() throws Exception {
        UUID order = proc.approvedOrder(p, proc.orderLine(p.inv().variant(), "3", "1", null));
        UUID receipt = id(proc.createReceipt(p, order, null), 201);

        List<MockHttpServletResponse> results =
                race(i -> postRequest("/goods-receipts/" + receipt + "/post", 0, "double-" + i + "-" + receipt));

        assertThat(results).filteredOn(r -> r.getStatus() == 200).hasSize(1);
        assertThat(results)
                .filteredOn(r -> r.getStatus() != 200)
                .extracting(MockHttpServletResponse::getStatus)
                .allMatch(s -> s == 409 || s == 412);
        assertThat(inv.balance(p.inv(), p.inv().variant(), p.inv().warehouse().stock()))
                .isEqualByComparingTo("3");
    }

    @Test
    void concurrentBillsNeverBillOneReceiptTwice() throws Exception {
        UUID order = proc.approvedOrder(p, proc.orderLine(p.inv().variant(), "10", "2", null));
        UUID receipt = proc.postedReceipt(p, order, null);
        UUID receiptLine = proc.receiptLines(p, receipt).getFirst();
        List<UUID> bills = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            bills.add(id(
                    mvc.perform(unsafe(post(p.path("/supplier-bills")))
                            .cookie(p.session())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(OrgFixtures.json(
                                    "documentType", "BILL",
                                    "supplierId", p.supplier(),
                                    "supplierInvoiceNumber", "RACE-" + i,
                                    "billDate", p.inv().today(),
                                    "lines",
                                            List.of(OrgFixtures.map(
                                                    "goodsReceiptLineId",
                                                    receiptLine,
                                                    "quantity",
                                                    "10",
                                                    "unitPrice",
                                                    "2"))))),
                    201));
        }

        List<MockHttpServletResponse> results =
                race(2, i -> postRequest("/supplier-bills/" + bills.get(i) + "/post", 0, "bill-" + i + "-" + receipt));

        assertThat(results).filteredOn(r -> r.getStatus() == 200).hasSize(1);
        assertThat(results)
                .filteredOn(r -> r.getStatus() != 200)
                .allSatisfy(r -> assertThat((String) JsonPath.read(r.getContentAsString(), "$.code"))
                        .isEqualTo("MATCH_EXCEPTION"));
        assertThat((String) JsonPath.read(proc.body(p, "/goods-receipts/" + receipt), "$.lines[0].billedQuantityBase"))
                .isEqualTo("10.000000");
    }

    @Test
    void anOrderIsApprovedOnce() throws Exception {
        UUID order = id(proc.createOrder(p, proc.order(p, proc.orderLine(p.inv().variant(), "1", "1", null))), 201);
        expect(proc.action(p, p.session(), "/purchase-orders/" + order + "/submit", 0, null, null), 200);

        List<MockHttpServletResponse> results = race(i -> unsafe(post(p.path("/purchase-orders/" + order + "/approve")))
                .cookie(p.approver())
                .header("If-Match", etag(1))
                .header("Idempotency-Key", "approve-" + i + "-" + order));

        assertThat(results).filteredOn(r -> r.getStatus() == 200).hasSize(1);
        assertThat(results)
                .filteredOn(r -> r.getStatus() != 200)
                .extracting(MockHttpServletResponse::getStatus)
                .allMatch(s -> s == 409 || s == 412);
    }

    @Test
    void aRequisitionIsConvertedOnce() throws Exception {
        UUID requisition = id(
                mvc.perform(unsafe(post(p.path("/purchase-requisitions")))
                        .cookie(p.session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OrgFixtures.json(
                                "branchId", p.inv().branch(),
                                "lines",
                                        List.of(OrgFixtures.map(
                                                "variantId",
                                                p.inv().variant(),
                                                "quantity",
                                                "5",
                                                "uomId",
                                                inv.uom("EA")))))),
                201);
        expect(proc.action(p, p.session(), "/purchase-requisitions/" + requisition + "/submit", 0, null, null), 200);
        expect(proc.action(p, p.approver(), "/purchase-requisitions/" + requisition + "/approve", 1, null, null), 200);

        List<MockHttpServletResponse> results = race(i -> postRequest(
                        "/purchase-requisitions/" + requisition + "/convert", 2, "convert-" + i + "-" + requisition)
                .contentType(MediaType.APPLICATION_JSON)
                .content(OrgFixtures.json(
                        "supplierId",
                        p.supplier(),
                        "warehouseId",
                        p.inv().warehouse().id())));

        assertThat(results).filteredOn(r -> r.getStatus() == 201).hasSize(1);
        assertThat(results)
                .filteredOn(r -> r.getStatus() != 201)
                .extracting(MockHttpServletResponse::getStatus)
                .allMatch(s -> s == 409 || s == 412 || s == 422);
        assertThat((String) JsonPath.read(
                        proc.body(p, "/purchase-requisitions/" + requisition), "$.lines[0].orderedQuantityBase"))
                .isEqualTo("5.000000");
    }

    // ------------------------------------------------------------------------------------------

    interface Request {
        MockHttpServletRequestBuilder create(int index) throws Exception;
    }

    private MockHttpServletRequestBuilder postRequest(String path, int version, String key) {
        return unsafe(post(p.path(path)))
                .cookie(p.session())
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
