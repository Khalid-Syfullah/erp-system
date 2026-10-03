package com.erp.support;

import static com.erp.support.AuthTestSupport.unsafe;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.erp.support.AuthTestSupport.TestUser;
import com.erp.support.InventoryFixtures.Setup;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * Procurement test setups built through the API on top of {@link InventoryFixtures}: the buyer (the
 * setup's user) also holds every procurement and (non-sensitive) partner permission and may manage
 * tax codes, payment terms and exchange rates; a second user approves (segregation of duties). The
 * setup has a USD supplier with net-30 terms and a 10 % purchase tax code.
 */
@TestComponent
public class ProcurementFixtures {

    public static final String[] ALL_PROCUREMENT = {
        "procurement.requisition.read",
        "procurement.requisition.create",
        "procurement.requisition.approve",
        "procurement.purchase_order.read",
        "procurement.purchase_order.create",
        "procurement.purchase_order.approve",
        "procurement.purchase_order.approve_high",
        "procurement.purchase_order.cancel",
        "procurement.purchase_order.close",
        "procurement.receipt.read",
        "procurement.receipt.create",
        "procurement.receipt.post",
        "procurement.return.manage",
        "procurement.supplier_bill.read",
        "procurement.supplier_bill.create",
        "procurement.supplier_bill.create_direct",
        "procurement.supplier_bill.post",
        "procurement.supplier_bill.override_match",
        "procurement.settings.manage",
        "partners.partner.read",
        "partners.partner.manage",
        "partners.supplier.manage",
        "org.tax_code.read",
        "org.tax_code.manage",
        "org.payment_terms.read",
        "org.payment_terms.manage",
        "org.exchange_rate.read",
        "org.exchange_rate.manage",
        "org.department.read",
        "org.department.manage"
    };

    /** The approver: approves requisitions and orders, reads documents. */
    public static final String[] APPROVER = {
        "procurement.requisition.read",
        "procurement.requisition.approve",
        "procurement.purchase_order.read",
        "procurement.purchase_order.approve",
        "procurement.purchase_order.approve_high"
    };

    /** A ready procurement setup: the inventory setup, a supplier, a tax code, terms and an approver. */
    public record P2P(Setup inv, UUID supplier, UUID taxCode, UUID terms, Cookie approver, TestUser approverUser) {

        public String path(String suffix) {
            return inv.path(suffix);
        }

        public Cookie session() {
            return inv.session();
        }
    }

    private final MockMvc mvc;
    private final AuthTestSupport auth;
    private final InventoryFixtures inventory;

    public ProcurementFixtures(MockMvc mvc, AuthTestSupport auth, InventoryFixtures inventory) {
        this.mvc = mvc;
        this.auth = auth;
        this.inventory = inventory;
    }

    public P2P setup() throws Exception {
        Setup s = inventory.setup();
        auth.assign(s.user(), auth.customRole(ALL_PROCUREMENT), s.company());
        auth.invalidatePermissionCache();
        UUID terms = create(s, "/payment-terms", OrgFixtures.json("code", "NET30", "name", "Net 30", "dueDays", 30));
        UUID tax = create(
                s,
                "/tax-codes",
                OrgFixtures.json("code", "VAT10", "name", "VAT 10 %", "scope", "PURCHASE", "ratePercent", "10"));
        UUID supplier = supplier(s, "ACME", "USD", terms, null);
        TestUser approverUser = auth.user();
        auth.assign(approverUser, auth.customRole(APPROVER), s.company());
        Cookie approver = auth.login(approverUser);
        return new P2P(s, supplier, tax, terms, approver, approverUser);
    }

    /** A partner with a supplier profile; returns the partner (= supplier) ID. */
    public UUID supplier(Setup s, String code, String currency, UUID terms, UUID taxCode) throws Exception {
        UUID partner = create(
                s,
                "/partners",
                OrgFixtures.json("code", code, "name", "Supplier " + code, "partnerType", "ORGANIZATION"));
        MvcResult result = mvc.perform(unsafe(
                                MockMvcRequestBuilders.put(s.path("/partners/" + partner + "/supplier-profile")))
                        .cookie(s.session())
                        .header("If-Match", OrgFixtures.etag(0))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OrgFixtures.json(
                                "currencyCode", currency, "paymentTermsId", terms, "defaultTaxCodeId", taxCode)))
                .andReturn();
        if (result.getResponse().getStatus() != 200) {
            throw new AssertionError(
                    "Supplier profile failed: " + result.getResponse().getContentAsString());
        }
        return partner;
    }

    /** Runs code as the buyer in the company's context and a new transaction (like a calling module). */
    public <T> T inCompany(P2P p, java.util.function.Supplier<T> work) {
        return inventory.inCompany(p.inv(), work);
    }

    /** {@code {variantId, quantity (EA), unitPrice, taxCodeId}}. */
    public Map<String, Object> orderLine(UUID variant, String quantity, String unitPrice, UUID taxCode) {
        return OrgFixtures.map(
                "variantId",
                variant,
                "quantity",
                quantity,
                "uomId",
                inventory.uom("EA"),
                "unitPrice",
                unitPrice,
                "taxCodeId",
                taxCode);
    }

    public Map<String, Object> order(P2P p, Object... lines) {
        return OrgFixtures.map(
                "supplierId", p.supplier(), "warehouseId", p.inv().warehouse().id(), "lines", Arrays.asList(lines));
    }

    public ResultActions createOrder(P2P p, Map<String, Object> body) throws Exception {
        return mvc.perform(unsafe(MockMvcRequestBuilders.post(p.path("/purchase-orders")))
                .cookie(p.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(OrgFixtures.JSON_MAPPER.writeValueAsString(body)));
    }

    /** Creates, submits (buyer) and approves (approver) an order; returns its ID. */
    public UUID approvedOrder(P2P p, Object... lines) throws Exception {
        UUID id = id(createOrder(p, order(p, lines)), 201);
        expect(action(p, p.session(), "/purchase-orders/" + id + "/submit", 0, null, null), 200);
        expect(action(p, p.approver(), "/purchase-orders/" + id + "/approve", 1, "approve-" + id, null), 200);
        return id;
    }

    /** Creates a draft receipt (all open quantities when {@code lines} is null) and posts it. */
    public UUID postedReceipt(P2P p, UUID order, List<Map<String, Object>> lines) throws Exception {
        UUID receipt = id(createReceipt(p, order, lines), 201);
        expect(action(p, p.session(), "/goods-receipts/" + receipt + "/post", 0, "post-" + receipt, null), 200);
        return receipt;
    }

    public ResultActions createReceipt(P2P p, UUID order, List<Map<String, Object>> lines) throws Exception {
        Map<String, Object> body = OrgFixtures.map("purchaseOrderId", order);
        if (lines != null) {
            body.put("lines", lines);
        }
        return mvc.perform(unsafe(MockMvcRequestBuilders.post(p.path("/goods-receipts")))
                .cookie(p.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(OrgFixtures.JSON_MAPPER.writeValueAsString(body)));
    }

    public Map<String, Object> receiptLine(UUID orderLine, String quantity) {
        return OrgFixtures.map("purchaseOrderLineId", orderLine, "quantity", quantity);
    }

    /** POST {path} with If-Match, an optional Idempotency-Key and an optional JSON body. */
    public ResultActions action(P2P p, Cookie session, String path, int version, String key, String body)
            throws Exception {
        MockHttpServletRequestBuilder request = unsafe(MockMvcRequestBuilders.post(p.path(path)))
                .cookie(session)
                .header("If-Match", OrgFixtures.etag(version));
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mvc.perform(request);
    }

    public String body(P2P p, String path) throws Exception {
        MvcResult result = mvc.perform(get(p.path(path)).cookie(p.session())).andReturn();
        if (result.getResponse().getStatus() != 200) {
            throw new AssertionError(path + ": " + result.getResponse().getStatus() + " "
                    + result.getResponse().getContentAsString());
        }
        return result.getResponse().getContentAsString();
    }

    public int version(P2P p, String path) throws Exception {
        return JsonPath.read(body(p, path), "$.version");
    }

    /** IDs of the order's lines in line order. */
    public List<UUID> orderLines(P2P p, UUID order) throws Exception {
        List<String> ids = JsonPath.read(body(p, "/purchase-orders/" + order), "$.lines[*].id");
        List<UUID> result = new ArrayList<>();
        ids.forEach(i -> result.add(UUID.fromString(i)));
        return result;
    }

    public List<UUID> receiptLines(P2P p, UUID receipt) throws Exception {
        List<String> ids = JsonPath.read(body(p, "/goods-receipts/" + receipt), "$.lines[*].id");
        List<UUID> result = new ArrayList<>();
        ids.forEach(i -> result.add(UUID.fromString(i)));
        return result;
    }

    public static UUID id(ResultActions actions, int expectedStatus) throws Exception {
        MvcResult result = actions.andReturn();
        if (result.getResponse().getStatus() != expectedStatus) {
            throw new AssertionError("Expected " + expectedStatus + " but got "
                    + result.getResponse().getStatus() + ": "
                    + result.getResponse().getContentAsString());
        }
        return UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), "$.id"));
    }

    public static MvcResult expect(ResultActions actions, int expectedStatus) throws Exception {
        MvcResult result = actions.andReturn();
        if (result.getResponse().getStatus() != expectedStatus) {
            throw new AssertionError("Expected " + expectedStatus + " but got "
                    + result.getResponse().getStatus() + ": "
                    + result.getResponse().getContentAsString());
        }
        return result;
    }

    private UUID create(Setup s, String path, String body) throws Exception {
        return id(
                mvc.perform(unsafe(MockMvcRequestBuilders.post(s.path(path)))
                        .cookie(s.session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)),
                201);
    }
}
