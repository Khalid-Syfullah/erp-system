package com.erp.accounting;

import static com.erp.support.ProcurementFixtures.expect;
import static com.erp.support.ProcurementFixtures.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erp.accounting.application.LedgerInvariantCheck;
import com.erp.support.AccountingFixtures;
import com.erp.support.AccountingFixtures.Books;
import com.erp.support.IntegrationTest;
import com.erp.support.OrgFixtures;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Expense vouchers (PRODUCT_SPEC.md §8.9): paid straight from a bank account, booked on expense
 * accounts with recoverable input tax, numbered when posted and corrected by reversal.
 */
class ExpenseIntegrationTest extends IntegrationTest {

    @Autowired
    AccountingFixtures acc;

    @Autowired
    LedgerInvariantCheck invariants;

    private Books b;
    private UUID vat;

    @BeforeEach
    void setUp() throws Exception {
        b = acc.setup();
        vat = id(
                acc.create(
                        b,
                        b.session(),
                        "/tax-codes",
                        OrgFixtures.map("code", "VAT10", "name", "VAT 10 %", "scope", "PURCHASE", "ratePercent", "10")),
                201);
    }

    @AfterEach
    void ledgerStaysConsistent() {
        assertThat(invariants.check(b.company()).clean()).isTrue();
    }

    @Test
    void anExpenseIsPaidFromTheBankWithRecoverableTaxAndReversed() throws Exception {
        UUID expense = id(
                acc.create(
                        b,
                        b.session(),
                        "/expenses",
                        expense(
                                false,
                                OrgFixtures.map("accountId", b.account("6000"), "amount", "100", "taxCodeId", vat),
                                OrgFixtures.map("accountId", b.account("6900"), "amount", "20.50"))),
                201);
        String draft = acc.body(b, "/expenses/" + expense);
        assertThat((String) JsonPath.read(draft, "$.subtotal")).isEqualTo("120.5000");
        assertThat((String) JsonPath.read(draft, "$.taxTotal")).isEqualTo("10.0000");
        assertThat((String) JsonPath.read(draft, "$.total")).isEqualTo("130.5000");
        assertThat(acc.balance(b, "1010")).isEqualByComparingTo("0");

        expect(acc.action(b, b.session(), "/expenses/" + expense + "/post", 0, "post-" + expense, null), 200);
        String posted = acc.body(b, "/expenses/" + expense);
        assertThat((String) JsonPath.read(posted, "$.status")).isEqualTo("POSTED");
        assertThat((String) JsonPath.read(posted, "$.number")).startsWith("EXP-");
        assertThat(acc.lines(b, "accounting", "EXPENSE", expense))
                .isEqualTo(AccountingFixtures.amounts("6000", "100", "6900", "20.5", "1300", "10", "1010", "-130.5"));
        // Posted: no more edits, no second post.
        acc.action(b, b.session(), "/expenses/" + expense + "/post", 1, "post-again-" + expense, null)
                .andExpect(status().isConflict());

        int version = acc.version(b, "/expenses/" + expense);
        expect(
                acc.action(
                        b,
                        b.session(),
                        "/expenses/" + expense + "/reverse",
                        version,
                        "rev-" + expense,
                        Map.of("reversalDate", b.today().toString(), "reason", "Duplicate receipt")),
                200);
        assertThat(acc.<String>read(b, "/expenses/" + expense, "$.status")).isEqualTo("REVERSED");
        assertThat(acc.balance(b, "6000")).isEqualByComparingTo("0");
        assertThat(acc.balance(b, "1010")).isEqualByComparingTo("0");
        assertThat(acc.balance(b, "1300")).isEqualByComparingTo("0");
    }

    @Test
    void taxIncludedAmountsAreSplitExactly() throws Exception {
        UUID expense = id(
                acc.create(
                        b,
                        b.session(),
                        "/expenses",
                        expense(
                                true,
                                OrgFixtures.map("accountId", b.account("6000"), "amount", "10", "taxCodeId", vat))),
                201);
        String draft = acc.body(b, "/expenses/" + expense);
        assertThat((String) JsonPath.read(draft, "$.subtotal")).isEqualTo("9.0900");
        assertThat((String) JsonPath.read(draft, "$.taxTotal")).isEqualTo("0.9100");
        assertThat((String) JsonPath.read(draft, "$.total")).isEqualTo("10.0000");
        expect(acc.action(b, b.session(), "/expenses/" + expense + "/post", 0, "post-" + expense, null), 200);
        assertThat(acc.balance(b, "6000")).isEqualByComparingTo("9.09");
        assertThat(acc.balance(b, "1300")).isEqualByComparingTo("0.91");
        assertThat(acc.balance(b, "1010")).isEqualByComparingTo("-10");
    }

    @Test
    void onlyExpenseAccountsAndPurchaseTaxCodesAreUsed() throws Exception {
        UUID salesTax = id(
                acc.create(
                        b,
                        b.session(),
                        "/tax-codes",
                        OrgFixtures.map("code", "OUT10", "name", "Output 10 %", "scope", "SALES", "ratePercent", "10")),
                201);
        acc.create(
                        b,
                        b.session(),
                        "/expenses",
                        expense(
                                false,
                                OrgFixtures.map("accountId", b.account("1100"), "amount", "5"),
                                OrgFixtures.map("accountId", b.account("6000"), "amount", "5", "taxCodeId", salesTax),
                                OrgFixtures.map("accountId", b.account("6000"), "amount", "5.001")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[*].pointer")
                        .value(org.hamcrest.Matchers.containsInAnyOrder(
                                "/lines/0/accountId", "/lines/1/taxCodeId", "/lines/2/amount")));
        assertThat(JsonPath.<List<?>>read(acc.body(b, "/expenses"), "$.data")).isEmpty();
    }

    private Map<String, Object> expense(boolean taxIncluded, Object... lines) {
        return OrgFixtures.map(
                "payeeName",
                "Office Supplies Ltd",
                "bankAccountId",
                b.bankAccount(),
                "pricesIncludeTax",
                taxIncluded,
                "lines",
                List.of(lines));
    }
}
