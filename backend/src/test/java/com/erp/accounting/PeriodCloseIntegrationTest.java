package com.erp.accounting;

import static com.erp.support.AuthTestSupport.unsafe;
import static com.erp.support.OrgFixtures.etag;
import static com.erp.support.ProcurementFixtures.expect;
import static com.erp.support.ProcurementFixtures.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erp.accounting.application.LedgerInvariantCheck;
import com.erp.platform.context.CurrentContext;
import com.erp.platform.context.RequestContext;
import com.erp.support.AccountingFixtures;
import com.erp.support.AccountingFixtures.Books;
import com.erp.support.AccountingFixtures.Period;
import com.erp.support.IntegrationTest;
import com.erp.support.InventoryFixtures;
import com.erp.support.OrgFixtures;
import com.jayway.jsonpath.JsonPath;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Period and year-end closing (PRODUCT_SPEC.md §8.7, ACC-4): closed periods take no postings, a
 * period closes only after the earlier ones, without drafts and with a level trial balance, and
 * reopens latest first with a reason; soft-closed periods take postings only from privileged users;
 * an operational document whose posting is refused is rolled back with it; the year-end close moves
 * the result into retained earnings.
 */
class PeriodCloseIntegrationTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    AccountingFixtures acc;

    @Autowired
    InventoryFixtures inv;

    @Autowired
    LedgerInvariantCheck invariants;

    @Autowired
    DSLContext dsl;

    @Autowired
    TransactionTemplate tx;

    private Books b;
    private List<Period> periods;

    @BeforeEach
    void setUp() throws Exception {
        b = acc.setup();
        periods = acc.periods(b, b.today());
    }

    @AfterEach
    void ledgerStaysConsistent() {
        assertThat(invariants.check(b.company()).clean()).isTrue();
    }

    @Test
    void aClosedPeriodTakesNoPostingsAndIsCorrectedInAnOpenOne() throws Exception {
        Period first = periods.getFirst();
        UUID entry = acc.posted(b, first.start(), Map.of("6000", "40", "3000", "-40"));
        expect(acc.period(b, b.session(), first, "close", null), 200);
        assertThat(statusOf(first)).isEqualTo("CLOSED");
        assertThat(snapshotRows(first)).isPositive();

        // A draft dated in the closed period is kept but not posted.
        UUID late = id(
                acc.create(
                        b,
                        b.session(),
                        "/journal-entries",
                        acc.entry(b, first.end(), Map.of("6000", "1", "3000", "-1"))),
                201);
        acc.action(b, b.session(), "/journal-entries/" + late + "/post", 0, "post-" + late, null)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("PERIOD_CLOSED"))
                .andExpect(jsonPath("$.errors[0].meta.status").value("CLOSED"));
        assertThat(acc.read(b, "/journal-entries/" + late, "$.status").toString())
                .isEqualTo("DRAFT");

        // The posted entry is corrected by a reversal dated in an open period, not in the closed one.
        acc.action(
                        b,
                        b.session(),
                        "/journal-entries/" + entry + "/reverse",
                        1,
                        "rev-closed-" + entry,
                        Map.of("reversalDate", first.end().toString(), "reason", "Wrong account"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("PERIOD_CLOSED"));
        expect(
                acc.action(
                        b,
                        b.session(),
                        "/journal-entries/" + entry + "/reverse",
                        1,
                        "rev-open-" + entry,
                        Map.of("reversalDate", b.today().toString(), "reason", "Wrong account")),
                201);
        assertThat(acc.balance(b, "6000")).isEqualByComparingTo("0");
        // The closed period's figures stay as they were.
        assertThat(acc.read(b, "/reports/trial-balance?from=" + first.start() + "&to=" + first.end(), "$.closingDebit")
                        .toString())
                .isEqualTo("40.0000");
    }

    @Test
    void periodsCloseInOrderWithoutDraftsAndReopenLatestFirst() throws Exception {
        Period first = periods.get(0);
        Period second = periods.get(1);
        acc.period(b, b.session(), second, "close", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE"));

        UUID draft = id(
                acc.create(
                        b,
                        b.session(),
                        "/journal-entries",
                        acc.entry(b, first.start(), Map.of("6000", "5", "3000", "-5"))),
                201);
        acc.period(b, b.session(), first, "close", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("draft")));
        mvc.perform(unsafe(delete(b.path("/journal-entries/" + draft)))
                        .cookie(b.session())
                        .header("If-Match", etag(0)))
                .andExpect(status().isNoContent());

        acc.posted(b, first.start(), Map.of("6000", "5", "3000", "-5"));
        expect(acc.period(b, b.session(), first, "close", null), 200);
        expect(acc.period(b, b.session(), second, "close", null), 200);

        // Reopening needs a reason and goes latest first; the snapshot is dropped.
        acc.period(b, b.session(), first, "reopen", Map.of("reason", "Late invoice"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE"));
        acc.period(b, b.session(), second, "reopen", Map.of("reason", " "))
                .andExpect(status().isUnprocessableContent());
        expect(acc.period(b, b.session(), second, "reopen", Map.of("reason", "Late invoice")), 200);
        expect(acc.period(b, b.session(), first, "reopen", Map.of("reason", "Late invoice")), 200);
        assertThat(statusOf(first)).isEqualTo("OPEN");
        assertThat(snapshotRows(first)).isZero();
        acc.posted(b, first.end(), Map.of("6000", "2", "3000", "-2"));
        assertThat(acc.balance(b, "6000")).isEqualByComparingTo("7");

        // A clerk may neither close nor reopen.
        var clerk = acc.user(b, AccountingFixtures.CLERK);
        acc.period(b, clerk, first, "close", null).andExpect(status().isForbidden());
    }

    @Test
    void softClosedPeriodsTakePostingsOnlyFromPrivilegedUsers() throws Exception {
        Period current = current();
        expect(acc.period(b, b.session(), current, "soft-close", null), 200);
        var clerk = acc.user(b, AccountingFixtures.CLERK);
        UUID entry = id(
                acc.create(b, clerk, "/journal-entries", acc.entry(b, b.today(), Map.of("6000", "3", "3000", "-3"))),
                201);
        acc.action(b, clerk, "/journal-entries/" + entry + "/post", 0, "post-clerk-" + entry, null)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("PERIOD_CLOSED"))
                .andExpect(jsonPath("$.errors[0].meta.status").value("SOFT_CLOSED"));
        expect(acc.action(b, b.session(), "/journal-entries/" + entry + "/post", 0, "post-acc-" + entry, null), 200);
        assertThat(acc.balance(b, "6000")).isEqualByComparingTo("3");

        // The company may refuse manual entries in soft-closed periods even to privileged users.
        mvc.perform(unsafe(put(b.path("/settings/accounting")))
                        .cookie(b.session())
                        .header("If-Match", etag(1))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OrgFixtures.json(
                                "retainedEarningsAccountId",
                                b.account("3100"),
                                "allowManualEntriesInSoftClosed",
                                false,
                                "maxRoundingDifferenceMinorUnits",
                                1)))
                .andExpect(status().isOk());
        UUID refused = id(
                acc.create(
                        b, b.session(), "/journal-entries", acc.entry(b, b.today(), Map.of("6000", "1", "3000", "-1"))),
                201);
        acc.action(b, b.session(), "/journal-entries/" + refused + "/post", 0, "post-refused-" + refused, null)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("PERIOD_CLOSED"));
    }

    /** Transaction rollback: the stock movement is not posted when its GL posting is refused. */
    @Test
    void anOperationalDocumentIsRolledBackWithItsRefusedPosting() throws Exception {
        InventoryFixtures.Setup s = inv.setup();
        Books books = acc.books(s.company());
        Period current = acc.periods(books, books.today()).stream()
                .filter(p -> !p.start().isAfter(books.today()) && !p.end().isBefore(books.today()))
                .findFirst()
                .orElseThrow();
        expect(acc.period(books, books.session(), current, "soft-close", null), 200);

        Map<String, Object> line = inv.line(s.variant(), null, s.warehouse().stock(), "10");
        line.put("unitCostBase", "12.50");
        UUID movement = UUID.fromString(JsonPath.read(
                expect(
                                inv.createMovement(
                                        s,
                                        inv.movement(s, "OPENING", s.warehouse().id(), "lines", List.of(line))),
                                201)
                        .getResponse()
                        .getContentAsString(),
                "$.id"));
        inv.post(s, movement, 0)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("PERIOD_CLOSED"));
        assertThat(inv.balance(s, s.variant(), s.warehouse().stock())).isEqualByComparingTo("0");
        assertThat(acc.lines(books, "inventory", "STOCK_MOVEMENT", movement)).isEmpty();
        String draft = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                                s.path("/stock-movements/" + movement))
                        .cookie(s.session()))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat((String) JsonPath.read(draft, "$.status")).isEqualTo("DRAFT");
        assertThat((Integer) JsonPath.read(draft, "$.version")).isZero();

        expect(acc.period(books, books.session(), current, "reopen", Map.of("reason", "Opening stock")), 200);
        expect(inv.post(s, movement, 0), 200);
        assertThat(inv.balance(s, s.variant(), s.warehouse().stock())).isEqualByComparingTo("10");
        assertThat(acc.lines(books, "inventory", "STOCK_MOVEMENT", movement))
                .containsExactlyInAnyOrderEntriesOf(
                        Map.of("1200", new java.math.BigDecimal("125"), "3900", new java.math.BigDecimal("-125")));
        assertThat(invariants.check(s.company()).clean()).isTrue();
    }

    @Test
    void theYearEndCloseMovesTheResultIntoRetainedEarnings() throws Exception {
        acc.posted(b, b.today(), Map.of("1010", "70", "4000", "-100", "6000", "30"));
        Map<String, Object> year = acc.year(b, b.today());
        UUID yearId = UUID.fromString((String) year.get("id"));
        acc.action(b, b.session(), "/fiscal-years/" + yearId + "/close", 0, "year-early-" + yearId, null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE"));

        for (Period period : periods) {
            expect(acc.period(b, b.session(), period, "close", null), 200);
        }
        String closed = expect(
                        acc.action(b, b.session(), "/fiscal-years/" + yearId + "/close", 0, "year-" + yearId, null),
                        200)
                .getResponse()
                .getContentAsString();
        assertThat((String) JsonPath.read(closed, "$.status")).isEqualTo("CLOSED");
        String closingEntry = JsonPath.read(closed, "$.closingEntryId");
        assertThat(closingEntry).isNotNull();
        String entry = acc.body(b, "/journal-entries/" + closingEntry);
        assertThat((String) JsonPath.read(entry, "$.entryType")).isEqualTo("CLOSING");
        assertThat((String) JsonPath.read(entry, "$.entryDate"))
                .isEqualTo(periods.getLast().end().toString());

        Map<String, java.math.BigDecimal> balances = acc.balances(b);
        assertThat(balances.getOrDefault("4000", java.math.BigDecimal.ZERO)).isEqualByComparingTo("0");
        assertThat(balances.getOrDefault("6000", java.math.BigDecimal.ZERO)).isEqualByComparingTo("0");
        assertThat(balances.get("3100")).isEqualByComparingTo("-70");
        assertThat(balances.get("1010")).isEqualByComparingTo("70");
        // The year's P&L still shows the result; the closing entry is not an expense or revenue.
        String pnl = acc.body(
                b,
                "/reports/profit-and-loss?from=" + periods.getFirst().start() + "&to="
                        + periods.getLast().end());
        assertThat((String) JsonPath.read(pnl, "$.netProfit")).isEqualTo("70.0000");

        // The next year is open, the closed year stays closed.
        LocalDate next = periods.getLast().end().plusDays(1);
        assertThat(acc.year(b, next).get("status")).isEqualTo("OPEN");
        acc.period(b, b.session(), periods.getLast(), "reopen", Map.of("reason", "Audit"))
                .andExpect(status().isConflict());
        acc.action(b, b.session(), "/fiscal-years/" + yearId + "/close", 1, "year-again-" + yearId, null)
                .andExpect(status().isConflict());
        acc.create(b, b.session(), "/journal-entries", acc.entry(b, next, Map.of("6000", "1", "3000", "-1")))
                .andExpect(status().isCreated());
    }

    // ------------------------------------------------------------------------------ helpers

    private Period current() {
        return periods.stream()
                .filter(p -> !p.start().isAfter(b.today()) && !p.end().isBefore(b.today()))
                .findFirst()
                .orElseThrow();
    }

    private String statusOf(Period period) throws Exception {
        return acc.read(b, "/periods/" + period.id(), "$.status");
    }

    private int snapshotRows(Period period) {
        return CurrentContext.callWith(
                RequestContext.forRequest("snapshot-" + UUID.randomUUID()).withCompany(b.company()),
                () -> tx.execute(s -> dsl.fetchCount(
                        dsl.selectFrom("accounting.period_balances").where("period_id = ?", period.id()))));
    }
}
