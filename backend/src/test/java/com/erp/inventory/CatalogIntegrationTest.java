package com.erp.inventory;

import static com.erp.support.AuthTestSupport.unsafe;
import static com.erp.support.OrgFixtures.etag;
import static com.erp.support.OrgFixtures.json;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erp.support.IntegrationTest;
import com.erp.support.InventoryFixtures;
import com.erp.support.InventoryFixtures.Setup;
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

/** Product master data: categories, products, attributes, variants and units (API.md §17.5). */
class CatalogIntegrationTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    InventoryFixtures inv;

    private Setup s;

    @BeforeEach
    void setUp() throws Exception {
        s = inv.setup();
    }

    @Test
    void categoriesFormATreeWithoutCycles() throws Exception {
        UUID a = id(create("/product-categories", json("code", "A", "name", "A")));
        UUID b = id(create("/product-categories", json("code", "B", "name", "B", "parentId", a)));
        UUID c = id(create("/product-categories", json("code", "C", "name", "C", "parentId", b)));

        mvc.perform(get(s.path("/product-categories/" + c)).cookie(s.session()))
                .andExpect(jsonPath("$.parentId").value(b.toString()));
        // Moving A below its grandchild would create a cycle.
        patchJson("/product-categories/" + a, 0, json("parentId", c)).andExpect(status().isUnprocessableContent());
        patchJson("/product-categories/" + a, 0, json("parentId", a)).andExpect(status().isUnprocessableContent());
        // Moving a subtree is fine.
        patchJson("/product-categories/" + c, 0, json("parentId", a)).andExpect(status().isOk());
        create("/product-categories", json("code", "A", "name", "Again"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_CODE"));
        mvc.perform(get(s.path("/product-categories")).cookie(s.session()).param("filter[parentId]", a.toString()))
                .andExpect(jsonPath("$.data[*].code").value(contains("B", "C")));
    }

    @Test
    void productsHaveUniqueCodesSkusAndBarcodes() throws Exception {
        create("/products", product("WIDGET", "WIDGET-2", null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_CODE"));
        create("/products", product("SPROCKET", "SKU-1", "4006381333931")).andExpect(status().isCreated());
        create("/products", product("GEAR", "SKU-1", null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_SKU"));
        create("/products", product("GEAR", "SKU-2", "4006381333931"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_BARCODE"));
        // Codes and SKUs are case-insensitive.
        create("/products", product("COG", "sku-1", null)).andExpect(status().isConflict());
        // Units must exist; tax codes must belong to the company.
        create(
                        "/products",
                        json(
                                "code",
                                "BAD",
                                "name",
                                "Bad",
                                "categoryId",
                                s.category(),
                                "productType",
                                "STOCKABLE",
                                "baseUomId",
                                UUID.randomUUID()))
                .andExpect(status().isUnprocessableContent());
        create(
                        "/products",
                        json(
                                "code",
                                "BAD",
                                "name",
                                "Bad",
                                "categoryId",
                                s.category(),
                                "productType",
                                "STOCKABLE",
                                "baseUomId",
                                inv.uom("EA"),
                                "salesTaxCodeId",
                                UUID.randomUUID()))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void variantsAreDefinedByAttributeCombinations() throws Exception {
        String color = body(create(
                "/product-attributes",
                OrgFixtures.JSON_MAPPER.writeValueAsString(Map.of(
                        "code", "COLOR",
                        "name", "Colour",
                        "values",
                                List.of(
                                        Map.of("code", "RED", "name", "Red"),
                                        Map.of("code", "BLUE", "name", "Blue"))))));
        UUID colorId = UUID.fromString(JsonPath.read(color, "$.id"));
        UUID red = valueId(color, "RED");
        UUID blue = valueId(color, "BLUE");
        String size = body(create(
                "/product-attributes",
                OrgFixtures.JSON_MAPPER.writeValueAsString(Map.of(
                        "code", "SIZE", "name", "Size", "values", List.of(Map.of("code", "L", "name", "Large"))))));
        UUID large = UUID.fromString(JsonPath.read(size, "$.values[0].id"));

        UUID shirt = id(create(
                "/products",
                json(
                        "code",
                        "SHIRT",
                        "name",
                        "Shirt",
                        "categoryId",
                        s.category(),
                        "productType",
                        "STOCKABLE",
                        "baseUomId",
                        inv.uom("EA"),
                        "hasVariants",
                        true)));
        mvc.perform(get(s.path("/products/" + shirt)).cookie(s.session()))
                .andExpect(jsonPath("$.variants.length()").value(0));

        create("/products/" + shirt + "/variants", variant("SHIRT-RED", Map.of(colorId, red)))
                .andExpect(status().isCreated());
        create("/products/" + shirt + "/variants", variant("SHIRT-BLUE", Map.of(colorId, blue)))
                .andExpect(status().isCreated());
        create("/products/" + shirt + "/variants", variant("SHIRT-RED-2", Map.of(colorId, red)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_VARIANT"));
        // A value must belong to the attribute it is given for.
        create("/products/" + shirt + "/variants", variant("SHIRT-X", Map.of(colorId, large)))
                .andExpect(status().isUnprocessableContent());
        // A product without variants keeps its single default variant.
        create("/products/" + s.product() + "/variants", variant("WIDGET-RED", Map.of(colorId, red)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE"));

        mvc.perform(get(s.path("/variants")).cookie(s.session()).param("filter[productId]", shirt.toString()))
                .andExpect(jsonPath("$.data[*].sku").value(contains("SHIRT-BLUE", "SHIRT-RED")));
        mvc.perform(get(s.path("/variants")).cookie(s.session()).param("q", "blue"))
                .andExpect(jsonPath("$.data[*].sku").value(contains("SHIRT-BLUE")));
        // Lookups by ID (the web app batches the names it shows): at most 100 IDs per request.
        String blueVariant = JsonPath.<List<String>>read(
                        body(get(s.path("/variants")).param("q", "blue")), "$.data[*].id")
                .getFirst();
        mvc.perform(get(s.path("/variants"))
                        .cookie(s.session())
                        .param("filter[id][in]", blueVariant + "," + UUID.randomUUID()))
                .andExpect(jsonPath("$.data[*].sku").value(contains("SHIRT-BLUE")));
        String tooMany = String.join(
                ",",
                java.util.stream.Stream.generate(() -> UUID.randomUUID().toString())
                        .limit(101)
                        .toList());
        mvc.perform(get(s.path("/variants")).cookie(s.session()).param("filter[id][in]", tooMany))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("TOO_MANY_VALUES"));
    }

    @Test
    void archivedProductsCannotMoveAndBaseUnitsLockOnceStocked() throws Exception {
        UUID gadget = inv.variant(s, "GADGET", "EA");
        UUID gadgetProduct = UUID.fromString(JsonPath.read(body(get(s.path("/variants/" + gadget))), "$.productId"));
        mvc.perform(unsafe(post(s.path("/products/" + gadgetProduct + "/archive")))
                        .cookie(s.session())
                        .header("If-Match", etag(0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ARCHIVED"));
        Map<String, Object> line = inv.line(gadget, null, s.warehouse().stock(), "1");
        line.put("unitCostBase", "1");
        inv.createMovement(s, inv.movement(s, "OPENING", s.warehouse().id(), "lines", List.of(line)))
                .andExpect(status().isUnprocessableContent());

        // The base unit can change until stock exists, then never again.
        patchJson("/products/" + s.product(), 0, json("baseUomId", inv.uom("DOZ")))
                .andExpect(status().isOk());
        patchJson("/products/" + s.product(), 1, json("baseUomId", inv.uom("EA")))
                .andExpect(status().isOk());
        Map<String, Object> stocked = inv.line(s.variant(), null, s.warehouse().stock(), "1");
        stocked.put("unitCostBase", "1");
        inv.postMovement(s, inv.movement(s, "OPENING", s.warehouse().id(), "lines", List.of(stocked)));
        patchJson("/products/" + s.product(), 2, json("baseUomId", inv.uom("DOZ")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].code").value("LOCKED"));
        // Other fields stay editable; a stale version is refused.
        patchJson("/products/" + s.product(), 2, json("name", "Widget v2")).andExpect(status().isOk());
        patchJson("/products/" + s.product(), 2, json("name", "Stale")).andExpect(status().isPreconditionFailed());
    }

    @Test
    void productListsFilterSearchSortAndPage() throws Exception {
        create("/products", product("ALPHA", null, null)).andExpect(status().isCreated());
        create("/products", product("BRAVO", null, null)).andExpect(status().isCreated());
        create(
                        "/products",
                        json(
                                "code",
                                "CONSULT",
                                "name",
                                "Consulting hours",
                                "categoryId",
                                s.category(),
                                "productType",
                                "SERVICE",
                                "baseUomId",
                                inv.uom("H")))
                .andExpect(status().isCreated());

        String first = body(get(s.path("/products")).param("limit", "2").param("includeTotal", "true"));
        mvc.perform(get(s.path("/products"))
                        .cookie(s.session())
                        .param("limit", "2")
                        .param("includeTotal", "true"))
                .andExpect(jsonPath("$.data[*].code").value(contains("ALPHA", "BRAVO")))
                .andExpect(jsonPath("$.meta.totalCount").value(4));
        mvc.perform(get(s.path("/products"))
                        .cookie(s.session())
                        .param("limit", "2")
                        .param("cursor", JsonPath.<String>read(first, "$.page.nextCursor")))
                .andExpect(jsonPath("$.data[*].code").value(contains("CONSULT", "WIDGET")))
                .andExpect(jsonPath("$.page.hasMore").value(false));
        mvc.perform(get(s.path("/products")).cookie(s.session()).param("filter[productType]", "SERVICE"))
                .andExpect(jsonPath("$.data[*].code").value(contains("CONSULT")));
        mvc.perform(get(s.path("/products")).cookie(s.session()).param("q", "consult"))
                .andExpect(jsonPath("$.data[*].code").value(contains("CONSULT")));
        mvc.perform(get(s.path("/products"))
                        .cookie(s.session())
                        .param("filter[id][in]", s.product().toString()))
                .andExpect(jsonPath("$.data[*].code").value(contains("WIDGET")));
        mvc.perform(get(s.path("/products")).cookie(s.session()).param("sort", "-code"))
                .andExpect(jsonPath("$.data[0].code").value("WIDGET"));
        mvc.perform(get(s.path("/products")).cookie(s.session()).param("sort", "description"))
                .andExpect(status().isBadRequest());
        mvc.perform(get(s.path("/products")).cookie(s.session()).param("filter[productType]", "FOOD"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unitsAreGlobalReferenceData() throws Exception {
        mvc.perform(get("/api/v1/reference/uoms").cookie(s.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].code").value(hasItem("KG")));
        mvc.perform(get("/api/v1/reference/uom-categories").cookie(s.session()))
                .andExpect(jsonPath("$.data[*].code").value(hasItem("WEIGHT")));
        // Product conversions stay within the product's company and refuse the base unit itself.
        create("/products/" + s.product() + "/uom-conversions", json("uomId", inv.uom("EA"), "factorToBase", "1"))
                .andExpect(status().isUnprocessableContent());
        create("/products/" + s.product() + "/uom-conversions", json("uomId", inv.uom("KG"), "factorToBase", "0"))
                .andExpect(status().isUnprocessableContent());
    }

    // ------------------------------------------------------------------------------------------

    private String product(String code, String sku, String barcode) {
        return OrgFixtures.json(
                "code",
                code,
                "name",
                "Product " + code,
                "categoryId",
                s.category(),
                "productType",
                "STOCKABLE",
                "baseUomId",
                inv.uom("EA"),
                "sku",
                sku,
                "barcode",
                barcode);
    }

    private static String variant(String sku, Map<UUID, UUID> attributes) throws Exception {
        return OrgFixtures.JSON_MAPPER.writeValueAsString(Map.of("sku", sku, "attributes", attributes));
    }

    private ResultActions create(String path, String body) throws Exception {
        return mvc.perform(unsafe(post(s.path(path)))
                .cookie(s.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions patchJson(String path, int version, String body) throws Exception {
        return mvc.perform(unsafe(patch(s.path(path)))
                .cookie(s.session())
                .header("If-Match", etag(version))
                .contentType(OrgFixtures.MERGE_PATCH)
                .content(body));
    }

    private String body(ResultActions actions) throws Exception {
        var response = actions.andReturn().getResponse();
        if (response.getStatus() >= 300) {
            throw new AssertionError(response.getStatus() + " " + response.getContentAsString());
        }
        return response.getContentAsString();
    }

    private String body(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
            throws Exception {
        return body(mvc.perform(request.cookie(s.session())));
    }

    private static UUID valueId(String attribute, String code) {
        List<String> ids = JsonPath.read(attribute, "$.values[?(@.code == '" + code + "')].id");
        return UUID.fromString(ids.getFirst());
    }

    private static UUID id(ResultActions actions) throws Exception {
        String body = actions.andReturn().getResponse().getContentAsString();
        if (actions.andReturn().getResponse().getStatus() != 201) {
            throw new AssertionError(body);
        }
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }
}
