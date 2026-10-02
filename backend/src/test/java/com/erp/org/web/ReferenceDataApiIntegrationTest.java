package com.erp.org.web;

import static com.erp.db.org.Tables.CURRENCIES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erp.support.IntegrationTest;
import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

/** Pagination, filtering, sorting and search conventions (API.md §8) on real reference data. */
class ReferenceDataApiIntegrationTest extends IntegrationTest {

    private static final String CURRENCIES_URL = "/api/v1/reference/currencies";

    @Autowired
    MockMvc mvc;

    @Autowired
    DSLContext dsl;

    @Autowired
    TransactionTemplate tx;

    @Test
    void requiresAuthentication() throws Exception {
        mvc.perform(get(CURRENCIES_URL)).andExpect(status().isUnauthorized());
    }

    @Test
    void firstPageUsesDefaultsAndEnvelope() throws Exception {
        mvc.perform(authenticated(get(CURRENCIES_URL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(25))
                .andExpect(jsonPath("$.data[0].code").value("AED"))
                .andExpect(jsonPath("$.data[0].minorUnits").isNumber())
                .andExpect(jsonPath("$.data[0].isActive").value(true))
                .andExpect(jsonPath("$.page.limit").value(25))
                .andExpect(jsonPath("$.page.hasMore").value(true))
                .andExpect(jsonPath("$.page.nextCursor").isString())
                .andExpect(jsonPath("$.meta.totalCount").doesNotExist());
    }

    @Test
    void cursorsWalkTheWholeListWithoutGapsOrDuplicates() throws Exception {
        List<String> expected = tx.execute(status -> dsl.select(CURRENCIES.CODE)
                .from(CURRENCIES)
                .orderBy(CURRENCIES.CODE)
                .fetch(CURRENCIES.CODE));

        List<String> walked = walk(Map.of("limit", "40"), "code");

        assertThat(walked).containsExactlyElementsOf(expected);
    }

    @Test
    void mixedDirectionSortIsStableAcrossPages() throws Exception {
        record Row(String code, int minorUnits) {}
        List<Row> rows = tx.execute(status -> dsl.select(CURRENCIES.CODE, CURRENCIES.MINOR_UNITS)
                .from(CURRENCIES)
                .fetch(r -> new Row(r.value1(), r.value2())));
        List<String> expected = rows.stream()
                .sorted(Comparator.comparing(Row::minorUnits).reversed().thenComparing(Row::code))
                .map(Row::code)
                .toList();

        List<String> walked = walk(Map.of("limit", "17", "sort", "-minorUnits,code"), "code");

        assertThat(walked).containsExactlyElementsOf(expected);
    }

    @Test
    void filtersAndCountsTotal() throws Exception {
        mvc.perform(authenticated(get(CURRENCIES_URL)
                        .param("filter[code][in]", "USD,JPY,EUR")
                        .param("includeTotal", "true")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].code").value(org.hamcrest.Matchers.contains("EUR", "JPY", "USD")))
                .andExpect(jsonPath("$.data[1].minorUnits").value(0))
                .andExpect(jsonPath("$.meta.totalCount").value(3))
                .andExpect(jsonPath("$.page.hasMore").value(false))
                .andExpect(jsonPath("$.page.nextCursor").doesNotExist());

        mvc.perform(authenticated(get(CURRENCIES_URL).param("filter[minorUnits][gte]", "3")))
                .andExpect(jsonPath("$.data[*].code").value(hasItems("BHD", "KWD")))
                .andExpect(jsonPath("$.data[*].minorUnits")
                        .value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.greaterThanOrEqualTo(3))));
    }

    @Test
    void searchesCaseInsensitivelyAndTreatsWildcardsLiterally() throws Exception {
        mvc.perform(authenticated(get(CURRENCIES_URL).param("q", "DOLLAR").param("limit", "200")))
                .andExpect(jsonPath("$.data[*].code").value(hasItems("USD", "CAD", "AUD")));
        mvc.perform(authenticated(get(CURRENCIES_URL).param("q", "%%")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(authenticated(get("/api/v1/reference/countries").param("q", "united")))
                .andExpect(jsonPath("$.data[*].code").value(hasItems("US", "GB", "AE")));
    }

    @Test
    void rejectsInvalidListParameters() throws Exception {
        for (Map.Entry<String, String> bad : List.of(
                Map.entry("limit", "0"),
                Map.entry("limit", "abc"),
                Map.entry("sort", "secret"),
                Map.entry("filter[password]", "x"),
                Map.entry("filter[minorUnits][like]", "2"),
                Map.entry("filter[isActive]", "yes"),
                Map.entry("q", "x"),
                Map.entry("cursor", "forged.cursor"),
                Map.entry("unexpected", "1"))) {
            mvc.perform(authenticated(get(CURRENCIES_URL).param(bad.getKey(), bad.getValue())))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                    .andExpect(jsonPath("$.errors[*].parameter").value(hasItem(bad.getKey())));
        }
    }

    @Test
    void cursorCannotBeReplayedWithDifferentFilters() throws Exception {
        String cursor = JsonPath.read(
                mvc.perform(authenticated(get(CURRENCIES_URL).param("limit", "5")))
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.page.nextCursor");

        mvc.perform(authenticated(get(CURRENCIES_URL).param("cursor", cursor).param("filter[minorUnits]", "2")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("INVALID_CURSOR"));
    }

    private List<String> walk(Map<String, String> params, String field) throws Exception {
        List<String> values = new ArrayList<>();
        String cursor = null;
        for (int guard = 0; guard < 100; guard++) {
            MockHttpServletRequestBuilder request = get(CURRENCIES_URL);
            params.forEach(request::param);
            if (cursor != null) {
                request.param("cursor", cursor);
            }
            String body = mvc.perform(authenticated(request))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            values.addAll(JsonPath.read(body, "$.data[*]." + field));
            cursor = JsonPath.read(body, "$.page.nextCursor");
            if (cursor == null) {
                return values;
            }
        }
        throw new AssertionError("pagination did not terminate");
    }

    private static MockHttpServletRequestBuilder authenticated(MockHttpServletRequestBuilder request) {
        return request.with(user("tester"));
    }
}
