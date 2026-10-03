package com.erp.support;

import static com.erp.db.inventory.Tables.UOMS;
import static com.erp.support.AuthTestSupport.unsafe;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.erp.platform.context.CurrentContext;
import com.erp.platform.context.RequestContext;
import com.erp.platform.security.ActorType;
import com.erp.platform.security.AuthenticatedActor;
import com.erp.support.AuthTestSupport.TestUser;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.jooq.DSLContext;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Inventory test setups built through the API: a company, a user holding every inventory permission,
 * a branch with a warehouse (its seeded locations), a category, a stockable product in EA and reason
 * codes. Facade calls run in a company context like Procurement and Sales would.
 */
@TestComponent
public class InventoryFixtures {

    /** Every inventory permission (none is sensitive, so no MFA is needed). */
    public static final String[] ALL_INVENTORY = {
        "inventory.product.read",
        "inventory.product.manage",
        "inventory.warehouse.read",
        "inventory.warehouse.manage",
        "inventory.stock.read",
        "inventory.valuation.read",
        "inventory.movement.read",
        "inventory.movement.create",
        "inventory.movement.post",
        "inventory.movement.reverse",
        "inventory.adjustment.manage",
        "inventory.adjustment.approve",
        "inventory.count.manage",
        "inventory.count.post",
        "inventory.settings.manage",
        "org.branch.read"
    };

    /** A warehouse with its locations by code. */
    public record Warehouse(UUID id, Map<String, UUID> locations) {

        public UUID stock() {
            return locations.get("STOCK");
        }

        public UUID transit() {
            return locations.get("TRANSIT");
        }
    }

    /** A ready-to-use inventory setup of one company. */
    public record Setup(
            UUID company,
            TestUser user,
            Cookie session,
            UUID branch,
            Warehouse warehouse,
            UUID category,
            UUID variant,
            UUID product,
            UUID adjustmentReason,
            UUID scrapReason,
            UUID countReason) {

        public String path(String suffix) {
            return "/api/v1/companies/" + company + suffix;
        }

        public LocalDate today() {
            return LocalDate.now(ZoneId.of("America/New_York"));
        }
    }

    private final MockMvc mvc;
    private final AuthTestSupport auth;
    private final DSLContext dsl;
    private final TransactionTemplate tx;

    public InventoryFixtures(MockMvc mvc, AuthTestSupport auth, DSLContext dsl, TransactionTemplate tx) {
        this.mvc = mvc;
        this.auth = auth;
        this.dsl = dsl;
        this.tx = tx;
    }

