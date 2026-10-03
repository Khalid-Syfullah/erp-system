package com.erp.inventory;

import static com.erp.support.AuthTestSupport.unsafe;
import static com.erp.support.OrgFixtures.etag;
import static com.erp.support.OrgFixtures.json;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erp.support.AuthTestSupport;
import com.erp.support.AuthTestSupport.TestUser;
import com.erp.support.IntegrationTest;
import com.erp.support.InventoryFixtures;
import com.erp.support.InventoryFixtures.Setup;
import com.erp.support.InventoryFixtures.Warehouse;
import com.erp.support.OrgFixtures;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Role permissions, adjustment approval, branch scope and company isolation (SECURITY.md §4). */
class InventoryAccessIntegrationTest extends IntegrationTest {

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
        inv.opening(s, s.variant(), wh.stock(), "100", "1");
    }

    @Test
    void warehouseClerksMoveStockButNeitherAdjustNorReverseNorSeeValues() throws Exception {
        Cookie clerk = member("WAREHOUSE_CLERK");
        Warehouse other = inv.warehouse(s, "WH2");

        String transfer = OrgFixtures.JSON_MAPPER.writeValueAsString(inv.movement(
                s,
                "TRANSFER",
                wh.id(),
                "destWarehouseId",
                other.id(),
                "lines",
                List.of(inv.line(s.variant(), wh.stock(), other.stock(), "1"))));
        UUID moved = id(as(clerk, unsafe(post(s.path("/stock-movements"))), transfer));
        postAs(clerk, moved).andExpect(status().isOk());
        mvc.perform(get(s.path("/stock-levels")).cookie(clerk)).andExpect(status().isOk());

        String adjustment = OrgFixtures.JSON_MAPPER.writeValueAsString(adjustmentBody("1"));
        as(clerk, unsafe(post(s.path("/stock-movements"))), adjustment).andExpect(status().isForbidden());
        // Nor may they post an adjustment someone else drafted.
        UUID draft = id(inv.createMovement(s, adjustmentBody("1")));
        postAs(clerk, draft).andExpect(status().isForbidden());
        mvc.perform(unsafe(post(s.path("/stock-movements/" + draft + "/cancel")))
                        .cookie(clerk)
                        .header("If-Match", etag(0)))
                .andExpect(status().isForbidden());

        mvc.perform(unsafe(post(s.path("/stock-movements/" + moved + "/reverse")))
                        .cookie(clerk)
                        .header("If-Match", etag(1))
                        .header("Idempotency-Key", "reverse-" + moved))
                .andExpect(status().isForbidden());
        mvc.perform(get(s.path("/stock-valuation")).cookie(clerk)).andExpect(status().isForbidden());
        as(
                        clerk,
                        unsafe(post(s.path("/products"))),
                        json(
                                "code",
                                "X",
                                "name",
                                "X",
                                "categoryId",
                                s.category(),
                                "productType",
                                "STOCKABLE",
                                "baseUomId",
                                inv.uom("EA")))
                .andExpect(status().isForbidden());
    }

    @Test
    void largeAdjustmentsNeedApproval() throws Exception {
        mvc.perform(unsafe(put(s.path("/settings/inventory")))
                        .cookie(s.session())
                        .header("If-Match", etag(0))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("overReceiptTolerancePercent", "0", "adjustmentApprovalThreshold", "10")))
                .andExpect(status().isOk());
        Cookie manager = member(auth.customRole(
                "inventory.movement.read",
                "inventory.movement.create",
                "inventory.movement.post",
                "inventory.adjustment.manage"));

        UUID small = id(as(
                manager,
                unsafe(post(s.path("/stock-movements"))),
                OrgFixtures.JSON_MAPPER.writeValueAsString(adjustmentBody("10"))));
        postAs(manager, small).andExpect(status().isOk());
        UUID large = id(as(
                manager,
                unsafe(post(s.path("/stock-movements"))),
                OrgFixtures.JSON_MAPPER.writeValueAsString(adjustmentBody("11"))));
        postAs(manager, large)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ADJUSTMENT_APPROVAL_REQUIRED"));
        // Someone holding inventory.adjustment.approve posts it.
        inv.post(s, large, 0).andExpect(status().isOk());
    }

    @Test
    void branchRestrictedUsersSeeOnlyTheirWarehouses() throws Exception {
        UUID north = auth.branch(s.company(), "NORTH");
        Warehouse northWarehouse = inv.warehouse(s.session(), s.path(""), north, "NWH");
        inv.opening(s, s.variant(), northWarehouse.stock(), "5", "1");
        TestUser user = auth.user();
        auth.assign(user, auth.customRole(InventoryFixtures.ALL_INVENTORY), s.company(), north);
        Cookie scoped = auth.login(user);

        mvc.perform(get(s.path("/warehouses")).cookie(scoped))
                .andExpect(jsonPath("$.data[*].code").value(contains("NWH")));
        mvc.perform(get(s.path("/warehouses/" + wh.id())).cookie(scoped)).andExpect(status().isNotFound());
        mvc.perform(get(s.path("/locations/" + wh.stock())).cookie(scoped)).andExpect(status().isNotFound());
        mvc.perform(get(s.path("/stock-levels")).cookie(scoped))
                .andExpect(jsonPath("$.data[*].warehouseId")
                        .value(contains(northWarehouse.id().toString())));
        mvc.perform(get(s.path("/inventory-transactions")).cookie(scoped))
                .andExpect(jsonPath("$.data[*].warehouseId")
                        .value(contains(northWarehouse.id().toString())));
        mvc.perform(get(s.path("/stock-movements")).cookie(scoped))
                .andExpect(jsonPath("$.data.length()").value(1));
        // Movements in other branches' warehouses are invisible and cannot be created.
        String mainMovement = mvc.perform(get(s.path("/stock-movements"))
                        .cookie(s.session())
                        .param("filter[warehouseId]", wh.id().toString()))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String mainId =
                JsonPath.<List<String>>read(mainMovement, "$.data[*].id").getFirst();
        mvc.perform(get(s.path("/stock-movements/" + mainId)).cookie(scoped)).andExpect(status().isNotFound());
        as(
                        scoped,
                        unsafe(post(s.path("/stock-movements"))),
                        OrgFixtures.JSON_MAPPER.writeValueAsString(adjustmentBody("1")))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void anotherCompanysDataIsUnreachable() throws Exception {
        Setup other = inv.setup();
        String movements = mvc.perform(get(s.path("/stock-movements")).cookie(s.session()))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String movementId =
                JsonPath.<List<String>>read(movements, "$.data[*].id").getFirst();

        // Their own company path with our IDs: 404.
        mvc.perform(get(other.path("/stock-movements/" + movementId)).cookie(other.session()))
                .andExpect(status().isNotFound());
        mvc.perform(get(other.path("/variants/" + s.variant())).cookie(other.session()))
                .andExpect(status().isNotFound());
        mvc.perform(get(other.path("/warehouses/" + wh.id())).cookie(other.session()))
                .andExpect(status().isNotFound());
        // Our company path: 404 as well (not a member).
        mvc.perform(get(s.path("/stock-levels")).cookie(other.session())).andExpect(status().isNotFound());
        // Our IDs inside their movement are unknown references.
        Map<String, Object> line = inv.line(s.variant(), null, other.warehouse().stock(), "1");
        line.put("unitCostBase", "1");
        inv.createMovement(
                        other, inv.movement(other, "OPENING", other.warehouse().id(), "lines", List.of(line)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].pointer").value("/lines/0/variantId"));
        Map<String, Object> foreignLocation = inv.line(other.variant(), null, wh.stock(), "1");
        foreignLocation.put("unitCostBase", "1");
        inv.createMovement(
                        other,
                        inv.movement(other, "OPENING", other.warehouse().id(), "lines", List.of(foreignLocation)))
                .andExpect(status().isUnprocessableContent());
        inv.createMovement(other, inv.movement(other, "OPENING", wh.id(), "lines", List.of(foreignLocation)))
                .andExpect(status().isUnprocessableContent());
        // Their ledger and stock are empty.
        mvc.perform(get(other.path("/inventory-transactions")).cookie(other.session()))
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    // ------------------------------------------------------------------------------------------

    private Map<String, Object> adjustmentBody(String qty) {
        return inv.movement(
                s,
                "ADJUSTMENT",
                wh.id(),
                "reasonCodeId",
                s.adjustmentReason(),
                "lines",
                List.of(inv.line(s.variant(), wh.stock(), null, qty)));
    }

    private Cookie member(String roleCode) throws Exception {
        TestUser user = auth.user();
        auth.assign(user, roleCode, s.company());
        return auth.login(user);
    }

    private Cookie member(UUID roleId) throws Exception {
        TestUser user = auth.user();
        auth.assign(user, roleId, s.company());
        return auth.login(user);
    }

    private ResultActions as(
            Cookie session,
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
            String body)
            throws Exception {
        return mvc.perform(
                request.cookie(session).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions postAs(Cookie session, UUID movement) throws Exception {
        return mvc.perform(unsafe(post(s.path("/stock-movements/" + movement + "/post")))
                .cookie(session)
                .header("If-Match", etag(0))
                .header("Idempotency-Key", "post-" + UUID.randomUUID()));
    }

    private static UUID id(ResultActions actions) throws Exception {
        String body = actions.andReturn().getResponse().getContentAsString();
        if (actions.andReturn().getResponse().getStatus() != 201) {
            throw new AssertionError(body);
        }
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }
}
