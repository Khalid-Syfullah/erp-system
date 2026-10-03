package com.erp.inventory;

import static com.erp.support.AuthTestSupport.unsafe;
import static com.erp.support.OrgFixtures.etag;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erp.support.AuthTestSupport;
import com.erp.support.IntegrationTest;
import com.erp.support.InventoryFixtures;
import com.erp.support.InventoryFixtures.Setup;
import com.erp.support.InventoryFixtures.Warehouse;
import com.erp.support.OrgFixtures;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Idempotency-Key handling on posting endpoints (API.md §10) and company number formats (G-6). */
class IdempotencyAndNumberingIntegrationTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    InventoryFixtures inv;

    @Autowired
    AuthTestSupport auth;

    private Setup s;
    private Warehouse wh;

    @BeforeEach
    void setUp() throws Exception {
        s = inv.setup();
        wh = s.warehouse();
    }

    @Test
    void aRepeatedRequestReplaysTheFirstResponse() throws Exception {
        UUID draft = draft(opening("2"));
        MockHttpServletResponse first =
                postWith(draft, "post-key-0001").andReturn().getResponse();
        assertThat(first.getStatus()).isEqualTo(200);

        MockHttpServletResponse again = postWith(draft, "post-key-0001")
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andReturn()
                .getResponse();
        assertThat(again.getContentAsString()).isEqualTo(first.getContentAsString());
        assertThat(again.getHeader("ETag")).isEqualTo(first.getHeader("ETag"));
        assertThat(inv.balance(s, s.variant(), wh.stock())).isEqualByComparingTo("2");

        // The same key for a different request is refused.
        UUID other = draft(opening("1"));
        postWith(other, "post-key-0001")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        // Posting requires a well-formed key.
        mvc.perform(unsafe(post(s.path("/stock-movements/" + other + "/post")))
                        .cookie(s.session())
                        .header("If-Match", etag(0)))
                .andExpect(status().isBadRequest());
        postWith(other, "short").andExpect(status().isBadRequest());
        postWith(other, "has spaces in it").andExpect(status().isBadRequest());
        assertThat(inv.balance(s, s.variant(), wh.stock())).isEqualByComparingTo("2");
    }

    @Test
    void clientErrorsAreReplayedTooButANewKeyTriesAgain() throws Exception {
        Warehouse other = inv.warehouse(s, "WH2");
        UUID transfer = draft(inv.movement(
                s,
                "TRANSFER",
                wh.id(),
                "destWarehouseId",
                other.id(),
                "lines",
                List.of(inv.line(s.variant(), wh.stock(), other.stock(), "1"))));
        postWith(transfer, "transfer-key-1")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"));

        inv.opening(s, s.variant(), wh.stock(), "1", "1");
        // The stored outcome is returned for the same key, even though the stock is there now.
        postWith(transfer, "transfer-key-1")
                .andExpect(status().isUnprocessableContent())
                .andExpect(header().string("Idempotent-Replayed", "true"));
        postWith(transfer, "transfer-key-2").andExpect(status().isOk());
        assertThat(inv.balance(s, s.variant(), other.stock())).isEqualByComparingTo("1");
    }

    @Test
    void companiesConfigureTheirNumberFormats() throws Exception {
        auth.assign(s.user(), auth.customRole("org.company.manage"), s.company());
        auth.invalidatePermissionCache();
        String settings = mvc.perform(get(s.path("/settings/numbering")).cookie(s.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.formats[?(@.documentType == 'STOCK_MOVEMENT')].example")
                        .value("SM-2026-000001"))
                .andReturn()
                .getResponse()
                .getHeader("ETag");

        putFormats(settings, Map.of("STOCK_MOVEMENT", Map.of("prefix", "sm-", "padding", 4)))
                .andExpect(status().isUnprocessableContent());
        putFormats(settings, Map.of("STOCK_MOVEMENT", Map.of("prefix", "MV{FY}/", "padding", 4)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.formats[?(@.documentType == 'STOCK_MOVEMENT')].isDefault")
                        .value(false));
        putFormats(settings, Map.of()).andExpect(status().isPreconditionFailed());

        UUID movement = inv.opening(s, s.variant(), wh.stock(), "1", "1");
        mvc.perform(get(s.path("/stock-movements/" + movement)).cookie(s.session()))
                .andExpect(jsonPath("$.number").value("MV" + s.today().getYear() + "/0001"));
    }

    // ------------------------------------------------------------------------------------------

    private Map<String, Object> opening(String qty) {
        Map<String, Object> line = inv.line(s.variant(), null, wh.stock(), qty);
        line.put("unitCostBase", "1");
        return inv.movement(s, "OPENING", wh.id(), "lines", List.of(line));
    }

    private UUID draft(Map<String, Object> body) throws Exception {
        MockHttpServletResponse response =
                inv.createMovement(s, body).andReturn().getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(201);
        return UUID.fromString(JsonPath.read(response.getContentAsString(), "$.id"));
    }

    private ResultActions postWith(UUID movement, String key) throws Exception {
        return mvc.perform(unsafe(post(s.path("/stock-movements/" + movement + "/post")))
                .cookie(s.session())
                .header("If-Match", etag(0))
                .header("Idempotency-Key", key));
    }

    private ResultActions putFormats(String ifMatch, Map<String, Object> formats) throws Exception {
        return mvc.perform(unsafe(put(s.path("/settings/numbering")))
                .cookie(s.session())
                .header("If-Match", ifMatch)
                .contentType(MediaType.APPLICATION_JSON)
                .content(OrgFixtures.JSON_MAPPER.writeValueAsString(Map.of("formats", formats))));
    }
}