    public Setup setup() throws Exception {
        UUID company = auth.company();
        TestUser user = auth.user();
        auth.assign(user, auth.customRole(ALL_INVENTORY), company);
        Cookie session = auth.login(user);
        UUID branch = auth.branch(company, "MAIN");
        String base = "/api/v1/companies/" + company;
        Warehouse warehouse = warehouse(session, base, branch, "WH1");
        UUID category =
                create(session, base + "/product-categories", OrgFixtures.json("code", "GOODS", "name", "Goods"));
        UUID product = create(
                session,
                base + "/products",
                OrgFixtures.json(
                        "code",
                        "WIDGET",
                        "name",
                        "Widget",
                        "categoryId",
                        category,
                        "productType",
                        "STOCKABLE",
                        "baseUomId",
                        uom("EA")));
        UUID variant = UUID.fromString(JsonPath.read(
                mvc.perform(get(base + "/products/" + product).cookie(session))
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.variants[0].id"));
        UUID adjustment = reason(session, base, "DAMAGE", "ADJUSTMENT");
        UUID scrap = reason(session, base, "EXPIRED", "SCRAP");
        UUID count = reason(session, base, "CYCLE", "COUNT");
        return new Setup(
                company, user, session, branch, warehouse, category, variant, product, adjustment, scrap, count);
    }

    public Warehouse warehouse(Setup setup, String code) throws Exception {
        return warehouse(setup.session(), setup.path(""), setup.branch(), code);
    }

    public Warehouse warehouse(Cookie session, String base, UUID branch, String code) throws Exception {
        UUID id = create(
                session,
                base + "/warehouses",
                OrgFixtures.json("branchId", branch, "code", code, "name", "Warehouse " + code));
        String body = mvc.perform(get(base + "/warehouses/" + id + "/locations").cookie(session))
                .andReturn()
                .getResponse()
                .getContentAsString();
        List<String> codes = JsonPath.read(body, "$.data[*].code");
        List<String> ids = JsonPath.read(body, "$.data[*].id");
        Map<String, UUID> locations = new HashMap<>();
        for (int i = 0; i < codes.size(); i++) {
            locations.put(codes.get(i), UUID.fromString(ids.get(i)));
        }
        return new Warehouse(id, locations);
    }

    /** A further stockable product in the given base unit; returns its (default) variant. */
    public UUID variant(Setup setup, String code, String baseUom) throws Exception {
        UUID product = create(
                setup.session(),
                setup.path("/products"),
                OrgFixtures.json(
                        "code",
                        code,
                        "name",
                        "Product " + code,
                        "categoryId",
                        setup.category(),
                        "productType",
                        "STOCKABLE",
                        "baseUomId",
                        uom(baseUom)));
        return UUID.fromString(JsonPath.read(
                mvc.perform(get(setup.path("/products/" + product)).cookie(setup.session()))
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.variants[0].id"));
    }

    public UUID uom(String code) {
        return dsl.select(UOMS.ID).from(UOMS).where(UOMS.CODE.eq(code)).fetchSingle(UOMS.ID);
    }

    /** A line moving {@code quantity} EA. */
    public Map<String, Object> line(UUID variant, UUID from, UUID to, String quantity) {
        return OrgFixtures.map(
                "variantId",
                variant,
                "fromLocationId",
                from,
                "toLocationId",
                to,
                "quantity",
                quantity,
                "uomId",
                uom("EA"));
    }

    /** Creates a movement draft through the API and returns the response. */
    public ResultActions createMovement(Setup setup, Map<String, Object> body) throws Exception {
        return mvc.perform(unsafe(MockMvcRequestBuilders.post(setup.path("/stock-movements")))
                .cookie(setup.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(OrgFixtures.JSON_MAPPER.writeValueAsString(body)));
    }

    /** Creates and posts a movement; fails the test unless both succeed. Returns the movement ID. */
    public UUID postMovement(Setup setup, Map<String, Object> body) throws Exception {
        MvcResult created = createMovement(setup, body).andReturn();
        if (created.getResponse().getStatus() != 201) {
            throw new AssertionError("Create failed: " + created.getResponse().getContentAsString());
        }
        UUID id = UUID.fromString(JsonPath.read(created.getResponse().getContentAsString(), "$.id"));
        MvcResult posted = post(setup, id, 0).andReturn();
        if (posted.getResponse().getStatus() != 200) {
            throw new AssertionError("Post failed: " + posted.getResponse().getContentAsString());
        }
        return id;
    }

    public ResultActions post(Setup setup, UUID movement, int version) throws Exception {
        return mvc.perform(unsafe(MockMvcRequestBuilders.post(setup.path("/stock-movements/" + movement + "/post")))
                .cookie(setup.session())
                .header("If-Match", OrgFixtures.etag(version))
                .header("Idempotency-Key", "key-" + UUID.randomUUID()));
    }

    /** Opening stock of {@code quantity} EA at {@code unitCost} into a location. */
    public UUID opening(Setup setup, UUID variant, UUID location, String quantity, String unitCost) throws Exception {
        Map<String, Object> line = line(variant, null, location, quantity);
        line.put("unitCostBase", unitCost);
        return postMovement(
                setup,
                OrgFixtures.map(
                        "movementType",
                        "OPENING",
                        "movementDate",
                        setup.today(),
                        "warehouseId",
                        warehouseOf(setup, location),
                        "lines",
                        List.of(line)));
    }

    public Map<String, Object> movement(Setup setup, String type, UUID warehouse, Object... more) {
        Map<String, Object> body =
                OrgFixtures.map("movementType", type, "movementDate", setup.today(), "warehouseId", warehouse);
        body.putAll(OrgFixtures.map(more));
        return body;
    }

    /** Runs code as the setup's user in the company's context and a new transaction (like a calling module). */
    public <T> T inCompany(Setup setup, Supplier<T> work) {
        RequestContext context = RequestContext.forRequest("test-" + UUID.randomUUID())
                .withActor(new AuthenticatedActor(
                        setup.user().id(),
                        ActorType.USER,
                        UUID.randomUUID(),
                        false,
                        Instant.now(),
                        null,
                        null,
                        null,
                        false,
                        null))
                .withCompany(setup.company());
        return CurrentContext.callWith(context, () -> tx.execute(status -> work.get()));
    }

    public BigDecimal balance(Setup setup, UUID variant, UUID location) {
        return inCompany(setup, () -> {
            BigDecimal value = dsl.fetchValue(
                                    "SELECT on_hand FROM inventory.stock_balances WHERE variant_id = ? AND location_id = ?",
                                    variant,
                                    location)
                            instanceof BigDecimal b
                    ? b
                    : BigDecimal.ZERO;
            return value;
        });
    }

    private UUID warehouseOf(Setup setup, UUID location) {
        return inCompany(
                setup,
                () -> dsl.fetchSingle("SELECT warehouse_id FROM inventory.locations WHERE id = ?", location)
                        .get(0, UUID.class));
    }

    private UUID reason(Cookie session, String base, String code, String appliesTo) throws Exception {
        return create(
                session, base + "/reason-codes", OrgFixtures.json("code", code, "name", code, "appliesTo", appliesTo));
    }

    private UUID create(Cookie session, String path, String body) throws Exception {
        MvcResult result = mvc.perform(unsafe(MockMvcRequestBuilders.post(path))
                        .cookie(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
        if (result.getResponse().getStatus() != 201) {
            throw new AssertionError(
                    "POST " + path + " failed: " + result.getResponse().getStatus() + " "
                            + result.getResponse().getContentAsString());
        }
        return UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), "$.id"));
    }
}
