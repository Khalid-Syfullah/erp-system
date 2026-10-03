package com.erp.org.web;

import static com.erp.support.AuthTestSupport.unsafe;
import static com.erp.support.OrgFixtures.etag;
import static com.erp.support.OrgFixtures.json;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erp.support.IntegrationTest;
import com.erp.support.OrgFixtures;
import com.erp.support.OrgFixtures.Admin;
import com.erp.support.TestTaxCodeUsage;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Exchange rates, tax codes, payment terms and company settings (API.md §17.3). */
class ReferenceDataAdministrationIntegrationTest extends IntegrationTest {

    private static final String MERGE_PATCH = "application/merge-patch+json";

    @Autowired
    MockMvc mvc;

    @Autowired
    OrgFixtures fixtures;

    @Autowired
    TestTaxCodeUsage taxCodeUsage;

    // ---------------------------------------------------------------------------- exchange rates

    @Test
    void exchangeRatesAreDecimalStringsLookedUpByDate() throws Exception {
        Admin admin = fixtures.admin(); // base currency of test companies: USD
        UUID march = create(
                admin,
                "/exchange-rates",
                json("currencyCode", "EUR", "rateDate", "2026-03-01", "rate", "0.9123456789"));
        create(admin, "/exchange-rates", json("currencyCode", "EUR", "rateDate", "2026-06-01", "rate", "0.95"));

        lookup(admin, "EUR", "2026-05-31")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rate").value("0.9123456789"))
                .andExpect(jsonPath("$.rateDate").value("2026-03-01"));
        lookup(admin, "EUR", "2026-06-01").andExpect(jsonPath("$.rate").value("0.9500000000"));
        lookup(admin, "EUR", "2026-02-28")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("EXCHANGE_RATE_MISSING"));
        lookup(admin, "USD", "2020-01-01").andExpect(jsonPath("$.rate").value("1"));
        lookup(admin, "eur", "2026-05-31").andExpect(status().isBadRequest());

        mvc.perform(get(admin.path("/exchange-rates"))
                        .cookie(admin.session())
                        .param("filter[rateDate][gte]", "2026-04-01")
                        .param("filter[currencyCode]", "EUR"))
                .andExpect(jsonPath("$.data[*].rateDate").value(contains("2026-06-01")));
        mvc.perform(get(admin.path("/exchange-rates")).cookie(admin.session()))
                .andExpect(jsonPath("$.data[*].rateDate").value(contains("2026-06-01", "2026-03-01")));

