package com.erp.support;

import static com.erp.support.AuthTestSupport.unsafe;
import static com.erp.support.ProcurementFixtures.expect;
import static com.erp.support.ProcurementFixtures.id;
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
 * Sales test setups built through the API on top of {@link InventoryFixtures}: the seller (the setup's
 * user) holds every sales permission except the overrides (credit, price, high discount, direct
 * invoices), which a second user, the manager, holds. The setup has a USD customer with net-30 terms
 * and a 10 % sales tax code, and a default USD price list pricing the setup's product at 25 per EA.
 */
@TestComponent
public class SalesFixtures {

    public static final String[] SELLER = {
        "sales.price_list.read",
        "sales.price_list.manage",
        "sales.quotation.read",
        "sales.quotation.manage",
        "sales.order.read",
        "sales.order.create",
        "sales.order.confirm",
        "sales.order.cancel",
        "sales.order.close",
        "sales.delivery.read",
        "sales.delivery.create",
        "sales.delivery.post",
        "sales.return.manage",
        "sales.invoice.read",
        "sales.invoice.create",
        "sales.invoice.post",
        "sales.settings.manage",
        "partners.partner.read",
        "partners.partner.manage",
        "partners.customer.manage",
        "org.tax_code.read",
        "org.tax_code.manage",
        "org.payment_terms.read",
        "org.payment_terms.manage",
        "org.exchange_rate.read",
        "org.exchange_rate.manage",
        "org.department.read",
        "org.department.manage"
    };

    /** The sales manager: the overrides, plus what is needed to use them. */
    public static final String[] MANAGER = {
        "sales.order.read",
        "sales.order.create",
        "sales.order.confirm",
        "sales.order.override_credit",
        "sales.order.override_price",
        "sales.order.discount_high",
        "sales.quotation.read",
        "sales.quotation.manage",
        "sales.invoice.read",
        "sales.invoice.create",
        "sales.invoice.create_direct",
        "sales.invoice.post"
    };

    /** A ready order-to-cash setup. */
    public record O2C(
            Setup inv, UUID customer, UUID taxCode, UUID terms, UUID priceList, Cookie manager, TestUser managerUser) {

        public String path(String suffix) {
            return inv.path(suffix);
        }

        public Cookie session() {
            return inv.session();
        }

        public UUID variant() {
            return inv.variant();
        }

        public UUID warehouse() {
            return inv.warehouse().id();
        }

        public UUID stock() {
            return inv.warehouse().stock();
        }
    }

    private final MockMvc mvc;
    private final AuthTestSupport auth;
    private final InventoryFixtures inventory;

    public SalesFixtures(MockMvc mvc, AuthTestSupport auth, InventoryFixtures inventory) {
        this.mvc = mvc;
        this.auth = auth;
        this.inventory = inventory;
    }

    public O2C setup() throws Exception {
        Setup s = inventory.setup();
        auth.assign(s.user(), auth.customRole(SELLER), s.company());
        auth.invalidatePermissionCache();
        UUID terms = create(s, "/payment-terms", OrgFixtures.json("code", "NET30", "name", "Net 30", "dueDays", 30));
        UUID tax = create(
                s,
                "/tax-codes",
                OrgFixtures.json("code", "OUT10", "name", "Output VAT 10 %", "scope", "SALES", "ratePercent", "10"));
        UUID customer = customer(s, "CUST1", "USD", terms, tax, null);
        UUID list = create(
                s,
                "/price-lists",
                OrgFixtures.json("code", "STD", "name", "Standard", "currencyCode", "USD", "isDefault", true));
        create(
                s,
                "/price-lists/" + list + "/items",
                OrgFixtures.json("variantId", s.variant(), "uomId", inventory.uom("EA"), "unitPrice", "25"));
        TestUser managerUser = auth.user();
        auth.assign(managerUser, auth.customRole(MANAGER), s.company());
        Cookie manager = auth.login(managerUser);
        return new O2C(s, customer, tax, terms, list, manager, managerUser);
    }

    /** A partner with a customer profile; returns the partner (= customer) ID. */
    public UUID customer(Setup s, String code, String currency, UUID terms, UUID taxCode, String creditLimit)
            throws Exception {
        UUID partner = create(
                s,
                "/partners",
                OrgFixtures.json("code", code, "name", "Customer " + code, "partnerType", "ORGANIZATION"));
        MvcResult result = mvc.perform(
                        unsafe(MockMvcRequestBuilders.put(s.path("/partners/" + partner + "/customer-profile")))
                                .cookie(s.session())
                                .header("If-Match", OrgFixtures.etag(0))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(OrgFixtures.json(
                                        "currencyCode",
                                        currency,
                                        "paymentTermsId",
                                        terms,
                                        "defaultTaxCodeId",
                                        taxCode,
                                        "creditLimit",
                                        creditLimit)))
                .andReturn();
        if (result.getResponse().getStatus() != 200) {
            throw new AssertionError(
                    "Customer profile failed: " + result.getResponse().getContentAsString());
        }
        return partner;
    }

    /** A service product (never delivered, invoiceable directly); returns its variant. */
    public UUID service(O2C o, String code) throws Exception {
        UUID product = create(
                o.inv(),
                "/products",
                OrgFixtures.json(
                        "code",
                        code,
                        "name",
                        "Service " + code,
                        "categoryId",
                        o.inv().category(),
                        "productType",
                        "SERVICE",
                        "baseUomId",
                        inventory.uom("EA")));
        return UUID.fromString(JsonPath.read(body(o, "/products/" + product), "$.variants[0].id"));
    }

