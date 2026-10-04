package com.erp.accounting;

import static com.erp.support.ProcurementFixtures.expect;
import static com.erp.support.ProcurementFixtures.id;
import static org.assertj.core.api.Assertions.assertThat;

import com.erp.accounting.application.LedgerInvariantCheck;
import com.erp.support.AccountingFixtures;
import com.erp.support.AccountingFixtures.Books;
import com.erp.support.IntegrationTest;
import com.erp.support.OrgFixtures;
import com.erp.support.SalesFixtures;
import com.erp.support.SalesFixtures.O2C;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Phase 8 exit criterion (DEVELOPMENT_PLAN.md): random operational sequences — stock receipts,
 * deliveries, invoices, credit notes, receipts with partial allocations, voids, manual entries and
 * reversals, in a seeded random order — always leave a level trial balance, AR = Σ open receivables,
 * AP = Σ open payables and the inventory account = the inventory valuation, after every step.
 */
class OperationalSequencePropertyTest extends IntegrationTest {

    private static final int STEPS = 14;

    @Autowired
    AccountingFixtures acc;

    @Autowired
    SalesFixtures sales;

    @Autowired
    LedgerInvariantCheck invariants;

    @ParameterizedTest(name = "seed {0}")
    @ValueSource(longs = {11, 23, 47})
    void everyStepKeepsTheLedgerConsistent(long seed) throws Exception {
        Random random = new Random(seed);
        O2C o = sales.setup();
        Books b = acc.books(o.inv().company());
        UUID service = sales.service(o, "SVC");
        sales.stock(o, "50", "8.25");
        List<UUID> invoices = new ArrayList<>();
        List<UUID> payments = new ArrayList<>();
        List<UUID> entries = new ArrayList<>();
        int onHand = 50;
        List<String> trace = new ArrayList<>();

        for (int step = 0; step < STEPS; step++) {
            int action = random.nextInt(7);
            switch (action) {
                case 0 -> { // manual entry
                    String amount = money(random, 1, 500);
                    entries.add(acc.posted(b, b.today(), Map.of("6000", amount, "3000", "-" + amount)));
                    trace.add("entry " + amount);
                }
                case 1 -> { // stock sold: order, delivery, invoice
                    int quantity = 1 + random.nextInt(3);
                    if (quantity > onHand) {
                        continue;
                    }
                    UUID order = sales.confirmedOrder(o, sales.line(o.variant(), String.valueOf(quantity), null));
                    sales.postedDelivery(o, order, null);
                    invoices.add(sales.postedInvoice(o, order));
                    onHand -= quantity;
                    trace.add("sold " + quantity);
                }
                case 2 -> { // service invoice
                    invoices.add(invoice(o, service, String.valueOf(1 + random.nextInt(5)), money(random, 5, 200)));
                    trace.add("service invoice");
                }
                case 3 -> { // receipt, partly allocated to an open invoice
                    UUID invoice = openInvoice(b, invoices, random);
                    String amount = money(random, 1, 300);
                    Map<UUID, String> allocation = Map.of();
                    if (invoice != null) {
                        Map<String, Object> item = acc.openItem(b, "receivables", invoice);
                        BigDecimal open = new BigDecimal((String) item.get("openAmount"));
                        BigDecimal part = open.min(new BigDecimal(amount))
                                .multiply(BigDecimal.valueOf(1 + random.nextInt(100)))
                                .divide(BigDecimal.valueOf(100), 2, java.math.RoundingMode.DOWN)
                                .max(new BigDecimal("0.01"));
                        allocation = Map.of(UUID.fromString((String) item.get("id")), part.toPlainString());
                    }
                    payments.add(acc.postedPayment(
                            b, acc.payment(b, "INBOUND", o.customer(), b.bankAccount(), amount, allocation)));
                    trace.add("receipt " + amount + " " + allocation.values());
                }
                case 4 -> { // void a receipt
                    if (payments.isEmpty()) {
                        continue;
                    }
                    UUID payment = payments.remove(random.nextInt(payments.size()));
                    expect(
                            acc.action(
                                    b,
                                    b.session(),
                                    "/payments/" + payment + "/void",
                                    acc.version(b, "/payments/" + payment),
                                    "void-" + payment,
                                    Map.of("reason", "Bounced")),
                            200);
                    trace.add("void");
                }
                case 5 -> { // reverse a manual entry
                    if (entries.isEmpty()) {
                        continue;
                    }
                    UUID entry = entries.remove(random.nextInt(entries.size()));
                    expect(
                            acc.action(
                                    b,
                                    b.session(),
                                    "/journal-entries/" + entry + "/reverse",
                                    acc.version(b, "/journal-entries/" + entry),
                                    "rev-" + entry,
                                    Map.of("reversalDate", b.today().toString(), "reason", "Undo")),
                            201);
                    trace.add("reversal");
                }
                default -> { // more stock at another cost
                    int quantity = 1 + random.nextInt(10);
                    sales.stock(o, String.valueOf(quantity), money(random, 5, 15));
                    onHand += quantity;
                    trace.add("stock " + quantity);
                }
            }
            LedgerInvariantCheck.Report report = invariants.check(b.company());
            assertThat(report.clean())
                    .as("seed %d after %s: %s", seed, trace, report)
                    .isTrue();
            String tb = acc.body(b, "/reports/trial-balance?from=" + b.today().minusYears(1) + "&to=" + b.today());
            assertThat((String) JsonPath.read(tb, "$.closingDebit"))
                    .as("seed %d after %s", seed, trace)
                    .isEqualTo(JsonPath.read(tb, "$.closingCredit"));
        }
    }

    private UUID invoice(O2C o, UUID variant, String quantity, String price) throws Exception {
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
                                List.of(sales.line(variant, quantity, price)))),
                201);
        expect(sales.action(o, o.session(), "/sales-orders/" + order + "/confirm", 0, "confirm-" + order, null), 200);
        return sales.postedInvoice(o, order);
    }

    private UUID openInvoice(Books b, List<UUID> invoices, Random random) throws Exception {
        List<UUID> open = new ArrayList<>();
        for (UUID invoice : invoices) {
            if (!"SETTLED".equals(acc.openItem(b, "receivables", invoice).get("status"))) {
                open.add(invoice);
            }
        }
        return open.isEmpty() ? null : open.get(random.nextInt(open.size()));
    }

    /** A random amount with two decimals between {@code min} and {@code max}. */
    private static String money(Random random, int min, int max) {
        long cents = min * 100L + random.nextLong((max - min) * 100L + 1);
        return BigDecimal.valueOf(cents, 2).toPlainString();
    }
}
