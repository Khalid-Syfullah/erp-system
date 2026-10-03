package com.erp.inventory;

import static com.erp.support.AuthTestSupport.unsafe;
import static com.erp.support.OrgFixtures.etag;
import static com.erp.support.OrgFixtures.map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erp.support.IntegrationTest;
import com.erp.support.InventoryFixtures;
import com.erp.support.InventoryFixtures.Setup;
import com.erp.support.InventoryFixtures.Warehouse;
import com.erp.support.OrgFixtures;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Stock movements through the API: posting effects, transfers, adjustments, reversals and validation. */
class StockMovementIntegrationTest extends IntegrationTest {

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
    void openingStockWritesTheLedgerBalancesAndValuation() throws Exception {
        UUID movement = inv.opening(s, s.variant(), wh.stock(), "10", "2.50");

        mvc.perform(get(s.path("/stock-movements/" + movement)).cookie(s.session()))
                .andExpect(jsonPath("$.status").value("POSTED"))
                .andExpect(
                        jsonPath("$.number").value(startsWith("SM-" + s.today().getYear() + "-")))
                .andExpect(jsonPath("$.lines[0].quantityBase").value("10.000000"))
                .andExpect(jsonPath("$.lines[0].unitCostBase").value("2.500000"));
        mvc.perform(get(s.path("/stock-levels"))
                        .cookie(s.session())
                        .param("filter[variantId]", s.variant().toString()))
                .andExpect(jsonPath("$.data[0].onHand").value("10.000000"))
                .andExpect(jsonPath("$.data[0].available").value("10.000000"));
        mvc.perform(get(s.path("/inventory-transactions"))
                        .cookie(s.session())
                        .param("filter[movementId]", movement.toString()))
                .andExpect(jsonPath("$.data[0].quantityBase").value("10.000000"))
                .andExpect(jsonPath("$.data[0].valueBase").value("25.0000"))
                .andExpect(jsonPath("$.data[0].createdBy").value(s.user().id().toString()));
        mvc.perform(get(s.path("/stock-valuation")).cookie(s.session()))
                .andExpect(jsonPath("$.totalValueBase").value("25.0000"))
                .andExpect(jsonPath("$.data[0].averageCostBase").value("2.500000"));
    }

    @Test
    void transfersMoveStockAtomicallyWithoutChangingValue() throws Exception {
        Warehouse other = inv.warehouse(s, "WH2");
        inv.opening(s, s.variant(), wh.stock(), "10", "3");

        inv.postMovement(
                s,
                inv.movement(
                        s,
                        "TRANSFER",
                        wh.id(),
                        "destWarehouseId",
                        other.id(),
                        "lines",
                        List.of(inv.line(s.variant(), wh.stock(), other.stock(), "4"))));

        assertThat(inv.balance(s, s.variant(), wh.stock())).isEqualByComparingTo("6");
        assertThat(inv.balance(s, s.variant(), other.stock())).isEqualByComparingTo("4");
        mvc.perform(get(s.path("/stock-valuation")).cookie(s.session()))
                .andExpect(jsonPath("$.totalValueBase").value("30.0000"));
        mvc.perform(get(s.path("/stock-levels"))
                        .cookie(s.session())
                        .param("filter[warehouseId]", other.id().toString()))
                .andExpect(jsonPath("$.data[0].onHand").value("4.000000"));
    }

