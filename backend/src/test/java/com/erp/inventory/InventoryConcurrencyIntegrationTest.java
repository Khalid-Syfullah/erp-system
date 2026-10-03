package com.erp.inventory;

import static com.erp.support.AuthTestSupport.unsafe;
import static com.erp.support.OrgFixtures.etag;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.erp.inventory.api.InventoryFacade;
import com.erp.inventory.api.InventoryFacade.InLine;
import com.erp.inventory.api.InventoryFacade.OutLine;
import com.erp.inventory.api.InventoryFacade.ReservationRequest;
import com.erp.inventory.api.InventoryFacade.SourceRef;
import com.erp.inventory.api.InventoryFacade.StockInRequest;
import com.erp.inventory.api.InventoryFacade.StockOutRequest;
import com.erp.inventory.application.InventoryErrorCode;
import com.erp.inventory.application.InventoryInvariantCheck;
import com.erp.platform.web.ApiException;
import com.erp.support.IntegrationTest;
import com.erp.support.InventoryFixtures;
import com.erp.support.InventoryFixtures.Setup;
import com.erp.support.InventoryFixtures.Warehouse;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
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
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Race-prone stock operations run truly in parallel (released together by a barrier). Whatever the
 * interleaving, stock never goes negative, nothing is applied twice, transfers are all-or-nothing and
 * the balances, reservations and valuations stay consistent with the ledger.
 *
 * <p>At most four parallel requests: the test connection pool has five connections.
 */
class InventoryConcurrencyIntegrationTest extends IntegrationTest {

    private static final int THREADS = 4;

    @Autowired
    MockMvc mvc;

    @Autowired
    InventoryFixtures inv;

    @Autowired
    InventoryFacade facade;

    @Autowired
    InventoryInvariantCheck invariants;

    private final ExecutorService pool = Executors.newFixedThreadPool(THREADS);
    private Setup s;
    private Warehouse wh;
    private UUID ea;

    @BeforeEach
    void setUp() throws Exception {
        s = inv.setup();
        wh = s.warehouse();
        ea = inv.uom("EA");
    }

    @AfterEach
    void consistent() {
        pool.shutdownNow();
        assertThat(invariants.check(s.company()).clean())
                .as("ledger, balances, reservations and valuations agree")
                .isTrue();
    }

    @Test
    void parallelIssuesNeverOverdrawStock() throws Exception {
        inv.opening(s, s.variant(), wh.stock(), "5", "1");

        List<Outcome> outcomes = race(THREADS, i -> () -> issue("2", null));

        assertThat(outcomes).filteredOn(Outcome::ok).hasSize(2);
        assertThat(outcomes)
                .filteredOn(o -> !o.ok())
                .extracting(Outcome::code)
                .containsOnly(InventoryErrorCode.INSUFFICIENT_STOCK.code());
        assertThat(inv.balance(s, s.variant(), wh.stock())).isEqualByComparingTo("1");
    }

    @Test
    void reservationAndDeliveryCannotBothTakeTheLastUnits() throws Exception {
        inv.opening(s, s.variant(), wh.stock(), "4", "1");

        List<Outcome> outcomes = race(2, i -> i == 0 ? () -> reserve("4") : () -> issue("4", null));

        assertThat(outcomes).filteredOn(Outcome::ok).hasSize(1);
        var availability = inv.inCompany(s, () -> facade.availability(s.variant(), wh.id()));
        assertThat(availability.available()).isEqualByComparingTo("0");
        assertThat(availability.reserved()).isLessThanOrEqualTo(availability.onHand());
    }

    @Test
    void crossingTransfersBothCompleteAtomically() throws Exception {
        Warehouse other = inv.warehouse(s, "WH2");
        inv.opening(s, s.variant(), wh.stock(), "5", "2");
        inv.opening(s, s.variant(), other.stock(), "5", "2");
        List<UUID> drafts = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            boolean outbound = i % 2 == 0;
            Warehouse from = outbound ? wh : other;
            Warehouse to = outbound ? other : wh;
            drafts.add(draft(inv.movement(
                    s,
                    "TRANSFER",
                    from.id(),
                    "destWarehouseId",
                    to.id(),
                    "lines",
                    List.of(inv.line(s.variant(), from.stock(), to.stock(), "2")))));
        }

        List<Outcome> outcomes = race(THREADS, i -> () -> http(postDraft(drafts.get(i), "transfer-" + i)));

