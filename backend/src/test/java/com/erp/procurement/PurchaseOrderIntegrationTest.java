package com.erp.procurement;

import static com.erp.support.AuthTestSupport.unsafe;
import static com.erp.support.OrgFixtures.etag;
import static com.erp.support.ProcurementFixtures.expect;
import static com.erp.support.ProcurementFixtures.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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

/** Purchase orders: pricing, validation, the workflow and its authority (API.md §17.6, PRODUCT_SPEC.md §7). */
class PurchaseOrderIntegrationTest extends IntegrationTest {

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
    void theServerPricesLinesAndTaxes() throws Exception {
        UUID service = serviceVariant();
        Map<String, Object> discounted = proc.orderLine(p.inv().variant(), "3", "10.00", p.taxCode());
        discounted.put("discountPercent", "10");
        Map<String, Object> plain = proc.orderLine(service, "1", "0.333", null);
        plain.put("uomId", inv.uom("H"));
        mvc.perform(unsafe(post(p.path("/purchase-orders")))
                        .cookie(p.session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OrgFixtures.JSON_MAPPER.writeValueAsString(proc.order(p, discounted, plain))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.number").doesNotExist())
                .andExpect(jsonPath("$.currencyCode").value("USD"))
                .andExpect(jsonPath("$.paymentTermsId").value(p.terms().toString()))
                .andExpect(jsonPath("$.lines[0].netAmount").value("27.0000"))
                .andExpect(jsonPath("$.lines[0].taxAmount").value("2.7000"))
                .andExpect(jsonPath("$.lines[0].isStockable").value(true))
                .andExpect(jsonPath("$.lines[1].netAmount").value("0.3300"))
                .andExpect(jsonPath("$.lines[1].isStockable").value(false))
                .andExpect(jsonPath("$.subtotal").value("27.3300"))
                .andExpect(jsonPath("$.taxTotal").value("2.7000"))
                .andExpect(jsonPath("$.total").value("30.0300"));

        // Tax-inclusive prices: 110.00 gross is 100.00 net plus 10.00 tax.
        Map<String, Object> body = proc.order(p, proc.orderLine(p.inv().variant(), "1", "110.00", p.taxCode()));
        body.put("pricesIncludeTax", true);
        proc.createOrder(p, body)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.subtotal").value("100.0000"))
                .andExpect(jsonPath("$.taxTotal").value("10.0000"))
                .andExpect(jsonPath("$.total").value("110.0000"));
    }

    @Test
    void invalidOrdersAreRefusedWithEveryProblem() throws Exception {
        UUID salesTax = id(
                mvc.perform(unsafe(post(p.path("/tax-codes")))
                        .cookie(p.session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OrgFixtures.json(
                                "code", "OUT", "name", "Output", "scope", "SALES", "ratePercent", "5"))),
                201);
        Map<String, Object> body = proc.order(
                p,
                proc.orderLine(UUID.randomUUID(), "1", "1", null),
                proc.orderLine(p.inv().variant(), "1.5", "1", null),
                proc.orderLine(p.inv().variant(), "1", "1", salesTax));
        body.put("expectedDate", p.inv().today().minusDays(1).toString());
        proc.createOrder(p, body)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[*].pointer")
                        .value(org.hamcrest.Matchers.containsInAnyOrder(
                                "/expectedDate", "/lines/0/variantId", "/lines/1/quantity", "/lines/2/taxCodeId")));
        // Decimals are strings; amounts sent by the client are ignored (unknown property).
        mvc.perform(unsafe(post(p.path("/purchase-orders")))
                        .cookie(p.session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OrgFixtures.json(
                                "supplierId", p.supplier(),
                                "warehouseId", p.inv().warehouse().id(),
                                "total", "1",
                                "lines", List.of(proc.orderLine(p.inv().variant(), "1", "1", null)))))
                .andExpect(status().isBadRequest());
        // A blocked supplier is not used on new orders.
        mvc.perform(unsafe(post(p.path("/partners/" + p.supplier() + "/block")))
                        .cookie(p.session())
                        .header("If-Match", etag(0)))
                .andExpect(status().isOk());
        proc.createOrder(p, proc.order(p, proc.orderLine(p.inv().variant(), "1", "1", null)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("PARTNER_BLOCKED"));
    }

    @Test
    void theWorkflowAllowsOnlyDocumentedTransitions() throws Exception {
        UUID order = id(proc.createOrder(p, proc.order(p, proc.orderLine(p.inv().variant(), "5", "2", null))), 201);
        // Approving a draft is not a transition.
        proc.action(p, p.approver(), "/purchase-orders/" + order + "/approve", 0, "k-" + order, null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE"));
        expect(proc.action(p, p.session(), "/purchase-orders/" + order + "/submit", 0, null, null), 200);
        String number = JsonPath.read(proc.body(p, "/purchase-orders/" + order), "$.number");
        // Submitted orders are not editable.
        mvc.perform(unsafe(patch(p.path("/purchase-orders/" + order)))
                        .cookie(p.session())
                        .header("If-Match", etag(1))
                        .contentType(OrgFixtures.MERGE_PATCH)
                        .content(OrgFixtures.json("notes", "late change")))
                .andExpect(status().isConflict());
        // Rejected back to draft with the reason; it keeps its number when resubmitted.
        proc.action(
                        p,
                        p.approver(),
                        "/purchase-orders/" + order + "/reject",
                        1,
                        null,
                        OrgFixtures.json("reason", "Wrong price"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.rejectionReason").value("Wrong price"));
        expect(proc.action(p, p.session(), "/purchase-orders/" + order + "/submit", 2, null, null), 200);
        assertThat((String) JsonPath.read(proc.body(p, "/purchase-orders/" + order), "$.number"))
                .isEqualTo(number);
        // A stale ETag is refused.
        proc.action(p, p.approver(), "/purchase-orders/" + order + "/approve", 1, "stale-" + order, null)
                .andExpect(status().isPreconditionFailed());
        expect(proc.action(p, p.approver(), "/purchase-orders/" + order + "/approve", 3, "ok-" + order, null), 200);
        // Approved orders are never edited or deleted, but can be cancelled while nothing was received.
        mvc.perform(unsafe(delete(p.path("/purchase-orders/" + order)))
                        .cookie(p.session())
                        .header("If-Match", etag(4)))
                .andExpect(status().isConflict());
        proc.action(p, p.session(), "/purchase-orders/" + order + "/close", 4, null, null)
                .andExpect(status().isConflict());
        proc.action(
                        p,
                        p.session(),
                        "/purchase-orders/" + order + "/cancel",
                        4,
                        null,
                        OrgFixtures.json("reason", "No longer needed"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelReason").value("No longer needed"));
        proc.action(p, p.session(), "/purchase-orders/" + order + "/submit", 5, null, null)
                .andExpect(status().isConflict());
    }

    @Test
    void receivedOrdersAreClosedNotCancelled() throws Exception {
        UUID order = proc.approvedOrder(p, proc.orderLine(p.inv().variant(), "10", "1", null));
        UUID line = proc.orderLines(p, order).getFirst();
        proc.postedReceipt(p, order, List.of(proc.receiptLine(line, "4")));
        UUID draftReceipt = id(proc.createReceipt(p, order, List.of(proc.receiptLine(line, "6"))), 201);
        int version = proc.version(p, "/purchase-orders/" + order);
        proc.action(p, p.session(), "/purchase-orders/" + order + "/cancel", version, null, null)
                .andExpect(status().isConflict());
        proc.action(
                        p,
                        p.session(),
                        "/purchase-orders/" + order + "/close",
                        version,
                        null,
                        OrgFixtures.json("reason", "Short delivery"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));
        // Its open draft receipt was cancelled, and no new receipt is accepted.
        mvc.perform(get(p.path("/goods-receipts/" + draftReceipt)).cookie(p.session()))
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        proc.createReceipt(p, order, null).andExpect(status().isConflict());
    }

    @Test
    void largeOrdersNeedHigherApprovalAuthority() throws Exception {
        mvc.perform(unsafe(put(p.path("/settings/procurement")))
                        .cookie(p.session())
                        .header("If-Match", etag(0))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OrgFixtures.json(
                                "poApprovalThresholdBase", "100",
                                "priceMatchTolerancePercent", "0",
                                "qtyMatchTolerancePercent", "0")))
                .andExpect(status().isOk());
        TestUser limited = auth.user();
        auth.assign(
                limited,
                auth.customRole("procurement.purchase_order.read", "procurement.purchase_order.approve"),
                p.inv().company());
        Cookie limitedSession = auth.login(limited);

        UUID small = id(proc.createOrder(p, proc.order(p, proc.orderLine(p.inv().variant(), "10", "10", null))), 201);
        expect(proc.action(p, p.session(), "/purchase-orders/" + small + "/submit", 0, null, null), 200);
        expect(
                proc.action(
                        p, limitedSession, "/purchase-orders/" + small + "/approve", 1, "approve-small-order", null),
                200);

        UUID large = id(proc.createOrder(p, proc.order(p, proc.orderLine(p.inv().variant(), "11", "10", null))), 201);
        expect(proc.action(p, p.session(), "/purchase-orders/" + large + "/submit", 0, null, null), 200);
        proc.action(p, limitedSession, "/purchase-orders/" + large + "/approve", 1, "approve-large-1", null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        expect(proc.action(p, p.approver(), "/purchase-orders/" + large + "/approve", 1, "approve-large-2", null), 200);
    }

    @Test
    void ordersOfOtherCompaniesAreInvisibleAndTheirRecordsUnusable() throws Exception {
        P2P other = proc.setup();
        UUID order = id(proc.createOrder(p, proc.order(p, proc.orderLine(p.inv().variant(), "1", "1", null))), 201);
        mvc.perform(get(other.path("/purchase-orders/" + order)).cookie(other.session()))
                .andExpect(status().isNotFound());
        mvc.perform(get(p.path("/purchase-orders/" + order)).cookie(other.session()))
                .andExpect(status().isNotFound());
        // Our supplier, warehouse and product inside their order are unknown references.
        Map<String, Object> body = OrgFixtures.map(
                "supplierId", p.supplier(),
                "warehouseId", p.inv().warehouse().id(),
                "lines", List.of(proc.orderLine(p.inv().variant(), "1", "1", null)));
        proc.createOrder(other, body).andExpect(status().isUnprocessableContent());
        Map<String, Object> theirSupplier = OrgFixtures.map(
                "supplierId", other.supplier(),
                "warehouseId", p.inv().warehouse().id(),
                "lines", List.of(proc.orderLine(other.inv().variant(), "1", "1", null)));
        proc.createOrder(other, theirSupplier)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].pointer").value("/warehouseId"));
    }

    @Test
    void draftsAreEditableAndDeletable() throws Exception {
        UUID order = id(proc.createOrder(p, proc.order(p, proc.orderLine(p.inv().variant(), "1", "1", null))), 201);
        mvc.perform(unsafe(patch(p.path("/purchase-orders/" + order)))
                        .cookie(p.session())
                        .header("If-Match", etag(0))
                        .contentType(OrgFixtures.MERGE_PATCH)
                        .content(OrgFixtures.JSON_MAPPER.writeValueAsString(OrgFixtures.map(
                                "notes",
                                "Rush",
                                "lines",
                                List.of(OrgFixtures.map(
                                        "variantId",
                                        p.inv().variant().toString(),
                                        "quantity",
                                        "2",
                                        "uomId",
                                        inv.uom("EA").toString(),
                                        "unitPrice",
                                        "3.50"))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value("7.0000"))
                .andExpect(jsonPath("$.notes").value("Rush"));
        mvc.perform(unsafe(delete(p.path("/purchase-orders/" + order)))
                        .cookie(p.session())
                        .header("If-Match", etag(1)))
                .andExpect(status().isNoContent());
        mvc.perform(get(p.path("/purchase-orders/" + order)).cookie(p.session()))
                .andExpect(status().isNotFound());
    }

    private UUID serviceVariant() throws Exception {
        String product = expect(
                        mvc.perform(unsafe(post(p.path("/products")))
                                .cookie(p.session())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(OrgFixtures.json(
                                        "code",
                                        "INSTALL",
                                        "name",
                                        "Installation",
                                        "categoryId",
                                        p.inv().category(),
                                        "productType",
                                        "SERVICE",
                                        "baseUomId",
                                        inv.uom("H")))),
                        201)
                .getResponse()
                .getContentAsString();
        return UUID.fromString(JsonPath.read(product, "$.variants[0].id"));
    }
}
