package com.erp.accounting;

import static com.erp.support.AuthTestSupport.unsafe;
import static com.erp.support.OrgFixtures.etag;
import static com.erp.support.ProcurementFixtures.expect;
import static com.erp.support.ProcurementFixtures.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.erp.accounting.application.LedgerInvariantCheck;
import com.erp.platform.context.CurrentContext;
import com.erp.platform.context.RequestContext;
import com.erp.support.AccountingFixtures;
import com.erp.support.AccountingFixtures.Books;
import com.erp.support.AccountingFixtures.Period;
import com.erp.support.IntegrationTest;
import com.erp.support.OrgFixtures;
import com.erp.support.SalesFixtures;
import com.erp.support.SalesFixtures.O2C;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
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
import java.util.function.IntFunction;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Race-prone accounting operations run in parallel (released by a barrier): an entry is posted once,
 * entry numbers stay gapless, an invoice is never over-settled by concurrent allocations, and a
 * period closes either before or after a concurrent posting, never in between. At most four
 * parallel requests: the test connection pool has five connections.
 */
class AccountingConcurrencyIntegrationTest extends IntegrationTest {

    private static final int THREADS = 4;

    @Autowired
    MockMvc mvc;

    @Autowired
    AccountingFixtures acc;

    @Autowired
    SalesFixtures sales;

    @Autowired
    LedgerInvariantCheck invariants;

    @Autowired
    DSLContext dsl;

    @Autowired
    TransactionTemplate tx;

    private final ExecutorService pool = Executors.newFixedThreadPool(THREADS);
    private Books b;

    @BeforeEach
    void setUp() throws Exception {
        b = acc.setup();
    }

    @AfterEach
    void consistent() {
        pool.shutdownNow();
        assertThat(invariants.check(b.company()).clean()).isTrue();
    }

    @Test
    void anEntryIsPostedOnceWhenPostedConcurrently() throws Exception {
        UUID entry = id(
                acc.create(
                        b,
                        b.session(),
                        "/journal-entries",
                        acc.entry(b, b.today(), Map.of("6000", "10", "3000", "-10"))),
                201);

        List<MockHttpServletResponse> results = race(
                THREADS,
                i -> unsafe(post(b.path("/journal-entries/" + entry + "/post")))
                        .cookie(b.session())
                        .header("If-Match", etag(0))
                        .header("Idempotency-Key", "post-" + i + "-" + entry));

        assertThat(results).filteredOn(r -> r.getStatus() == 200).hasSize(1);
        assertThat(results)
                .filteredOn(r -> r.getStatus() != 200)
                .extracting(MockHttpServletResponse::getStatus)
                .allMatch(s -> s == 409 || s == 412);
        assertThat(acc.balance(b, "6000")).isEqualByComparingTo("10");
    }

    @Test
    void entryNumbersStayGaplessUnderConcurrentPosting() throws Exception {
        List<UUID> entries = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            entries.add(id(
                    acc.create(
                            b,
                            b.session(),
                            "/journal-entries",
                            acc.entry(b, b.today(), Map.of("6000", String.valueOf(i + 1), "3000", "-" + (i + 1)))),
                    201));
        }

        List<MockHttpServletResponse> results = race(
                THREADS,
                i -> unsafe(post(b.path("/journal-entries/" + entries.get(i) + "/post")))
                        .cookie(b.session())
                        .header("If-Match", etag(0))
                        .header("Idempotency-Key", "gapless-" + entries.get(i)));