    @Test
    void aFailingLineRollsBackTheWholeMovement() throws Exception {
        Warehouse other = inv.warehouse(s, "WH2");
        UUID second = inv.variant(s, "GADGET", "EA");
        inv.opening(s, s.variant(), wh.stock(), "10", "1");
        inv.opening(s, second, wh.stock(), "2", "1");

        // Line 1 is fine, line 2 asks for more than there is: nothing may move, no number is used.
        UUID draft = id(inv.createMovement(
                s,
                inv.movement(
                        s,
                        "TRANSFER",
                        wh.id(),
                        "destWarehouseId",
                        other.id(),
                        "lines",
                        List.of(
                                inv.line(s.variant(), wh.stock(), other.stock(), "5"),
                                inv.line(second, wh.stock(), other.stock(), "3")))));
        inv.post(s, draft, 0)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"))
                .andExpect(jsonPath("$.errors[0].pointer").value("/lines/1/quantity"))
                .andExpect(jsonPath("$.errors[0].meta.requested").value("3"))
                .andExpect(jsonPath("$.errors[0].meta.available").value("2"));

        assertThat(inv.balance(s, s.variant(), wh.stock())).isEqualByComparingTo("10");
        assertThat(inv.balance(s, s.variant(), other.stock())).isEqualByComparingTo("0");
        mvc.perform(get(s.path("/inventory-transactions"))
                        .cookie(s.session())
                        .param("filter[movementId]", draft.toString()))
                .andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(get(s.path("/stock-movements/" + draft)).cookie(s.session()))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.number").doesNotExist());
        // The next posted movement gets the next number: the failed attempt left no gap.
        UUID next = inv.opening(s, s.variant(), wh.stock(), "1", "1");
        String number = JsonPath.read(body(get(s.path("/stock-movements/" + next))), "$.number");
        assertThat(number).endsWith("000003");
    }

    @Test
    void twoStepTransfersPassThroughTransit() throws Exception {
        Warehouse other = inv.warehouse(s, "WH2");
        inv.opening(s, s.variant(), wh.stock(), "10", "1");

        UUID ship = inv.postMovement(
                s,
                inv.movement(
                        s,
                        "TRANSFER_SHIP",
                        wh.id(),
                        "destWarehouseId",
                        other.id(),
                        "lines",
                        List.of(inv.line(s.variant(), wh.stock(), other.transit(), "6"))));
        assertThat(inv.balance(s, s.variant(), other.transit())).isEqualByComparingTo("6");
        // Transit stock is not available in either warehouse.
        mvc.perform(get(s.path("/stock-levels"))
                        .cookie(s.session())
                        .param("filter[warehouseId]", other.id().toString()))
                .andExpect(jsonPath("$.data.length()").value(0));

        String receipt = body(unsafe(post(s.path("/stock-movements/" + ship + "/receive")))
                .header("If-Match", etag(1))
                .header("Idempotency-Key", "receive-" + ship));
        assertThat((String) JsonPath.read(receipt, "$.movementType")).isEqualTo("TRANSFER_RECEIVE");
        assertThat((String) JsonPath.read(receipt, "$.relatedMovementId")).isEqualTo(ship.toString());
        assertThat(inv.balance(s, s.variant(), other.transit())).isEqualByComparingTo("0");
        assertThat(inv.balance(s, s.variant(), other.stock())).isEqualByComparingTo("6");
        // A shipment is received once.
        mvc.perform(unsafe(post(s.path("/stock-movements/" + ship + "/receive")))
                        .cookie(s.session())
                        .header("If-Match", etag(1))
                        .header("Idempotency-Key", "receive-again-" + ship))
                .andExpect(status().isConflict());
    }

    @Test
    void adjustmentsNeedAReasonAndValueAtTheAverage() throws Exception {
        inv.opening(s, s.variant(), wh.stock(), "10", "2");
        Map<String, Object> noReason =
                inv.movement(s, "ADJUSTMENT", wh.id(), "lines", List.of(inv.line(s.variant(), wh.stock(), null, "1")));
        inv.createMovement(s, noReason)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].pointer").value("/reasonCodeId"));
        // A SCRAP reason does not fit an ADJUSTMENT.
        inv.createMovement(
                        s,
                        inv.movement(
                                s,
                                "ADJUSTMENT",
                                wh.id(),
                                "reasonCodeId",
                                s.scrapReason(),
                                "lines",
                                List.of(inv.line(s.variant(), wh.stock(), null, "1"))))
                .andExpect(status().isUnprocessableContent());