    /** An exchange rate of the currency from the setup's today. */
    public void rate(O2C o, String currency, String rate) throws Exception {
        create(
                o.inv(),
                "/exchange-rates",
                OrgFixtures.json("currencyCode", currency, "rateDate", o.inv().today(), "rate", rate));
    }

    /** Opening stock of the setup's product in WH1/STOCK. */
    public void stock(O2C o, String quantity, String unitCost) throws Exception {
        inventory.opening(o.inv(), o.variant(), o.stock(), quantity, unitCost);
    }

    public java.math.BigDecimal onHand(O2C o) {
        return inventory.balance(o.inv(), o.variant(), o.stock());
    }

    /** Runs code as the seller in the company's context and a new transaction. */
    public <T> T inCompany(O2C o, java.util.function.Supplier<T> work) {
        return inventory.inCompany(o.inv(), work);
    }

    /** {@code {variantId, quantity (EA), unitPrice?}}; a null price takes the price list's. */
    public Map<String, Object> line(UUID variant, String quantity, String unitPrice) {
        Map<String, Object> line =
                OrgFixtures.map("variantId", variant, "quantity", quantity, "uomId", inventory.uom("EA"));
        if (unitPrice != null) {
            line.put("unitPrice", unitPrice);
        }
        return line;
    }

    public Map<String, Object> order(O2C o, Object... lines) {
        return OrgFixtures.map("customerId", o.customer(), "warehouseId", o.warehouse(), "lines", Arrays.asList(lines));
    }

    public ResultActions create(O2C o, String path, Map<String, Object> body) throws Exception {
        return create(o, o.session(), path, body);
    }

    public ResultActions create(O2C o, Cookie session, String path, Map<String, Object> body) throws Exception {
        return mvc.perform(unsafe(MockMvcRequestBuilders.post(o.path(path)))
                .cookie(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content(OrgFixtures.JSON_MAPPER.writeValueAsString(body)));
    }

    public UUID draftOrder(O2C o, Object... lines) throws Exception {
        return id(create(o, "/sales-orders", order(o, lines)), 201);
    }

    /** Creates and confirms an order; returns its ID. */
    public UUID confirmedOrder(O2C o, Object... lines) throws Exception {
        UUID id = draftOrder(o, lines);
        expect(action(o, o.session(), "/sales-orders/" + id + "/confirm", 0, "confirm-" + id, null), 200);
        return id;
    }

    public ResultActions createDelivery(O2C o, UUID order, List<Map<String, Object>> lines) throws Exception {
        Map<String, Object> body = OrgFixtures.map("salesOrderId", order);
        if (lines != null) {
            body.put("lines", lines);
        }
        return create(o, "/deliveries", body);
    }

    /** Creates a delivery (everything open when {@code lines} is null) and posts it. */
    public UUID postedDelivery(O2C o, UUID order, List<Map<String, Object>> lines) throws Exception {
        UUID delivery = id(createDelivery(o, order, lines), 201);
        expect(action(o, o.session(), "/deliveries/" + delivery + "/post", 0, "post-" + delivery, null), 200);
        return delivery;
    }

    public Map<String, Object> deliveryLine(UUID orderLine, String quantity) {
        return OrgFixtures.map("salesOrderLineId", orderLine, "quantity", quantity);
    }

    /** Invoices everything invoiceable on the order and posts the invoice. */
    public UUID postedInvoice(O2C o, UUID order) throws Exception {
        UUID invoice = id(create(o, "/invoices/from-order", OrgFixtures.map("salesOrderId", order)), 201);
        expect(action(o, o.session(), "/invoices/" + invoice + "/post", 0, "post-" + invoice, null), 200);
        return invoice;
    }

    /** POST {path} with If-Match, an optional Idempotency-Key and an optional JSON body. */
    public ResultActions action(O2C o, Cookie session, String path, int version, String key, String body)
            throws Exception {
        MockHttpServletRequestBuilder request = unsafe(MockMvcRequestBuilders.post(o.path(path)))
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

    public String body(O2C o, String path) throws Exception {
        MvcResult result = mvc.perform(get(o.path(path)).cookie(o.session())).andReturn();
        if (result.getResponse().getStatus() != 200) {
            throw new AssertionError(path + ": " + result.getResponse().getStatus() + " "
                    + result.getResponse().getContentAsString());
        }
        return result.getResponse().getContentAsString();
    }

    public int version(O2C o, String path) throws Exception {
        return JsonPath.read(body(o, path), "$.version");
    }

    public <T> T read(O2C o, String path, String jsonPath) throws Exception {
        return JsonPath.read(body(o, path), jsonPath);
    }

    /** IDs at a JSON path of a resource ({@code $.lines[*].id}). */
    public List<UUID> ids(O2C o, String path, String jsonPath) throws Exception {
        List<String> ids = JsonPath.read(body(o, path), jsonPath);
        List<UUID> result = new ArrayList<>();
        ids.forEach(i -> result.add(UUID.fromString(i)));
        return result;
    }

    public List<UUID> orderLines(O2C o, UUID order) throws Exception {
        return ids(o, "/sales-orders/" + order, "$.lines[*].id");
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
