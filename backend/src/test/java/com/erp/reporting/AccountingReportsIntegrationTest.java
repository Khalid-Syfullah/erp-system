package com.erp.reporting;

import static com.erp.support.ReportingFixtures.decimal;
import static com.erp.support.ReportingFixtures.rows;
import static com.erp.support.ReportingFixtures.totals;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erp.support.AccountingFixtures;
import com.erp.support.AccountingFixtures.Books;
import com.erp.support.IntegrationTest;
import com.erp.support.ReportingFixtures;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The accounting reports of the catalogue: expenses and the cash position slice the posted ledger and
 * agree with Accounting's own statements (income statement, trial balance, cash book), which the
 * catalogue lists at Accounting's paths for callers holding their permissions.
 */
class AccountingReportsIntegrationTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    AccountingFixtures acc;

    @Autowired
    ReportingFixtures rep;

    private Books b;
    private LocalDate today;
    private String period;
    private Cookie accountant;

    @BeforeEach
    void setUp() throws Exception {
        b = acc.setup();
        today = b.today();
        period = "from=" + today + "&to=" + today;
        acc.posted(b, today, Map.of("1010", "500", "4900", "-500"));
        acc.posted(b, today, Map.of("6000", "100", "1010", "-100"));
        acc.posted(b, today, Map.of("6100", "40", "1010", "-40"));
        acc.posted(b, today, Map.of("1010", "15", "6000", "-15"));
        accountant = rep.user(b.company(), "accounting.report.read");
    }

    @Test
    void expensesEqualTheIncomeStatementsExpenses() throws Exception {
        String expenses = rep.run(accountant, b.company(), "expenses", period);
        assertThat(rows(expenses))
                .extracting(r -> r.get("groupCode") + "=" + decimal(r.get("netBase")))
                .containsExactly("6000=85", "6100=40");
        Map<String, Object> first = rows(expenses).getFirst();
        assertThat(decimal(first.get("debitBase"))).isEqualByComparingTo("100");
        assertThat(decimal(first.get("creditBase"))).isEqualByComparingTo("15");
        String pl = acc.body(b, "/reports/profit-and-loss?" + period);
        assertThat(decimal(totals(expenses).get("netBase")))
                .isEqualByComparingTo("125")
                .isEqualByComparingTo(decimal(JsonPath.read(pl, "$.totalExpenses")));
        for (String groupBy : List.of("MONTH", "BRANCH", "DEPARTMENT")) {
            assertThat(decimal(totals(rep.run(accountant, b.company(), "expenses", period + "&groupBy=" + groupBy))
                            .get("netBase")))
                    .as(groupBy)
                    .isEqualByComparingTo("125");
        }
        assertThat(rows(rep.run(accountant, b.company(), "expenses", period + "&accountId=" + b.account("6100"))))
                .singleElement()
                .satisfies(r -> assertThat(r).containsEntry("groupName", "Salaries and wages"));
        assertThat(rows(rep.run(
                        accountant, b.company(), "expenses", "from=" + today.plusDays(1) + "&to=" + today.plusDays(1))))
                .isEmpty();
    }

    @Test
    void theCashPositionEqualsTheTrialBalanceAndTheCashBook() throws Exception {
        String position = rep.run(accountant, b.company(), "cash-position", "");
        Map<String, Object> bank = rows(position).getFirst();
        assertThat(bank).containsEntry("accountCode", "1010").containsEntry("currencyCode", "USD");
        assertThat(decimal(bank.get("balanceBase")))
                .isEqualByComparingTo("375")
                .isEqualByComparingTo(acc.balance(b, "1010"));
        String book = acc.body(b, "/reports/cash-book?bankAccountId=" + b.bankAccount() + "&" + period);
        assertThat(decimal(bank.get("balanceCurrency")))
                .isEqualByComparingTo(decimal(JsonPath.read(book, "$.closing")));
        assertThat(decimal(rows(rep.run(accountant, b.company(), "cash-position", "asOf=" + today.minusDays(1)))
                        .getFirst()
                        .get("balanceBase")))
                .isZero();
    }

    @Test
    void theCatalogueListsAccountingsStatementsByPermission() throws Exception {
        List<String> codes = JsonPath.read(
                mvc.perform(get(ReportingFixtures.path(b.company(), "/reports")).cookie(accountant))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$[*].code");
        assertThat(codes)
                .containsExactlyInAnyOrder(
                        "trial-balance",
                        "general-ledger",
                        "profit-and-loss",
                        "balance-sheet",
                        "cash-book",
                        "cash-position",
                        "expenses");
        Cookie receivables = rep.user(b.company(), "accounting.report.read", "accounting.ar.read");
        String catalogue = mvc.perform(
                        get(ReportingFixtures.path(b.company(), "/reports")).cookie(receivables))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(JsonPath.<List<String>>read(catalogue, "$[*].code"))
                .contains("ar-ageing")
                .doesNotContain("ap-ageing");
        assertThat(JsonPath.<List<String>>read(catalogue, "$[?(@.code=='trial-balance')].path"))
                .containsExactly("/api/v1/companies/" + b.company() + "/reports/trial-balance");
        // The listed path is Accounting's statement.
        mvc.perform(get(ReportingFixtures.path(b.company(), "/reports/trial-balance?" + period))
                        .cookie(accountant))
                .andExpect(status().isOk());
        Cookie sales = rep.user(b.company(), "reporting.sales.read");
        rep.get(sales, b.company(), "expenses", period).andExpect(status().isForbidden());
    }
}
