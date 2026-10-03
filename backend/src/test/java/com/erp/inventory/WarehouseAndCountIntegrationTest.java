package com.erp.inventory;

import static com.erp.support.AuthTestSupport.unsafe;
import static com.erp.support.OrgFixtures.etag;
import static com.erp.support.OrgFixtures.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Warehouses, their locations and physical stock counts. */
class WarehouseAndCountIntegrationTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    InventoryFixtures inv;

    private Setup s;
    private Warehouse wh;

    @BeforeEach
    void setUp() throws Exception {
        s = inv.setup();
        wh = s.warehouse();
    }

    @Test
    void newWarehousesComeWithTheirStandardLocations() throws Exception {
        assertThat(wh.locations()).containsOnlyKeys("RECEIVING", "SHIPPING", "STOCK", "TRANSIT");
        mvc.perform(get(s.path("/warehouses/" + wh.id() + "/locations")).cookie(s.session()))
                .andExpect(jsonPath("$.data[*].locationType")
                        .value(containsInAnyOrder("RECEIVING", "SHIPPING", "INTERNAL", "TRANSIT")));
        create("/warehouses", json("branchId", s.branch(), "code", "WH1", "name", "Again"))
                .andExpect(status().isConflict());
        create("/warehouses", json("branchId", UUID.randomUUID(), "code", "WH9", "name", "No branch"))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void locationsFormATreeWithinOneWarehouse() throws Exception {
        UUID aisle = id(create(
                "/warehouses/" + wh.id() + "/locations",
                json("code", "A1", "name", "Aisle 1", "parentId", wh.stock(), "locationType", "INTERNAL")));
        create(
                        "/warehouses/" + wh.id() + "/locations",
                        json("code", "A1-B1", "name", "Bin", "parentId", aisle, "locationType", "INTERNAL"))
                .andExpect(status().isCreated());
        // A second TRANSIT location and a parent from another warehouse are refused.
        create(
                        "/warehouses/" + wh.id() + "/locations",
                        json("code", "T2", "name", "Transit", "locationType", "TRANSIT"))
                .andExpect(status().is4xxClientError());
        Warehouse other = inv.warehouse(s, "WH2");
        create(
                        "/warehouses/" + wh.id() + "/locations",
                        json("code", "X", "name", "Cross", "parentId", other.stock(), "locationType", "INTERNAL"))
                .andExpect(status().isUnprocessableContent());
        // The standard locations cannot be deactivated.
        mvc.perform(unsafe(post(s.path("/locations/" + wh.stock() + "/deactivate")))
                        .cookie(s.session())
                        .header("If-Match", etag(0)))
                .andExpect(status().isConflict());
    }

    @Test
    void stockedLocationsAndWarehousesStayActive() throws Exception {
        UUID bin = id(create(
                "/warehouses/" + wh.id() + "/locations",
                json("code", "BIN", "name", "Bin", "locationType", "INTERNAL")));
        inv.opening(s, s.variant(), bin, "3", "1");
        mvc.perform(unsafe(post(s.path("/locations/" + bin + "/deactivate")))
                        .cookie(s.session())
                        .header("If-Match", etag(0)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESOURCE_IN_USE"));
        mvc.perform(unsafe(post(s.path("/warehouses/" + wh.id() + "/deactivate")))
                        .cookie(s.session())
                        .header("If-Match", etag(0)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESOURCE_IN_USE"));

        // An empty warehouse can be deactivated; it then takes no new movements.
        Warehouse empty = inv.warehouse(s, "WH2");
        mvc.perform(unsafe(post(s.path("/warehouses/" + empty.id() + "/deactivate")))
                        .cookie(s.session())
                        .header("If-Match", etag(0)))
                .andExpect(status().isOk());
        Map<String, Object> line = inv.line(s.variant(), null, empty.stock(), "1");
        line.put("unitCostBase", "1");
        inv.createMovement(s, inv.movement(s, "OPENING", empty.id(), "lines", List.of(line)))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void countsPostTheDifferenceToTheCurrentQuantity() throws Exception {
        UUID bin = id(create(
                "/warehouses/" + wh.id() + "/locations",
                json("code", "BIN", "name", "Bin", "locationType", "INTERNAL")));
        UUID gadget = inv.variant(s, "GADGET", "EA");
        inv.opening(s, s.variant(), wh.stock(), "10", "2");
        inv.opening(s, gadget, bin, "4", "5");

        UUID count = id(create("/stock-counts", json("warehouseId", wh.id(), "countDate", s.today())));
        String started =
                body(unsafe(post(s.path("/stock-counts/" + count + "/start"))).header("If-Match", etag(0)));
        assertThat((String) JsonPath.read(started, "$.status")).isEqualTo("IN_PROGRESS");
        assertThat((String) JsonPath.read(started, "$.number")).startsWith("SC-");
        assertThat(JsonPath.<List<Object>>read(started, "$.lines")).hasSize(2);

        // Completing needs every line counted.
        mvc.perform(unsafe(post(s.path("/stock-counts/" + count + "/complete")))
                        .cookie(s.session())
                        .header("If-Match", etag(1)))
                .andExpect(status().isUnprocessableContent());
        String lines = OrgFixtures.JSON_MAPPER.writeValueAsString(Map.of(
                "lines",
                List.of(
                        Map.of("variantId", s.variant(), "locationId", wh.stock(), "countedQuantityBase", "8"),
                        Map.of("variantId", gadget, "locationId", bin, "countedQuantityBase", "4"))));
        mvc.perform(unsafe(put(s.path("/stock-counts/" + count + "/lines")))
                        .cookie(s.session())
                        .header("If-Match", etag(1))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(lines))
                .andExpect(status().isOk());
        mvc.perform(unsafe(post(s.path("/stock-counts/" + count + "/complete")))
                        .cookie(s.session())
                        .header("If-Match", etag(2)))
                .andExpect(status().isOk());

        // Stock moves during the count: the difference is taken against the quantity at posting.
        inv.postMovement(
                s,
                inv.movement(
                        s,
                        "SCRAP",
                        wh.id(),
                        "reasonCodeId",
                        s.scrapReason(),
                        "lines",
                        List.of(inv.line(s.variant(), wh.stock(), null, "1"))));

        // A COUNT reason is required; another kind is refused.
        postCount(count, 3, s.scrapReason()).andExpect(status().isUnprocessableContent());
        String posted = body(unsafe(post(s.path("/stock-counts/" + count + "/post")))
                .header("If-Match", etag(3))
                .header("Idempotency-Key", "count-" + count)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json("reasonCodeId", s.countReason())));
        assertThat((String) JsonPath.read(posted, "$.status")).isEqualTo("POSTED");
        String adjustment = JsonPath.read(posted, "$.adjustmentMovementId");
        assertThat(inv.balance(s, s.variant(), wh.stock())).isEqualByComparingTo("8");
        assertThat(inv.balance(s, gadget, bin)).isEqualByComparingTo("4");
        mvc.perform(get(s.path("/stock-movements/" + adjustment)).cookie(s.session()))
                .andExpect(jsonPath("$.movementType").value("COUNT_ADJUSTMENT"))
                .andExpect(jsonPath("$.lines.length()").value(1))
                .andExpect(
                        jsonPath("$.lines[0].fromLocationId").value(wh.stock().toString()))
                .andExpect(jsonPath("$.lines[0].quantityBase").value("1.000000"));
        // A posted count is final.
        mvc.perform(unsafe(post(s.path("/stock-counts/" + count + "/cancel")))
                        .cookie(s.session())
                        .header("If-Match", etag(4)))
                .andExpect(status().isConflict());
    }

    @Test
    void aCountWithoutDifferencesPostsNoMovement() throws Exception {
        inv.opening(s, s.variant(), wh.stock(), "3", "1");
        UUID count = id(create(
                "/stock-counts",
                json("warehouseId", wh.id(), "countDate", s.today(), "reasonCodeId", s.countReason())));
        patchCount(count, 0, json("countDate", s.today().minusDays(1), "notes", "Aisle 1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.countDate").value(s.today().minusDays(1).toString()));
        body(unsafe(post(s.path("/stock-counts/" + count + "/start"))).header("If-Match", etag(1)));
        // Once started, the date of the snapshot is fixed; notes stay editable.
        patchCount(count, 2, json("countDate", s.today()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].code").value("LOCKED"));
        patchCount(count, 2, json("notes", "Aisle 1 and 2")).andExpect(status().isOk());
        patchCount(count, 3, json("reasonCodeId", s.scrapReason())).andExpect(status().isUnprocessableContent());
        mvc.perform(unsafe(put(s.path("/stock-counts/" + count + "/lines")))
                        .cookie(s.session())
                        .header("If-Match", etag(3))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OrgFixtures.JSON_MAPPER.writeValueAsString(Map.of(
                                "lines",
                                List.of(Map.of(
                                        "variantId",
                                        s.variant(),
                                        "locationId",
                                        wh.stock(),
                                        "countedQuantityBase",
                                        "3"))))))
                .andExpect(status().isOk());
        body(unsafe(post(s.path("/stock-counts/" + count + "/complete"))).header("If-Match", etag(4)));
        postCount(count, 5, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("POSTED"))
                .andExpect(jsonPath("$.adjustmentMovementId").doesNotExist())
                .andExpect(jsonPath("$.notes").value("Aisle 1 and 2"));
        patchCount(count, 6, json("notes", "late")).andExpect(status().isConflict());
    }

    // ------------------------------------------------------------------------------------------

    private ResultActions postCount(UUID count, int version, UUID reason) throws Exception {
        return mvc.perform(unsafe(post(s.path("/stock-counts/" + count + "/post")))
                .cookie(s.session())
                .header("If-Match", etag(version))
                .header("Idempotency-Key", "count-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json("reasonCodeId", reason)));
    }

    private ResultActions patchCount(UUID count, int version, String body) throws Exception {
        return mvc.perform(unsafe(patch(s.path("/stock-counts/" + count)))
                .cookie(s.session())
                .header("If-Match", etag(version))
                .contentType(OrgFixtures.MERGE_PATCH)
                .content(body));
    }

    private ResultActions create(String path, String body) throws Exception {
        return mvc.perform(unsafe(post(s.path(path)))
                .cookie(s.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private String body(MockHttpServletRequestBuilder request) throws Exception {
        var response = mvc.perform(request.cookie(s.session())).andReturn().getResponse();
        if (response.getStatus() >= 300) {
            throw new AssertionError(response.getStatus() + " " + response.getContentAsString());
        }
        return response.getContentAsString();
    }

    private static UUID id(ResultActions actions) throws Exception {
        String body = actions.andReturn().getResponse().getContentAsString();
        if (actions.andReturn().getResponse().getStatus() != 201) {
            throw new AssertionError(body);
        }
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }
}
