package com.erp.accounting;

import static com.erp.support.AuthTestSupport.unsafe;
import static com.erp.support.OrgFixtures.etag;
import static com.erp.support.ProcurementFixtures.expect;
import static com.erp.support.ProcurementFixtures.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erp.support.AccountingFixtures;
import com.erp.support.AccountingFixtures.Books;
import com.erp.support.AuthTestSupport;
import com.erp.support.IntegrationTest;
import com.erp.support.OrgFixtures;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Accounting setup and master data (PRODUCT_SPEC.md §8.1, §8.2, §8.6): a new company gets the
 * standard chart, mappings, journals and its current fiscal year; accounts form a tree and keep
 * what their postings rely on; mappings are scoped and type-checked; the retained earnings account
 * is an equity account of its subtype.
 */
class ChartOfAccountsIntegrationTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    AuthTestSupport auth;

    @Autowired
    AccountingFixtures acc;

    @Test
    void aCompanyCreatedThroughTheApiIsSetUpForAccounting() throws Exception {
        Cookie admin = auth.login(auth.systemAdmin());
        String code = "A" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        UUID company = UUID.fromString(JsonPath.read(
                mvc.perform(unsafe(post("/api/v1/companies"))
                                .cookie(admin)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(OrgFixtures.json(
                                        "code",
                                        code,
                                        "legalName",
                                        code + " Ltd",
                                        "displayName",
                                        code,
                                        "countryCode",
                                        "US",
                                        "baseCurrency",
                                        "USD",
                                        "timezone",
                                        "America/New_York",
                                        "fiscalYearStartMonth",
                                        4)))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.id"));
        Books b = acc.books(company);

        assertThat(b.accounts()).hasSize(31).containsKeys("1010", "1100", "2000", "3100", "4000", "5000");
        List<String> journals = JsonPath.read(acc.body(b, "/journals?limit=50"), "$.data[*].code");
        assertThat(journals).containsExactlyInAnyOrder("GEN", "SAL", "PUR", "BNK", "CSH", "INV", "PAY", "CLS", "OPN");
        List<AccountingFixtures.Period> periods = acc.periods(b, b.today());
        assertThat(periods).hasSize(12);
        assertThat(periods.getFirst().start().getMonthValue()).isEqualTo(4);
        assertThat(periods).allSatisfy(p -> assertThat(p.status()).isEqualTo("OPEN"));
        assertThat(acc.<String>read(b, "/settings/accounting", "$.retainedEarningsAccountId"))
                .isEqualTo(b.account("3100").toString());
        assertThat(acc.<String>read(b, "/account-mappings/resolve?key=AR_CONTROL", "$.accountCode"))
                .isEqualTo("1100");
        assertThat(acc.<String>read(b, "/account-mappings/resolve?key=COGS", "$.accountCode"))
                .isEqualTo("5000");
        // The books are ready: a first entry posts.
        acc.posted(b, b.today(), Map.of("1010", "500", "3000", "-500"));
        assertThat(acc.balance(b, "1010")).isEqualByComparingTo("500");

        // A further fiscal year starts in the company's start month, once.
        LocalDate nextStart = periods.getLast().end().plusDays(1);
        expect(acc.create(b, b.session(), "/fiscal-years", Map.of("startDate", nextStart.toString())), 201);
        acc.create(b, b.session(), "/fiscal-years", Map.of("startDate", nextStart.toString()))
                .andExpect(status().isConflict());
        acc.create(
                        b,
                        b.session(),
                        "/fiscal-years",
                        Map.of("startDate", nextStart.plusYears(1).withMonth(1).toString()))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void accountsFormATreeAndKeepWhatTheirPostingsRelyOn() throws Exception {
        Books b = acc.setup();
        UUID marketing = id(
                acc.create(
                        b,
                        b.session(),
                        "/accounts",
                        OrgFixtures.map(
                                "code",
                                "6200",
                                "name",
                                "Marketing",
                                "accountType",
                                "EXPENSE",
                                "accountSubtype",
                                "OPERATING_EXPENSE",
                                "isPostable",
                                false)),
                201);
        UUID ads = id(
                acc.create(
                        b,
                        b.session(),
                        "/accounts",
                        OrgFixtures.map(
                                "code",
                                "6210",
                                "name",
                                "Advertising",
                                "accountType",
                                "EXPENSE",
                                "accountSubtype",
                                "OPERATING_EXPENSE",
                                "parentId",
                                marketing)),
                201);
        b.accounts().put("6200", marketing);
        b.accounts().put("6210", ads);
        String tree = acc.body(b, "/accounts/tree");
        assertThat(JsonPath.<List<String>>read(tree, "$[?(@.account.code == '6200')].children[*].account.code"))
                .containsExactly("6210");

        // Codes are unique, children share the parent's type, group accounts take no lines.
        acc.create(
                        b,
                        b.session(),
                        "/accounts",
                        OrgFixtures.map(
                                "code",
                                "6210",
                                "name",
                                "Again",
                                "accountType",
                                "EXPENSE",
                                "accountSubtype",
                                "OPERATING_EXPENSE"))
                .andExpect(status().isConflict());
        acc.create(
                        b,
                        b.session(),
                        "/accounts",
                        OrgFixtures.map(
                                "code",
                                "4300",
                                "name",
                                "Wrong parent",
                                "accountType",
                                "REVENUE",
                                "accountSubtype",
                                "OTHER_INCOME",
                                "parentId",
                                marketing))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].code").value("TYPE_MISMATCH"));
        acc.create(b, b.session(), "/journal-entries", acc.entry(b, b.today(), Map.of("6200", "5", "3000", "-5")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].code").value("ACCOUNT_NOT_POSTABLE"));

        // Once used, an account keeps its subtype; system accounts stay active.
        acc.posted(b, b.today(), Map.of("6210", "5", "3000", "-5"));
        mvc.perform(unsafe(patch(b.path("/accounts/" + ads)))
                        .cookie(b.session())
                        .header("If-Match", etag(0))
                        .contentType(OrgFixtures.MERGE_PATCH)
                        .content(OrgFixtures.json("accountSubtype", "OTHER_EXPENSE", "name", "Ads")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].code").value("ACCOUNT_IN_USE"));
        mvc.perform(unsafe(patch(b.path("/accounts/" + ads)))
                        .cookie(b.session())
                        .header("If-Match", etag(0))
                        .contentType(OrgFixtures.MERGE_PATCH)
                        .content(OrgFixtures.json("name", "Ads")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Ads"));
        acc.action(b, b.session(), "/accounts/" + b.account("1100") + "/deactivate", 0, null, null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESOURCE_IN_USE"));
        // A user without account.manage reads the chart but does not change it.
        Cookie clerk = acc.user(b, AccountingFixtures.CLERK);
        mvc.perform(get(b.path("/accounts")).cookie(clerk)).andExpect(status().isOk());
        acc.create(
                        b,
                        clerk,
                        "/accounts",
                        OrgFixtures.map(
                                "code",
                                "6300",
                                "name",
                                "Travel",
                                "accountType",
                                "EXPENSE",
                                "accountSubtype",
                                "OPERATING_EXPENSE"))
                .andExpect(status().isForbidden());
    }

    @Test
    void mappingsAreScopedAndTypeChecked() throws Exception {
        Books b = acc.setup();
        UUID tax = id(
                acc.create(
                        b,
                        b.session(),
                        "/tax-codes",
                        OrgFixtures.map("code", "OUT20", "name", "Output 20 %", "scope", "SALES", "ratePercent", "20")),
                201);
        UUID special = acc.account(b, "2110", "LIABILITY", "TAX_PAYABLE");

        String stale = etagOf(b, "/account-mappings");
        putMappings(b, stale, mapping("TAX_OUTPUT", "TAX_CODE", tax, special)).andExpect(status().isOk());
        assertThat(acc.<String>read(
                        b,
                        "/account-mappings/resolve?key=TAX_OUTPUT&scopeType=TAX_CODE&scopeId=" + tax,
                        "$.accountCode"))
                .isEqualTo("2110");
        assertThat(acc.<String>read(b, "/account-mappings/resolve?key=TAX_OUTPUT", "$.accountCode"))
                .isEqualTo("2100");

        // Wrong account types, unsupported scopes and removing a default are refused.
        putMappings(b, etagOf(b, "/account-mappings"), mapping("COGS", "DEFAULT", null, b.account("4000")))
                .andExpect(status().isUnprocessableContent());
        putMappings(b, etagOf(b, "/account-mappings"), mapping("GRNI", "TAX_CODE", tax, b.account("2050")))
                .andExpect(status().isUnprocessableContent());
        putMappings(b, etagOf(b, "/account-mappings"), mapping("AR_CONTROL", "DEFAULT", null, null))
                .andExpect(status().isUnprocessableContent());
        putMappings(b, stale, mapping("TAX_OUTPUT", "DEFAULT", null, b.account("2100")))
                .andExpect(status().isPreconditionFailed());

        // A scoped mapping is removed with accountId null; resolution falls back to the default.
        putMappings(b, etagOf(b, "/account-mappings"), mapping("TAX_OUTPUT", "TAX_CODE", tax, null))
                .andExpect(status().isOk());
        assertThat(acc.<String>read(
                        b,
                        "/account-mappings/resolve?key=TAX_OUTPUT&scopeType=TAX_CODE&scopeId=" + tax,
                        "$.accountCode"))
                .isEqualTo("2100");

        // The retained earnings account must be one.
        mvc.perform(unsafe(put(b.path("/settings/accounting")))
                        .cookie(b.session())
                        .header("If-Match", etagOf(b, "/settings/accounting"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OrgFixtures.json(
                                "retainedEarningsAccountId",
                                b.account("6000"),
                                "allowManualEntriesInSoftClosed",
                                true,
                                "maxRoundingDifferenceMinorUnits",
                                1)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].pointer").value("/retainedEarningsAccountId"));
    }

    private org.springframework.test.web.servlet.ResultActions putMappings(
            Books b, String etag, Map<String, Object> mapping) throws Exception {
        return mvc.perform(unsafe(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                        b.path("/account-mappings")))
                .cookie(b.session())
                .header("If-Match", etag)
                .contentType(MediaType.APPLICATION_JSON)
                .content(OrgFixtures.json("mappings", List.of(mapping))));
    }

    private String etagOf(Books b, String path) throws Exception {
        return mvc.perform(get(b.path(path)).cookie(b.session()))
                .andReturn()
                .getResponse()
                .getHeader("ETag");
    }

    private static Map<String, Object> mapping(String key, String scope, UUID scopeId, UUID account) {
        return OrgFixtures.map("mappingKey", key, "scopeType", scope, "scopeId", scopeId, "accountId", account);
    }
}