        inv.postMovement(
                s,
                inv.movement(
                        s,
                        "ADJUSTMENT",
                        wh.id(),
                        "reasonCodeId",
                        s.adjustmentReason(),
                        "lines",
                        List.of(inv.line(s.variant(), wh.stock(), null, "3"))));
        inv.postMovement(
                s,
                inv.movement(
                        s,
                        "ADJUSTMENT",
                        wh.id(),
                        "reasonCodeId",
                        s.adjustmentReason(),
                        "lines",
                        List.of(inv.line(s.variant(), null, wh.stock(), "1"))));
        inv.postMovement(
                s,
                inv.movement(
                        s,
                        "SCRAP",
                        wh.id(),
                        "reasonCodeId",
                        s.scrapReason(),
                        "lines",
                        List.of(inv.line(s.variant(), wh.stock(), null, "2"))));

        assertThat(inv.balance(s, s.variant(), wh.stock())).isEqualByComparingTo("6");
        mvc.perform(get(s.path("/stock-valuation")).cookie(s.session()))
                .andExpect(jsonPath("$.totalValueBase").value("12.0000"));
    }

    @Test
    void postedMovementsAreImmutableAndCorrectedByReversal() throws Exception {
        UUID opening = inv.opening(s, s.variant(), wh.stock(), "10", "2");

        mvc.perform(unsafe(patch(s.path("/stock-movements/" + opening)))
                        .cookie(s.session())
                        .header("If-Match", etag(1))
                        .contentType(OrgFixtures.MERGE_PATCH)
                        .content(OrgFixtures.json("notes", "changed")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE"));
        mvc.perform(unsafe(delete(s.path("/stock-movements/" + opening)))
                        .cookie(s.session())
                        .header("If-Match", etag(1)))
                .andExpect(status().isConflict());

        String reversal = body(unsafe(post(s.path("/stock-movements/" + opening + "/reverse")))
                .header("If-Match", etag(1))
                .header("Idempotency-Key", "reverse-" + opening));
        assertThat((String) JsonPath.read(reversal, "$.movementType")).isEqualTo("REVERSAL");
        assertThat((String) JsonPath.read(reversal, "$.reversalOfId")).isEqualTo(opening.toString());
        assertThat(inv.balance(s, s.variant(), wh.stock())).isEqualByComparingTo("0");
        mvc.perform(get(s.path("/stock-valuation")).cookie(s.session()))
                .andExpect(jsonPath("$.totalValueBase").value("0"));
        // The ledger keeps both rows.
        mvc.perform(get(s.path("/inventory-transactions"))
                        .cookie(s.session())
                        .param("filter[variantId]", s.variant().toString()))
                .andExpect(jsonPath("$.data[*].quantityBase").value(containsInAnyOrder("10.000000", "-10.000000")));
        // A movement is reversed once.
        mvc.perform(unsafe(post(s.path("/stock-movements/" + opening + "/reverse")))
                        .cookie(s.session())
                        .header("If-Match", etag(1))
                        .header("Idempotency-Key", "reverse-again-" + opening))
                .andExpect(status().isConflict());
        // A reversal is final: corrections of it are new movements.
        String reversalId = JsonPath.read(reversal, "$.id");
        mvc.perform(unsafe(post(s.path("/stock-movements/" + reversalId + "/reverse")))
                        .cookie(s.session())
                        .header("If-Match", etag(1))
                        .header("Idempotency-Key", "reverse-reversal-" + reversalId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE"));
    }

    @Test
    void reversalsFailWhenTheStockHasBeenConsumed() throws Exception {
        UUID opening = inv.opening(s, s.variant(), wh.stock(), "10", "2");
        inv.postMovement(
                s,
                inv.movement(
                        s,
                        "SCRAP",
                        wh.id(),
                        "reasonCodeId",
                        s.scrapReason(),
                        "lines",
                        List.of(inv.line(s.variant(), wh.stock(), null, "4"))));

        mvc.perform(unsafe(post(s.path("/stock-movements/" + opening + "/reverse")))
                        .cookie(s.session())
                        .header("If-Match", etag(1))
                        .header("Idempotency-Key", "reverse-" + opening))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"));
        assertThat(inv.balance(s, s.variant(), wh.stock())).isEqualByComparingTo("6");
    }

    @Test
    void draftsAreEditableDeletableAndCancellable() throws Exception {
        inv.opening(s, s.variant(), wh.stock(), "10", "1");
        Warehouse other = inv.warehouse(s, "WH2");
        UUID draft = id(inv.createMovement(
                s,
                inv.movement(
                        s,
                        "TRANSFER",
                        wh.id(),
                        "destWarehouseId",
                        other.id(),
                        "lines",
                        List.of(inv.line(s.variant(), wh.stock(), other.stock(), "1")))));

        mvc.perform(unsafe(patch(s.path("/stock-movements/" + draft)))
                        .cookie(s.session())
                        .header("If-Match", etag(0))
                        .contentType(OrgFixtures.MERGE_PATCH)
                        .content(OrgFixtures.JSON_MAPPER.writeValueAsString(map(
                                "notes",
                                "two now",
                                "lines",
                                List.of(inv.line(s.variant(), wh.stock(), other.stock(), "2"))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].quantity").value("2.000000"))
                .andExpect(jsonPath("$.notes").value("two now"));
        mvc.perform(unsafe(post(s.path("/stock-movements/" + draft + "/cancel")))
                        .cookie(s.session())
                        .header("If-Match", etag(1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        inv.post(s, draft, 2).andExpect(status().isConflict());

        UUID another = id(inv.createMovement(
                s,
                inv.movement(
                        s,
                        "TRANSFER",
                        wh.id(),
                        "destWarehouseId",
                        other.id(),
                        "lines",
                        List.of(inv.line(s.variant(), wh.stock(), other.stock(), "1")))));
        mvc.perform(unsafe(delete(s.path("/stock-movements/" + another)))
                        .cookie(s.session())
                        .header("If-Match", etag(0)))
                .andExpect(status().isNoContent());
        mvc.perform(get(s.path("/stock-movements/" + another)).cookie(s.session()))
                .andExpect(status().isNotFound());
    }

    @Test
    void invalidQuantitiesUnitsAndLocationsAreRejected() throws Exception {
        UUID service = UUID.fromString(JsonPath.read(
                body(unsafe(post(s.path("/products")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OrgFixtures.json(
                                "code",
                                "SERVICE",
                                "name",
                                "Service",
                                "categoryId",
                                s.category(),
                                "productType",
                                "SERVICE",
                                "baseUomId",
                                inv.uom("H")))),
                "$.variants[0].id"));
        Warehouse other = inv.warehouse(s, "WH2");
        Map<String, Object> zero = inv.line(s.variant(), null, wh.stock(), "0");
        zero.put("unitCostBase", "1");
        Map<String, Object> fraction = inv.line(s.variant(), null, wh.stock(), "1.5");
        fraction.put("unitCostBase", "1");
        Map<String, Object> kilos = inv.line(s.variant(), null, wh.stock(), "1");
        kilos.put("uomId", inv.uom("KG"));
        kilos.put("unitCostBase", "1");
        Map<String, Object> serviceLine = inv.line(service, null, wh.stock(), "1");
        serviceLine.put("uomId", inv.uom("H"));
        serviceLine.put("unitCostBase", "1");
        Map<String, Object> wrongWarehouse = inv.line(s.variant(), null, other.stock(), "1");
        wrongWarehouse.put("unitCostBase", "1");

        opening(List.of(zero)).andExpect(status().isUnprocessableContent());
        opening(List.of(fraction))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].code").value("TOO_PRECISE"));
        opening(List.of(kilos))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("UOM_NOT_CONVERTIBLE"));
        opening(List.of(serviceLine))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].code").value("NOT_STOCKABLE"));
        opening(List.of(wrongWarehouse))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].code").value("UNKNOWN_LOCATION"));
        opening(List.of(inv.line(s.variant(), null, wh.stock(), "1")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].pointer").value("/lines/0/unitCostBase"));
        // Decimals must be strings; numbers are rejected (API.md §11).
        mvc.perform(unsafe(post(s.path("/stock-movements")))
                        .cookie(s.session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"movementType\":\"OPENING\",\"movementDate\":\"" + s.today()
                                + "\",\"warehouseId\":\"" + wh.id()
                                + "\",\"lines\":[{\"variantId\":\"" + s.variant() + "\",\"toLocationId\":\""
                                + wh.stock()
                                + "\",\"quantity\":1,\"uomId\":\"" + inv.uom("EA") + "\",\"unitCostBase\":\"1\"}]}"))
                .andExpect(status().isBadRequest());
        // Receipts are created by Procurement only.
        mvc.perform(unsafe(post(s.path("/stock-movements")))
                        .cookie(s.session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OrgFixtures.JSON_MAPPER.writeValueAsString(
                                inv.movement(s, "PURCHASE_RECEIPT", wh.id(), "lines", List.of(zero)))))
                .andExpect(status().isUnprocessableContent());
        // Future-dated movements cannot be posted.
        Map<String, Object> line = inv.line(s.variant(), null, wh.stock(), "1");
        line.put("unitCostBase", "1");
        Map<String, Object> future = inv.movement(s, "OPENING", wh.id(), "lines", List.of(line));
        future.put("movementDate", s.today().plusDays(2).toString());
        UUID draft = id(inv.createMovement(s, future));
        inv.post(s, draft, 0).andExpect(status().isUnprocessableContent());
    }

    @Test
    void unitsAreConvertedToTheBaseUnit() throws Exception {
        Map<String, Object> dozens = inv.line(s.variant(), null, wh.stock(), "2");
        dozens.put("uomId", inv.uom("DOZ"));
        dozens.put("unitCostBase", "0.50");
        inv.postMovement(s, inv.movement(s, "OPENING", wh.id(), "lines", List.of(dozens)));
        assertThat(inv.balance(s, s.variant(), wh.stock())).isEqualByComparingTo("24");

        // A product-specific conversion across categories: 1 KG = 40 EA for this product.
        mvc.perform(unsafe(post(s.path("/products/" + s.product() + "/uom-conversions")))
                        .cookie(s.session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OrgFixtures.json("uomId", inv.uom("KG"), "factorToBase", "40")))
                .andExpect(status().isCreated());
        Map<String, Object> kilos = inv.line(s.variant(), null, wh.stock(), "0.5");
        kilos.put("uomId", inv.uom("KG"));
        kilos.put("unitCostBase", "0.50");
        inv.postMovement(s, inv.movement(s, "OPENING", wh.id(), "lines", List.of(kilos)));
        assertThat(inv.balance(s, s.variant(), wh.stock())).isEqualByComparingTo("44");

        // Inexact same-category factors round to the base unit's precision (1 LB → 0.454 KG).
        UUID flour = inv.variant(s, "FLOUR", "KG");
        Map<String, Object> pounds = inv.line(flour, null, wh.stock(), "1");
        pounds.put("uomId", inv.uom("LB"));
        pounds.put("unitCostBase", "1");
        inv.postMovement(s, inv.movement(s, "OPENING", wh.id(), "lines", List.of(pounds)));
        assertThat(inv.balance(s, flour, wh.stock())).isEqualByComparingTo("0.454");
    }

    private ResultActions opening(List<Map<String, Object>> lines) throws Exception {
        return inv.createMovement(s, inv.movement(s, "OPENING", wh.id(), "lines", lines));
    }

    private String body(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
            throws Exception {
        var result = mvc.perform(request.cookie(s.session())).andReturn();
        if (result.getResponse().getStatus() >= 300) {
            throw new AssertionError(result.getResponse().getStatus() + " "
                    + result.getResponse().getContentAsString());
        }
        return result.getResponse().getContentAsString();
    }

    private static UUID id(ResultActions actions) throws Exception {
        String body = actions.andReturn().getResponse().getContentAsString();
        if (actions.andReturn().getResponse().getStatus() != 201) {
            throw new AssertionError(body);
        }
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    static BigDecimal dec(String value) {
        return new BigDecimal(value);
    }
}
