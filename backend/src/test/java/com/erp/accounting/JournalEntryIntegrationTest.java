package com.erp.accounting;

import static com.erp.support.AuthTestSupport.unsafe;
import static com.erp.support.OrgFixtures.etag;
import static com.erp.support.ProcurementFixtures.expect;
import static com.erp.support.ProcurementFixtures.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erp.accounting.application.LedgerInvariantCheck;
import com.erp.support.AccountingFixtures;
import com.erp.support.AccountingFixtures.Books;
import com.erp.support.IntegrationTest;
import com.erp.support.OrgFixtures;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Manual journal entries (PRODUCT_SPEC.md §8.3, §8.4): drafts are edited freely; posting requires a
 * balanced entry (ACC-1) of one-sided lines (ACC-2) on postable, non-control accounts; posted entries
 * are immutable and corrected by reversal (ACC-3); amounts are exact decimals.
 */
class JournalEntryIntegrationTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    AccountingFixtures acc;

    @Autowired
    LedgerInvariantCheck invariants;

    private Books b;

    @BeforeEach
    void setUp() throws Exception {
        b = acc.setup();
    }

    @AfterEach
    void ledgerStaysConsistent() {
        assertThat(invariants.check(b.company()).clean()).isTrue();
    }

    @Test
    void aBalancedEntryIsPostedNumberedAndBooked() throws Exception {
        UUID entry = id(
                acc.create(
                        b,
                        b.session(),
                        "/journal-entries",
                        acc.entry(b, b.today(), lines("6000", "150.25", "3000", "-150.25"))),
                201);
        String draft = acc.body(b, "/journal-entries/" + entry);
        assertThat((String) JsonPath.read(draft, "$.status")).isEqualTo("DRAFT");
        assertThat(JsonPath.<String>read(draft, "$.number")).isNull();

        expect(acc.action(b, b.session(), "/journal-entries/" + entry + "/post", 0, "post-" + entry, null), 200);
        String posted = acc.body(b, "/journal-entries/" + entry);
        assertThat((String) JsonPath.read(posted, "$.status")).isEqualTo("POSTED");
        assertThat((String) JsonPath.read(posted, "$.number")).startsWith("GEN-");
        assertThat((String) JsonPath.read(posted, "$.totalDebit")).isEqualTo("150.2500");
        assertThat(acc.balance(b, "6000")).isEqualByComparingTo("150.25");
        assertThat(acc.balance(b, "3000")).isEqualByComparingTo("-150.25");
    }

    @Test
    void unbalancedEntriesAreRefused() throws Exception {
        UUID entry = id(
                acc.create(
                        b,
                        b.session(),
                        "/journal-entries",
                        acc.entry(b, b.today(), lines("6000", "100", "3000", "-99.99"))),
                201);
        String refused = expect(
                        acc.action(b, b.session(), "/journal-entries/" + entry + "/post", 0, "post-" + entry, null),
                        422)
                .getResponse()
                .getContentAsString();
        assertThat((String) JsonPath.read(refused, "$.code")).isEqualTo("UNBALANCED_ENTRY");
        assertThat(acc.read(b, "/journal-entries/" + entry, "$.status").toString())
                .isEqualTo("DRAFT");
        assertThat(acc.balance(b, "6000")).isEqualByComparingTo("0");

        // A line with both sides, or none, is refused at once; so is a one-line entry.
        Map<String, Object> twoSided = acc.entry(b, b.today(), lines("6000", "10", "3000", "-10"));
        lineOf(twoSided, 0).put("credit", "5");
        acc.create(b, b.session(), "/journal-entries", twoSided)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].code").value("ONE_SIDE"));
        Map<String, Object> single = acc.entry(b, b.today(), lines("6000", "10"));
        acc.create(b, b.session(), "/journal-entries", single).andExpect(status().isUnprocessableContent());

        // postImmediately refuses the unbalanced entry and stores nothing.
        Map<String, Object> immediate = acc.entry(b, b.today(), lines("6000", "1", "3000", "-2"));
        immediate.put("postImmediately", true);
        mvc.perform(unsafe(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                                b.path("/journal-entries")))
                        .cookie(b.session())
                        .header("Idempotency-Key", "immediate-" + b.company())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OrgFixtures.JSON_MAPPER.writeValueAsString(immediate)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("UNBALANCED_ENTRY"));
        assertThat(JsonPath.<List<?>>read(acc.body(b, "/journal-entries?filter[status]=DRAFT"), "$.data"))
                .hasSize(1);
    }

    @Test
    void controlAndInactiveAccountsTakeNoManualLines() throws Exception {
        acc.create(b, b.session(), "/journal-entries", acc.entry(b, b.today(), lines("1100", "10", "3000", "-10")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].code").value("CONTROL_ACCOUNT_MANUAL_POSTING"));
        UUID other = id(
                acc.create(
                        b,
                        b.session(),
                        "/accounts",
                        OrgFixtures.map(
                                "code",
                                "6500",
                                "name",
                                "Travel",
                                "accountType",
                                "EXPENSE",
                                "accountSubtype",
                                "OPERATING_EXPENSE")),
                201);
        UUID entry = id(
                acc.create(
                        b,
                        b.session(),
                        "/journal-entries",
                        OrgFixtures.map(
                                "entryDate",
                                b.today(),
                                "description",
                                "Travel",
                                "lines",
                                List.of(
                                        OrgFixtures.map("accountId", other, "debit", "40"),
                                        OrgFixtures.map("accountId", b.account("3000"), "credit", "40")))),
                201);
        expect(acc.action(b, b.session(), "/accounts/" + other + "/deactivate", 0, null, null), 200);
        expect(acc.action(b, b.session(), "/journal-entries/" + entry + "/post", 0, "post-" + entry, null), 422)
                .getResponse()
                .getContentAsString()
                .contains("ACCOUNT_NOT_POSTABLE");
    }

    @Test
    void draftsAreEditedButPostedEntriesAreImmutable() throws Exception {
        UUID entry = id(
                acc.create(
                        b, b.session(), "/journal-entries", acc.entry(b, b.today(), lines("6000", "5", "3000", "-5"))),
                201);
        Map<String, Object> changed = acc.entry(b, b.today(), lines("6000", "7.5", "3000", "-7.5"));
        mvc.perform(unsafe(patch(b.path("/journal-entries/" + entry)))
                        .cookie(b.session())
                        .header("If-Match", etag(0))
                        .contentType("application/merge-patch+json")
                        .content(OrgFixtures.JSON_MAPPER.writeValueAsString(
                                OrgFixtures.map("description", "Corrected", "lines", changed.get("lines")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalDebit").value("7.5000"))
                .andExpect(jsonPath("$.description").value("Corrected"));
        expect(acc.action(b, b.session(), "/journal-entries/" + entry + "/post", 1, "post-" + entry, null), 200);

        mvc.perform(unsafe(patch(b.path("/journal-entries/" + entry)))
                        .cookie(b.session())
                        .header("If-Match", etag(2))
                        .contentType("application/merge-patch+json")
                        .content(OrgFixtures.json("description", "Silent change")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE"));
        mvc.perform(unsafe(delete(b.path("/journal-entries/" + entry)))
                        .cookie(b.session())
                        .header("If-Match", etag(2)))
                .andExpect(status().isConflict());
        expect(acc.action(b, b.session(), "/journal-entries/" + entry + "/post", 2, "again-" + entry, null), 409);

        UUID deletable = id(
                acc.create(
                        b, b.session(), "/journal-entries", acc.entry(b, b.today(), lines("6000", "1", "3000", "-1"))),
                201);
        mvc.perform(unsafe(delete(b.path("/journal-entries/" + deletable)))
                        .cookie(b.session())
                        .header("If-Match", etag(0)))
                .andExpect(status().isNoContent());
    }

    @Test
    void postedEntriesAreCorrectedByReversal() throws Exception {
        UUID entry = id(
                acc.create(
                        b,
                        b.session(),
                        "/journal-entries",
                        acc.entry(b, b.today(), lines("6000", "80", "3000", "-80"))),
                201);
        expect(acc.action(b, b.session(), "/journal-entries/" + entry + "/post", 0, "post-" + entry, null), 200);

        String reversal = expect(
                        acc.action(
                                b,
                                b.session(),
                                "/journal-entries/" + entry + "/reverse",
                                1,
                                "rev-" + entry,
                                OrgFixtures.map("reversalDate", b.today(), "reason", "Booked twice")),
                        201)
                .getResponse()
                .getContentAsString();
        assertThat((String) JsonPath.read(reversal, "$.entryType")).isEqualTo("REVERSAL");
        assertThat((String) JsonPath.read(reversal, "$.reversalOfId")).isEqualTo(entry.toString());
        assertThat((String) JsonPath.read(reversal, "$.lines[0].credit")).isEqualTo("80.0000");
        assertThat(acc.<String>read(b, "/journal-entries/" + entry, "$.reversedById"))
                .isEqualTo(JsonPath.read(reversal, "$.id"));
        assertThat(acc.<String>read(b, "/journal-entries/" + entry, "$.status")).isEqualTo("POSTED");
        assertThat(acc.balance(b, "6000")).isEqualByComparingTo("0");

        // Reversed once only; a reversal is not reversed through the API.
        expect(
                acc.action(
                        b,
                        b.session(),
                        "/journal-entries/" + entry + "/reverse",
                        2,
                        "rev2-" + entry,
                        OrgFixtures.map("reversalDate", b.today(), "reason", "Again")),
                409);
        String reversalId = JsonPath.read(reversal, "$.id");
        expect(
                acc.action(
                        b,
                        b.session(),
                        "/journal-entries/" + reversalId + "/reverse",
                        1,
                        "rev3-" + entry,
                        OrgFixtures.map("reversalDate", b.today(), "reason", "Undo")),
                409);
    }

    @Test
    void largeEntriesArePostedByAnotherUser() throws Exception {
        UUID retained = b.account("3100");
        mvc.perform(unsafe(put(b.path("/settings/accounting")))
                        .cookie(b.session())
                        .header("If-Match", etag(1))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OrgFixtures.json(
                                "retainedEarningsAccountId",
                                retained,
                                "allowManualEntriesInSoftClosed",
                                true,
                                "maxRoundingDifferenceMinorUnits",
                                1,
                                "manualEntryApprovalThresholdBase",
                                "1000")))
                .andExpect(status().isOk());
        UUID small = id(
                acc.create(
                        b,
                        b.session(),
                        "/journal-entries",
                        acc.entry(b, b.today(), lines("6000", "999.99", "3000", "-999.99"))),
                201);
        expect(acc.action(b, b.session(), "/journal-entries/" + small + "/post", 0, "post-" + small, null), 200);
        UUID large = id(
                acc.create(
                        b,
                        b.session(),
                        "/journal-entries",
                        acc.entry(b, b.today(), lines("6000", "1000", "3000", "-1000"))),
                201);
        String refused = expect(
                        acc.action(b, b.session(), "/journal-entries/" + large + "/post", 0, "post-" + large, null),
                        403)
                .getResponse()
                .getContentAsString();
        assertThat((String) JsonPath.read(refused, "$.code")).isEqualTo("SOD_VIOLATION");
        expect(acc.action(b, b.approver(), "/journal-entries/" + large + "/post", 0, "appr-" + large, null), 200);
    }

    @Test
    void amountsAreExactDecimals() throws Exception {
        // 0.1 + 0.2 = 0.3 exactly: no binary floating point anywhere on the way.
        Map<String, String> tenths = new LinkedHashMap<>();
        tenths.put("6000", "0.1");
        tenths.put("6100", "0.2");
        tenths.put("3000", "-0.3");
        UUID entry = id(acc.create(b, b.session(), "/journal-entries", acc.entry(b, b.today(), tenths)), 201);
        expect(acc.action(b, b.session(), "/journal-entries/" + entry + "/post", 0, "post-" + entry, null), 200);
        assertThat(acc.balance(b, "3000")).isEqualByComparingTo(new BigDecimal("-0.3"));
        assertThat((String) acc.read(b, "/journal-entries/" + entry, "$.totalCredit"))
                .isEqualTo("0.3000");

        // More decimals than the base currency has are refused, not rounded.
        acc.create(
                        b,
                        b.session(),
                        "/journal-entries",
                        acc.entry(b, b.today(), lines("6000", "0.001", "3000", "-0.001")))
                .andExpect(status().isUnprocessableContent());
        // Amounts must be decimal strings in the format the API documents.
        Map<String, Object> numeric = acc.entry(b, b.today(), lines("6000", "1", "3000", "-1"));
        lineOf(numeric, 0).put("debit", "1e3");
        acc.create(b, b.session(), "/journal-entries", numeric).andExpect(status().is4xxClientError());

        // Large amounts keep every digit.
        UUID large = id(
                acc.create(
                        b,
                        b.session(),
                        "/journal-entries",
                        acc.entry(b, b.today(), lines("6000", "999999999999.99", "3000", "-999999999999.99"))),
                201);
        expect(acc.action(b, b.session(), "/journal-entries/" + large + "/post", 0, "post-" + large, null), 200);
        assertThat(acc.balance(b, "6000")).isEqualByComparingTo("1000000000000.09");
    }

    @Test
    void postingNeedsThePermissionAndDraftsAPeriod() throws Exception {
        var reader = acc.user(b, "accounting.journal_entry.read", "accounting.journal_entry.create");
        UUID entry = id(
                acc.create(b, reader, "/journal-entries", acc.entry(b, b.today(), lines("6000", "1", "3000", "-1"))),
                201);
        expect(acc.action(b, reader, "/journal-entries/" + entry + "/post", 0, "post-" + entry, null), 403);
        acc.create(
                        b,
                        b.session(),
                        "/journal-entries",
                        acc.entry(b, b.today().minusYears(3), lines("6000", "1", "3000", "-1")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].code").value("PERIOD_CLOSED"));
    }

    private static Map<String, String> lines(String... codesAndAmounts) {
        Map<String, String> lines = new LinkedHashMap<>();
        for (int i = 0; i + 1 < codesAndAmounts.length; i += 2) {
            lines.put(codesAndAmounts[i], codesAndAmounts[i + 1]);
        }
        return lines;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> lineOf(Map<String, Object> body, int index) {
        return ((List<Map<String, Object>>) body.get("lines")).get(index);
    }
}
