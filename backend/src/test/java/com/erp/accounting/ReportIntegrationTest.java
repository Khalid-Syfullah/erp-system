package com.erp.accounting;

import static com.erp.support.AuthTestSupport.unsafe;
import static com.erp.support.ProcurementFixtures.expect;
import static com.erp.support.ProcurementFixtures.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erp.accounting.application.LedgerInvariantCheck;
import com.erp.support.AccountingFixtures;
import com.erp.support.AccountingFixtures.Books;
import com.erp.support.IntegrationTest;
import com.erp.support.OrgFixtures;
import com.erp.support.SalesFixtures;
import com.erp.support.SalesFixtures.O2C;
import com.jayway.jsonpath.JsonPath;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Financial reports as JSON (PRODUCT_SPEC.md §8.10): computed from posted lines only, the trial
 * balance level, the balance sheet balanced with the current result, ageing by due date, partner
 * statements, the tax summary and the cash book with reconciliation marks.
 */
class ReportIntegrationTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    AccountingFixtures acc;

    @Autowired
    SalesFixtures sales;

    @Autowired
    LedgerInvariantCheck invariants;

    private O2C o;
    private Books b;
    private LocalDate today;
    private UUID invoice;

    /** Capital 1 000, an expense of 300, an invoice of 110 (10 tax) and a receipt of 50 against it. */
    @BeforeEach
    void setUp() throws Exception {
        o = sales.setup();
        b = acc.books(o.inv().company());
        today = b.today();
        acc.posted(b, today, Map.of("1010", "1000", "3000", "-1000"));
        acc.posted(b, today, Map.of("6000", "300", "1010", "-300"));
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
        invoice = sales.postedInvoice(o, order);
        UUID item =
                UUID.fromString((String) acc.openItem(b, "receivables", invoice).get("id"));
        acc.postedPayment(b, acc.payment(b, "INBOUND", o.customer(), b.bankAccount(), "50", Map.of(item, "50")));
        // A draft never shows.
        id(
                acc.create(
                        b, b.session(), "/journal-entries", acc.entry(b, today, Map.of("6000", "999", "3000", "-999"))),
                201);
    }

    @Test
    void theTrialBalanceIsLevelAndTheStatementsAgree() throws Exception {
        String tb = acc.body(b, "/reports/trial-balance?from=" + today.withDayOfMonth(1) + "&to=" + today);
        assertThat((String) JsonPath.read(tb, "$.totalDebit")).isEqualTo(JsonPath.read(tb, "$.totalCredit"));
        assertThat((String) JsonPath.read(tb, "$.closingDebit")).isEqualTo(JsonPath.read(tb, "$.closingCredit"));
        assertThat(AccountingFixtures.nonZero(acc.balances(b)))
                .isEqualTo(AccountingFixtures.amounts(
                        "1010", "750", "3000", "-1000", "6000", "300", "1100", "60", "4000", "-100", "2100", "-10"));

        String bs = acc.body(b, "/reports/balance-sheet?asOf=" + today);
        assertThat((Boolean) JsonPath.read(bs, "$.balanced")).isTrue();
        assertThat((String) JsonPath.read(bs, "$.totalAssets")).isEqualTo("810.0000");
        assertThat((String) JsonPath.read(bs, "$.totalLiabilities")).isEqualTo("10.0000");
        assertThat((String) JsonPath.read(bs, "$.currentEarnings")).isEqualTo("-200.0000");

        String pnl = acc.body(b, "/reports/profit-and-loss?from=" + today.withDayOfMonth(1) + "&to=" + today);
        assertThat((String) JsonPath.read(pnl, "$.totalRevenue")).isEqualTo("100.0000");
        assertThat((String) JsonPath.read(pnl, "$.totalExpenses")).isEqualTo("300.0000");
        assertThat((String) JsonPath.read(pnl, "$.netProfit")).isEqualTo("-200.0000");

        String gl = acc.body(
                b, "/reports/general-ledger?accountId=" + b.account("1010") + "&from=" + today + "&to=" + today);
        assertThat(JsonPath.<List<String>>read(gl, "$.rows[*].balance"))
                .containsExactly("1000.0000", "700.0000", "750.0000");
        assertThat((String) JsonPath.read(gl, "$.closing")).isEqualTo("750.0000");
        // Nothing before the books began.
        String before =
                acc.body(b, "/reports/trial-balance?from=" + today.minusYears(1) + "&to=" + today.minusYears(1));
        assertThat(JsonPath.<List<?>>read(before, "$.rows")).isEmpty();
    }

    @Test
    void receivablesAgeByDueDateAndTheStatementFollowsTheCustomer() throws Exception {
        String now = acc.body(b, "/reports/ar-ageing?asOf=" + today);
        assertThat((String) JsonPath.read(now, "$.total.current")).isEqualTo("60.0000");
        assertThat((String) JsonPath.read(now, "$.total.total")).isEqualTo("60.0000");
        String later = acc.body(b, "/reports/ar-ageing?asOf=" + today.plusDays(45));
        assertThat((String) JsonPath.read(later, "$.total.days1To30")).isEqualTo("60.0000");
        assertThat((String) JsonPath.read(later, "$.partners[0].partnerId"))
                .isEqualTo(o.customer().toString());
        String muchLater = acc.body(b, "/reports/ar-ageing?asOf=" + today.plusDays(200));
        assertThat((String) JsonPath.read(muchLater, "$.total.over90")).isEqualTo("60.0000");
        String ap = acc.body(b, "/reports/ap-ageing?asOf=" + today);
        assertThat((String) JsonPath.read(ap, "$.total.total")).isEqualTo("0.0000");

        String statement = acc.body(
                b,
                "/reports/partner-statement?partnerId=" + o.customer() + "&from=" + today.withDayOfMonth(1) + "&to="
                        + today);
        assertThat((String) JsonPath.read(statement, "$.opening")).isEqualTo("0.0000");
        assertThat(JsonPath.<List<?>>read(statement, "$.rows")).hasSize(2);
        assertThat((String) JsonPath.read(statement, "$.closing")).isEqualTo("60.0000");

        String tax = acc.body(b, "/reports/tax-summary?from=" + today.withDayOfMonth(1) + "&to=" + today);
        assertThat((String) JsonPath.read(tax, "$.outputTax")).isEqualTo("10.0000");
        assertThat((String) JsonPath.read(tax, "$.rows[0].taxableSales")).isEqualTo("100.0000");
        assertThat((String) JsonPath.read(tax, "$.netTax")).isEqualTo("10.0000");

        // A clerk without ar.read sees no AR ageing (both permissions are required).
        var reporter = acc.user(b, "accounting.report.read");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                                b.path("/reports/ar-ageing"))
                        .param("asOf", today.toString())
                        .cookie(reporter))
                .andExpect(status().isForbidden());
    }

    @Test
    void theCashBookShowsBankTransactionsAndReconciliationMarks() throws Exception {
        String path = "/reports/cash-book?bankAccountId=" + b.bankAccount() + "&from=" + today + "&to=" + today;
        String book = acc.body(b, path);
        assertThat((String) JsonPath.read(book, "$.opening")).isEqualTo("0.0000");
        assertThat((String) JsonPath.read(book, "$.closing")).isEqualTo("750.0000");
        List<String> lines = JsonPath.read(book, "$.transactions[*].journalLineId");
        assertThat(lines).hasSize(3);
        assertThat(JsonPath.<List<String>>read(
                        acc.body(
                                b,
                                "/bank-accounts/" + b.bankAccount() + "/transactions?from=" + today + "&to=" + today),
                        "$.transactions[*].journalLineId"))
                .containsExactlyInAnyOrderElementsOf(lines);

        mvc.perform(unsafe(post(b.path("/bank-reconciliation-marks")))
                        .cookie(b.session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OrgFixtures.json(
                                "journalLineIds",
                                lines.subList(0, 2),
                                "statementReference",
                                "STMT-1",
                                "statementDate",
                                today)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines").value(2));
        assertThat(JsonPath.<List<String>>read(acc.body(b, path), "$.transactions[*].statementReference"))
                .containsExactly("STMT-1", "STMT-1", null);
        // Lines of another account are not bank transactions.
        String revenueLine = JsonPath.read(
                acc.body(
                        b,
                        "/reports/general-ledger?accountId=" + b.account("3000") + "&from=" + today + "&to=" + today),
                "$.rows[0].journalLineId");
        mvc.perform(unsafe(post(b.path("/bank-reconciliation-marks")))
                        .cookie(b.session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OrgFixtures.json(
                                "journalLineIds",
                                List.of(revenueLine),
                                "statementReference",
                                "STMT-1",
                                "statementDate",
                                today)))
                .andExpect(status().isUnprocessableContent());
        assertThat(invariants.check(b.company()).clean()).isTrue();
    }
}
