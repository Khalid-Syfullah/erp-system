package com.erp.procurement;

import static com.erp.support.AuthTestSupport.unsafe;
import static com.erp.support.OrgFixtures.etag;
import static com.erp.support.ProcurementFixtures.expect;
import static com.erp.support.ProcurementFixtures.id;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erp.support.AuthTestSupport;
import com.erp.support.AuthTestSupport.TestUser;
import com.erp.support.IntegrationTest;
import com.erp.support.InventoryFixtures;
import com.erp.support.OrgFixtures;
import com.erp.support.ProcurementFixtures;
import com.erp.support.ProcurementFixtures.P2P;
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

/** Purchase requisitions: workflow, approval authority, conversion and ordering progress. */
class RequisitionIntegrationTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ProcurementFixtures proc;

    @Autowired
    InventoryFixtures inv;

    @Autowired
    AuthTestSupport auth;

    private P2P p;

    @BeforeEach
    void setUp() throws Exception {
        p = proc.setup();
    }

    @Test
    void theWorkflowEnforcesItsTransitionsAndAuthority() throws Exception {
        UUID requisition = id(create(p.session(), line(p.inv().variant(), "5")), 201);
        // Drafts are edited freely.
        mvc.perform(unsafe(patch(p.path("/purchase-requisitions/" + requisition)))
                        .cookie(p.session())
                        .header("If-Match", etag(0))
                        .contentType(OrgFixtures.MERGE_PATCH)
                        .content(OrgFixtures.json("notes", "Urgent")))
                .andExpect(status().isOk());
        // Approval needs a submitted requisition.
        proc.action(p, p.approver(), "/purchase-requisitions/" + requisition + "/approve", 1, null, null)
                .andExpect(status().isConflict());
        expect(proc.action(p, p.session(), "/purchase-requisitions/" + requisition + "/submit", 1, null, null), 200);
        mvc.perform(unsafe(patch(p.path("/purchase-requisitions/" + requisition)))
                        .cookie(p.session())
                        .header("If-Match", etag(2))
                        .contentType(OrgFixtures.MERGE_PATCH)
                        .content(OrgFixtures.json("notes", "Too late")))
                .andExpect(status().isConflict());
        // Approval needs procurement.requisition.approve.
        TestUser requester = auth.user();
        auth.assign(
                requester,
                auth.customRole("procurement.requisition.read", "procurement.requisition.create"),
                p.inv().company());
        Cookie requesterSession = auth.login(requester);
        proc.action(p, requesterSession, "/purchase-requisitions/" + requisition + "/approve", 2, null, null)
                .andExpect(status().isForbidden());
        proc.action(
                        p,
                        p.approver(),
                        "/purchase-requisitions/" + requisition + "/reject",
                        2,
                        null,
                        OrgFixtures.json("reason", "Not budgeted"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.rejectionReason").value("Not budgeted"));
        // A rejected requisition is final.
        proc.action(p, p.session(), "/purchase-requisitions/" + requisition + "/cancel", 3, null, null)
                .andExpect(status().isConflict());

        UUID second = id(create(p.session(), line(p.inv().variant(), "1")), 201);
        expect(proc.action(p, p.session(), "/purchase-requisitions/" + second + "/cancel", 0, null, null), 200);
        mvc.perform(get(p.path("/purchase-requisitions/" + second)).cookie(p.session()))
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.number").doesNotExist());
    }

    @Test
    void conversionTracksOrderedQuantities() throws Exception {
        UUID gadget = inv.variant(p.inv(), "GADGET", "EA");
        UUID requisition = id(create(p.session(), line(p.inv().variant(), "10"), line(gadget, "4")), 201);
        expect(proc.action(p, p.session(), "/purchase-requisitions/" + requisition + "/submit", 0, null, null), 200);
        expect(proc.action(p, p.approver(), "/purchase-requisitions/" + requisition + "/approve", 1, null, null), 200);
        List<String> lines = JsonPath.read(proc.body(p, "/purchase-requisitions/" + requisition), "$.lines[*].id");

        // First only the widget line goes to the supplier.
        String order = expect(convert(requisition, 2, List.of(lines.getFirst())), 201)
                .getResponse()
                .getContentAsString();
        UUID orderId = UUID.fromString(JsonPath.read(order, "$.id"));
        mvc.perform(get(p.path("/purchase-requisitions/" + requisition)).cookie(p.session()))
                .andExpect(jsonPath("$.status").value("PARTIALLY_ORDERED"))
                .andExpect(jsonPath("$.lines[*].orderedQuantityBase").value(contains("10.000000", "0.000000")));
        // A line is ordered once.
        convert(requisition, 3, List.of(lines.getFirst()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].code").value("NOTHING_TO_ORDER"));
        // Raising the ordered quantity on the order beyond the requisition is refused.
        mvc.perform(unsafe(patch(p.path("/purchase-orders/" + orderId)))
                        .cookie(p.session())
                        .header("If-Match", etag(0))
                        .contentType(OrgFixtures.MERGE_PATCH)
                        .content(OrgFixtures.JSON_MAPPER.writeValueAsString(Map.of(
                                "lines",
                                List.of(OrgFixtures.map(
                                        "variantId", p.inv().variant().toString(),
                                        "quantity", "11",
                                        "uomId", inv.uom("EA").toString(),
                                        "unitPrice", "1",
                                        "requisitionLineId", lines.getFirst()))))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("QUANTITY_EXCEEDS_REMAINING"));
        // The rest, then everything is ordered.
        expect(convert(requisition, 3, List.of()), 201);
        mvc.perform(get(p.path("/purchase-requisitions/" + requisition)).cookie(p.session()))
                .andExpect(jsonPath("$.status").value("ORDERED"));
        // Cancelling the first order frees its quantity again.
        expect(proc.action(p, p.session(), "/purchase-orders/" + orderId + "/cancel", 0, null, null), 200);
        mvc.perform(get(p.path("/purchase-requisitions/" + requisition)).cookie(p.session()))
                .andExpect(jsonPath("$.status").value("PARTIALLY_ORDERED"))
                .andExpect(jsonPath("$.lines[*].orderedQuantityBase").value(contains("0.000000", "4.000000")));
    }

    @Test
    void branchRestrictedUsersSeeTheirBranchesOnly() throws Exception {
        UUID north = auth.branch(p.inv().company(), "NORTH");
        UUID mine = id(create(p.session(), line(p.inv().variant(), "1")), 201);
        TestUser scoped = auth.user();
        auth.assign(
                scoped,
                auth.customRole(ProcurementFixtures.ALL_PROCUREMENT),
                p.inv().company(),
                north);
        Cookie scopedSession = auth.login(scoped);
        mvc.perform(get(p.path("/purchase-requisitions/" + mine)).cookie(scopedSession))
                .andExpect(status().isNotFound());
        mvc.perform(get(p.path("/purchase-requisitions")).cookie(scopedSession))
                .andExpect(jsonPath("$.data.length()").value(0));
        // Nor can they file one for another branch.
        create(scopedSession, line(p.inv().variant(), "1"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].pointer").value("/branchId"));
    }

    private ResultActions convert(UUID requisition, int version, List<String> lineIds) throws Exception {
        return proc.action(
                p,
                p.session(),
                "/purchase-requisitions/" + requisition + "/convert",
                version,
                "convert-" + UUID.randomUUID(),
                OrgFixtures.json(
                        "supplierId",
                        p.supplier(),
                        "warehouseId",
                        p.inv().warehouse().id(),
                        "lineIds",
                        lineIds));
    }

    private ResultActions create(Cookie session, Map<?, ?>... lines) throws Exception {
        return mvc.perform(unsafe(post(p.path("/purchase-requisitions")))
                .cookie(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content(OrgFixtures.json("branchId", p.inv().branch(), "lines", List.of(lines))));
    }

    private Map<String, Object> line(UUID variant, String quantity) {
        return OrgFixtures.map(
                "variantId", variant, "quantity", quantity, "uomId", inv.uom("EA"), "estimatedUnitPrice", "1");
    }
}