        assertThat(results).extracting(MockHttpServletResponse::getStatus).containsOnly(200);
        List<Integer> sequence = new ArrayList<>();
        for (UUID entry : entries) {
            String number = acc.read(b, "/journal-entries/" + entry, "$.number");
            sequence.add(Integer.parseInt(number.substring(number.lastIndexOf('-') + 1)));
        }
        assertThat(sequence).containsExactlyInAnyOrder(1, 2, 3, 4);
        assertThat(acc.balance(b, "6000")).isEqualByComparingTo("10");
    }

    @Test
    void concurrentAllocationsNeverOverSettleAnInvoice() throws Exception {
        O2C o = sales.setup();
        b = acc.books(o.inv().company());
        UUID service = sales.service(o, "SVC");
        UUID order = id(
                sales.create(
                        o,
                        "/sales-orders",
                        OrgFixtures.map(
                                "customerId",
                                o.customer(),
                                "warehouseId",
                                o.warehouse(),
                                "lines",
                                List.of(sales.line(service, "4", "25")))),
                201);
        expect(sales.action(o, o.session(), "/sales-orders/" + order + "/confirm", 0, "confirm-" + order, null), 200);
        UUID invoice = sales.postedInvoice(o, order);
        UUID item =
                UUID.fromString((String) acc.openItem(b, "receivables", invoice).get("id"));
        List<UUID> payments = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            payments.add(
                    acc.postedPayment(b, acc.payment(b, "INBOUND", o.customer(), b.bankAccount(), "110", Map.of())));
        }

        List<MockHttpServletResponse> results = race(
                2,
                i -> unsafe(post(b.path("/payments/" + payments.get(i) + "/allocations")))
                        .cookie(b.session())
                        .header("If-Match", etag(1))
                        .header("Idempotency-Key", "alloc-" + payments.get(i))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                OrgFixtures.json("allocations", List.of(Map.of("openItemId", item, "amount", "110")))));

        assertThat(results).filteredOn(r -> r.getStatus() == 200).hasSize(1);
        assertThat(results)
                .filteredOn(r -> r.getStatus() != 200)
                .allSatisfy(r -> assertThat((String) JsonPath.read(r.getContentAsString(), "$.code"))
                        .isEqualTo("ALLOCATION_INVALID"));
        assertThat(acc.openItem(b, "receivables", invoice))
                .containsEntry("status", "SETTLED")
                .containsEntry("openAmount", "0.0000");
        assertThat(acc.balance(b, "1100")).isEqualByComparingTo("-110");
    }

    @Test
    void unallocatingAndVoidingOnePaymentSerialize() throws Exception {
        O2C o = sales.setup();
        b = acc.books(o.inv().company());
        UUID invoice = serviceInvoice(o);
        UUID item =
                UUID.fromString((String) acc.openItem(b, "receivables", invoice).get("id"));
        UUID payment = acc.postedPayment(
                b, acc.payment(b, "INBOUND", o.customer(), b.bankAccount(), "110", Map.of(item, "60")));
        String allocation = JsonPath.<List<String>>read(acc.body(b, "/receivables/" + item), "$.allocations[*].id")
                .getFirst();
        int version = acc.version(b, "/payments/" + payment);

        List<MockHttpServletResponse> results = race(
                2,
                i -> i == 0
                        ? unsafe(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(
                                        b.path("/payment-allocations/" + allocation)))
                                .cookie(b.session())
                                .header("Idempotency-Key", "unalloc-" + allocation)
                        : unsafe(post(b.path("/payments/" + payment + "/void")))
                                .cookie(b.session())
                                .header("If-Match", etag(version))
                                .header("Idempotency-Key", "void-" + payment)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(OrgFixtures.json("reason", "Bounced")));

        // Whichever runs first wins; the other sees the changed payment.
        int unallocated = results.get(0).getStatus();
        int voided = results.get(1).getStatus();
        assertThat(List.of(unallocated, voided)).containsAnyOf(204, 200);
        if (unallocated == 204) {
            assertThat(voided).isEqualTo(412);
        } else {
            assertThat(voided).isEqualTo(200);
            assertThat(unallocated).isEqualTo(409);
        }
        assertThat(acc.openItem(b, "receivables", invoice)).containsEntry("openAmount", "110.0000");
    }

    @Test
    void aPeriodClosesEitherBeforeOrAfterAConcurrentPosting() throws Exception {
        Period first = acc.periods(b, b.today()).getFirst();
        acc.posted(b, first.start(), Map.of("6000", "1", "3000", "-1"));
        int version = acc.version(b, "/periods/" + first.id());

        List<MockHttpServletResponse> results = race(THREADS, i -> {
            if (i == 0) {
                return unsafe(post(b.path("/periods/" + first.id() + "/close")))
                        .cookie(b.session())
                        .header("If-Match", etag(version))
                        .header("Idempotency-Key", "close-" + first.id());
            }
            Map<String, Object> body = acc.entry(b, first.end(), Map.of("6000", "10", "3000", "-10"));
            body.put("postImmediately", true);
            try {
                return unsafe(post(b.path("/journal-entries")))
                        .cookie(b.session())
                        .header("Idempotency-Key", "late-" + i + "-" + first.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OrgFixtures.JSON_MAPPER.writeValueAsString(body));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });

        assertThat(results.getFirst().getStatus()).isEqualTo(200);
        assertThat(results.subList(1, THREADS))
                .allSatisfy(r -> assertThat(r.getStatus()).isIn(201, 422));
        assertThat(results.subList(1, THREADS))
                .filteredOn(r -> r.getStatus() == 422)
                .allSatisfy(r -> assertThat((String) JsonPath.read(r.getContentAsString(), "$.code"))
                        .isEqualTo("PERIOD_CLOSED"));
        long posted = results.subList(1, THREADS).stream()
                .filter(r -> r.getStatus() == 201)
                .count();
        // The snapshot holds exactly what was posted into the period before it closed.
        BigDecimal ledger = new BigDecimal(acc.<String>read(
                b, "/reports/trial-balance?from=" + first.start() + "&to=" + first.end(), "$.totalDebit"));
        assertThat(ledger).isEqualByComparingTo(BigDecimal.valueOf(1 + 10 * posted));
        BigDecimal snapshot = CurrentContext.callWith(
                RequestContext.forRequest("snapshot-" + UUID.randomUUID()).withCompany(b.company()),
                () -> tx.execute(s -> dsl.fetchSingle(
                                "SELECT coalesce(sum(debit_total), 0) FROM accounting.period_balances WHERE period_id = ?",
                                first.id())
                        .get(0, BigDecimal.class)));
        assertThat(snapshot).isEqualByComparingTo(ledger);
    }

    // ------------------------------------------------------------------------------ helpers

    /** A posted invoice of 4 × 25 + 10 % tax (110) for a service. */
    private UUID serviceInvoice(O2C o) throws Exception {
        UUID service = sales.service(o, "SVC");
        UUID order = id(
                sales.create(
                        o,
                        "/sales-orders",
                        OrgFixtures.map(
                                "customerId",
                                o.customer(),
                                "warehouseId",
                                o.warehouse(),
                                "lines",
                                List.of(sales.line(service, "4", "25")))),
                201);
        expect(sales.action(o, o.session(), "/sales-orders/" + order + "/confirm", 0, "confirm-" + order, null), 200);
        return sales.postedInvoice(o, order);
    }

    private List<MockHttpServletResponse> race(int count, IntFunction<MockHttpServletRequestBuilder> request)
            throws Exception {
        CyclicBarrier start = new CyclicBarrier(count);
        List<Future<MockHttpServletResponse>> futures = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            MockHttpServletRequestBuilder builder = request.apply(i);
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