        // Each warehouse can always cover its own outgoing transfers, so all must succeed (a deadlock
        // between the crossing transfers would be retried by the platform).
        assertThat(outcomes).allMatch(Outcome::ok, "posted");
        assertThat(inv.balance(s, s.variant(), wh.stock())).isEqualByComparingTo("5");
        assertThat(inv.balance(s, s.variant(), other.stock())).isEqualByComparingTo("5");
    }

    @Test
    void aDraftIsPostedOnceEvenWhenPostedConcurrently() throws Exception {
        UUID draft = draft(openingBody("3"));

        List<Outcome> outcomes = race(THREADS, i -> () -> http(postDraft(draft, "double-" + i)));

        assertThat(outcomes).filteredOn(Outcome::ok).hasSize(1);
        assertThat(outcomes)
                .filteredOn(o -> !o.ok())
                .extracting(Outcome::status)
                .allMatch(code -> code == 409 || code == 412);
        assertThat(inv.balance(s, s.variant(), wh.stock())).isEqualByComparingTo("3");
    }

    @Test
    void theSameIdempotencyKeyRunsTheCommandOnce() throws Exception {
        UUID draft = draft(openingBody("3"));

        List<Outcome> outcomes = race(THREADS, i -> () -> http(postDraft(draft, "same-key-" + draft)));

        // One execution; concurrent duplicates wait for it and replay the stored response.
        assertThat(outcomes).allMatch(Outcome::ok, "succeeded or replayed");
        assertThat(outcomes)
                .extracting(Outcome::body)
                .allMatch(outcomes.getFirst().body()::equals);
        assertThat(outcomes).filteredOn(Outcome::replayed).hasSize(THREADS - 1);
        assertThat(inv.balance(s, s.variant(), wh.stock())).isEqualByComparingTo("3");
    }

    @Test
    void documentNumbersStayGaplessUnderLoad() throws Exception {
        int perThread = 8;
        List<Outcome> outcomes = race(THREADS, i -> () -> {
            List<String> numbers = new ArrayList<>();
            for (int n = 0; n < perThread; n++) {
                numbers.add(receive("1").number());
                if (n % 3 == 0) {
                    // A failing posting in between must not consume a number.
                    try {
                        issue("1000", null);
                    } catch (ApiException expected) {
                        // insufficient stock
                    }
                }
            }
            return String.join(",", numbers);
        });

        List<Integer> numbers = new ArrayList<>();
        for (Outcome outcome : outcomes) {
            assertThat(outcome.ok()).as(outcome.body()).isTrue();
            for (String number : outcome.body().split(",")) {
                numbers.add(Integer.parseInt(number.substring(number.lastIndexOf('-') + 1)));
            }
        }
        Collections.sort(numbers);
        List<Integer> expected = new ArrayList<>();
        for (int n = 1; n <= THREADS * perThread; n++) {
            expected.add(n);
        }
        assertThat(numbers).isEqualTo(expected);
    }

    // ------------------------------------------------------------------------------------------

    /** The result of one racing task: success with a body, or a failure status/code. */
    record Outcome(boolean ok, int status, String code, String body, boolean replayed) {}

    interface Task {
        Callable<Object> create(int index);
    }

    /** Runs {@code count} tasks released at the same instant and collects their outcomes. */
    private List<Outcome> race(int count, Task task) throws Exception {
        CyclicBarrier start = new CyclicBarrier(count);
        List<Future<Outcome>> futures = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Callable<Object> work = task.create(i);
            futures.add(pool.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                try {
                    Object result = work.call();
                    if (result instanceof Outcome outcome) {
                        return outcome;
                    }
                    return new Outcome(true, 200, null, String.valueOf(result), false);
                } catch (ApiException e) {
                    return new Outcome(
                            false, e.errorCode().status().value(), e.errorCode().code(), e.getMessage(), false);
                }
            }));
        }
        List<Outcome> outcomes = new ArrayList<>();
        for (Future<Outcome> future : futures) {
            outcomes.add(future.get(60, TimeUnit.SECONDS));
        }
        return outcomes;
    }

    private Outcome http(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
            throws Exception {
        MockHttpServletResponse response =
                mvc.perform(request.cookie(s.session())).andReturn().getResponse();
        String body = response.getContentAsString();
        boolean ok = response.getStatus() == 200;
        String code = ok ? null : JsonPath.read(body, "$.code");
        String number = ok ? JsonPath.read(body, "$.number") : body;
        return new Outcome(
                ok, response.getStatus(), code, number, "true".equals(response.getHeader("Idempotent-Replayed")));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder postDraft(
            UUID draft, String key) {
        return unsafe(post(s.path("/stock-movements/" + draft + "/post")))
                .header("If-Match", etag(0))
                .header("Idempotency-Key", key);
    }

    private UUID draft(java.util.Map<String, Object> body) throws Exception {
        MockHttpServletResponse response =
                inv.createMovement(s, body).andReturn().getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(201);
        return UUID.fromString(JsonPath.read(response.getContentAsString(), "$.id"));
    }

    private java.util.Map<String, Object> openingBody(String qty) {
        var line = inv.line(s.variant(), null, wh.stock(), qty);
        line.put("unitCostBase", "1");
        return inv.movement(s, "OPENING", wh.id(), "lines", List.of(line));
    }

    private InventoryFacade.PostedMovement receive(String qty) {
        return inv.inCompany(
                s,
                () -> facade.receive(new StockInRequest(
                        new SourceRef("procurement", "GOODS_RECEIPT", UUID.randomUUID(), null),
                        s.today(),
                        wh.id(),
                        null,
                        List.of(new InLine(s.variant(), new BigDecimal(qty), ea, null, BigDecimal.ONE, null)),
                        null)));
    }

    private String issue(String qty, UUID reservation) {
        return inv.inCompany(
                        s,
                        () -> facade.issue(new StockOutRequest(
                                new SourceRef("sales", "DELIVERY", UUID.randomUUID(), null),
                                s.today(),
                                wh.id(),
                                null,
                                List.of(new OutLine(
                                        s.variant(), new BigDecimal(qty), ea, null, reservation, null, null)),
                                null)))
                .number();
    }

    private String reserve(String qty) {
        return String.valueOf(inv.inCompany(
                        s,
                        () -> facade.reserve(new ReservationRequest(
                                new SourceRef("sales", "ORDER", UUID.randomUUID(), null),
                                UUID.randomUUID(),
                                s.variant(),
                                wh.id(),
                                new BigDecimal(qty),
                                false)))
                .reservationId());
    }
}