        mvc.perform(unsafe(patch(admin.path("/exchange-rates/" + march)))
                        .cookie(admin.session())
                        .header("If-Match", etag(0))
                        .contentType(MERGE_PATCH)
                        .content(json("rate", 0.91)))
                .andExpect(status().isUnprocessableContent());
        mvc.perform(unsafe(patch(admin.path("/exchange-rates/" + march)))
                        .cookie(admin.session())
                        .header("If-Match", etag(0))
                        .contentType(MERGE_PATCH)
                        .content(json("rate", "0.91")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rate").value("0.9100000000"));
        mvc.perform(unsafe(delete(admin.path("/exchange-rates/" + march)))
                        .cookie(admin.session())
                        .header("If-Match", etag(1)))
                .andExpect(status().isNoContent());
        lookup(admin, "EUR", "2026-05-31").andExpect(status().isUnprocessableContent());
    }

    @Test
    void exchangeRatesAreValidatedAndUnique() throws Exception {
        Admin admin = fixtures.admin();
        String rate = json("currencyCode", "GBP", "rateDate", "2026-01-01", "rate", "1.15");
        create(admin, "/exchange-rates", rate);

        postJson(admin, "/exchange-rates", rate)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_EXCHANGE_RATE"));
        postJson(admin, "/exchange-rates", json("currencyCode", "USD", "rateDate", "2026-01-01", "rate", "1"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].code").value("BASE_CURRENCY"));
        postJson(admin, "/exchange-rates", json("currencyCode", "XYZ", "rateDate", "2026-01-01", "rate", "1"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].code").value("UNKNOWN_CURRENCY"));
        postJson(admin, "/exchange-rates", json("currencyCode", "CHF", "rateDate", "2026-01-01", "rate", "0"))
                .andExpect(status().isUnprocessableContent());
        postJson(
                        admin,
                        "/exchange-rates",
                        json("currencyCode", "CHF", "rateDate", "2026-01-01", "rate", "1.00000000001"))
                .andExpect(status().isUnprocessableContent());
        // Numbers instead of decimal strings are rejected (API.md §11).
        postJson(admin, "/exchange-rates", "{\"currencyCode\":\"CHF\",\"rateDate\":\"2026-01-01\",\"rate\":1.05}")
                .andExpect(status().isBadRequest());
    }

    // --------------------------------------------------------------------------------- tax codes

    @Test
    void taxCodesValidateRatesAndFreezeOnceUsed() throws Exception {
        Admin admin = fixtures.admin();
        UUID vat = create(
                admin, "/tax-codes", json("code", "VAT19", "name", "VAT 19 %", "scope", "BOTH", "ratePercent", "19"));

        postJson(admin, "/tax-codes", json("code", "VAT19", "name", "Again", "scope", "BOTH", "ratePercent", "19"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_CODE"));
        postJson(
                        admin,
                        "/tax-codes",
                        json("code", "EX", "name", "Exempt", "scope", "SALES", "ratePercent", "5", "isExempt", true))
                .andExpect(status().isUnprocessableContent());
        postJson(
                        admin,
                        "/tax-codes",
                        json(
                                "code",
                                "OLD",
                                "name",
                                "Old",
                                "scope",
                                "SALES",
                                "ratePercent",
                                "7",
                                "validFrom",
                                "2026-02-01",
                                "validTo",
                                "2026-01-01"))
                .andExpect(status().isUnprocessableContent());
        postJson(admin, "/tax-codes", json("code", "BIG", "name", "Too high", "scope", "SALES", "ratePercent", "101"))
                .andExpect(status().isUnprocessableContent());
        postJson(admin, "/tax-codes", json("code", "SCOPE", "name", "Bad scope", "scope", "ALL", "ratePercent", "1"))
                .andExpect(status().isUnprocessableContent());

        patchTaxCode(admin, vat, 0, json("ratePercent", "19.5", "validFrom", "2026-01-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ratePercent").value("19.5000"));
        taxCodeUsage.markUsed(vat);
        patchTaxCode(admin, vat, 1, json("ratePercent", "20"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESOURCE_IN_USE"));
        // Ending the validity and renaming stay possible.
        patchTaxCode(admin, vat, 1, json("validTo", "2026-12-31", "name", "VAT 19.5 % (until 2026)"))
                .andExpect(status().isOk());
        action(admin, "/tax-codes/" + vat + "/deactivate", 2)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isActive").value(false));
        mvc.perform(get(admin.path("/tax-codes")).cookie(admin.session()).param("filter[isActive]", "false"))
                .andExpect(jsonPath("$.data[*].code").value(contains("VAT19")));
        action(admin, "/tax-codes/" + vat + "/activate", 3).andExpect(status().isOk());
    }

    // ----------------------------------------------------------------------------- payment terms

    @Test
    void paymentTermsComputeDueDates() throws Exception {
        Admin admin = fixtures.admin();
        UUID net30 = create(admin, "/payment-terms", json("code", "NET30", "name", "Net 30", "dueDays", 30));
        UUID eom = create(
                admin,
                "/payment-terms",
                json("code", "EOM15", "name", "15 days end of month", "dueDays", 15, "dueBasis", "END_OF_MONTH"));

        dueDate(admin, net30, "2026-01-15").andExpect(jsonPath("$.dueDate").value("2026-02-14"));
        dueDate(admin, eom, "2026-02-10").andExpect(jsonPath("$.dueDate").value("2026-03-15"));
        postJson(admin, "/payment-terms", json("code", "NEG", "name", "Negative", "dueDays", -1))
                .andExpect(status().isUnprocessableContent());
        postJson(admin, "/payment-terms", json("code", "NET30", "name", "Duplicate", "dueDays", 30))
                .andExpect(status().isConflict());

        mvc.perform(unsafe(patch(admin.path("/payment-terms/" + net30)))
                        .cookie(admin.session())
                        .header("If-Match", etag(0))
                        .contentType(MERGE_PATCH)
                        .content(json("dueDays", 45, "dueBasis", "END_OF_MONTH")))
                .andExpect(status().isOk());
        dueDate(admin, net30, "2026-01-15").andExpect(jsonPath("$.dueDate").value("2026-03-17"));
        action(admin, "/payment-terms/" + net30 + "/deactivate", 1).andExpect(status().isOk());
        mvc.perform(get(admin.path("/payment-terms"))
                        .cookie(admin.session())
                        .param("filter[isActive]", "true")
                        .param("sort", "-dueDays"))
                .andExpect(jsonPath("$.data[*].code").value(contains("EOM15")));
    }

    // -------------------------------------------------------------------------- company settings

    @Test
    void companySettingsArePatchable() throws Exception {
        Admin admin = fixtures.admin();

        mvc.perform(unsafe(patch(admin.path("")))
                        .cookie(admin.session())
                        .header("If-Match", etag(0))
                        .contentType(MERGE_PATCH)
                        .content(json("roundingMode", "HALF_EVEN", "taxRounding", "PER_DOCUMENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roundingMode").value("HALF_EVEN"))
                .andExpect(jsonPath("$.taxRounding").value("PER_DOCUMENT"));
        mvc.perform(unsafe(patch(admin.path("")))
                        .cookie(admin.session())
                        .header("If-Match", etag(1))
                        .contentType(MERGE_PATCH)
                        .content(json("roundingMode", "CEILING")))
                .andExpect(status().isUnprocessableContent());
        mvc.perform(unsafe(patch(admin.path("")))
                        .cookie(admin.session())
                        .header("If-Match", etag(1))
                        .contentType(MERGE_PATCH)
                        .content(json("baseCurrency", "EUR")))
                .andExpect(status().isBadRequest());
    }

    // -------------------------------------------------------------------------------- helpers

    private UUID create(Admin admin, String path, String body) throws Exception {
        return fixtures.create(admin, path, body);
    }

    private ResultActions postJson(Admin admin, String path, String body) throws Exception {
        return mvc.perform(unsafe(post(admin.path(path)))
                .cookie(admin.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions lookup(Admin admin, String currency, String date) throws Exception {
        return mvc.perform(get(admin.path("/exchange-rates/lookup"))
                .cookie(admin.session())
                .param("currencyCode", currency)
                .param("date", date));
    }

    private ResultActions patchTaxCode(Admin admin, UUID id, int version, String body) throws Exception {
        return mvc.perform(unsafe(patch(admin.path("/tax-codes/" + id)))
                .cookie(admin.session())
                .header("If-Match", etag(version))
                .contentType(MERGE_PATCH)
                .content(body));
    }

    private ResultActions action(Admin admin, String path, int version) throws Exception {
        return mvc.perform(
                unsafe(post(admin.path(path))).cookie(admin.session()).header("If-Match", etag(version)));
    }

    private ResultActions dueDate(Admin admin, UUID terms, String documentDate) throws Exception {
        return mvc.perform(get(admin.path("/payment-terms/" + terms + "/due-date"))
                .cookie(admin.session())
                .param("documentDate", documentDate));
    }
}
