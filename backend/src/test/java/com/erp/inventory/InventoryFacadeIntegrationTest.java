package com.erp.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erp.inventory.api.InventoryFacade;
import com.erp.inventory.api.InventoryFacade.Availability;
import com.erp.inventory.api.InventoryFacade.InLine;
import com.erp.inventory.api.InventoryFacade.OutLine;
import com.erp.inventory.api.InventoryFacade.PostedMovement;
import com.erp.inventory.api.InventoryFacade.ReservationRequest;
import com.erp.inventory.api.InventoryFacade.ReservationResult;
import com.erp.inventory.api.InventoryFacade.SourceRef;
import com.erp.inventory.api.InventoryFacade.StockInRequest;
import com.erp.inventory.api.InventoryFacade.StockOutRequest;
import com.erp.inventory.application.InventoryErrorCode;
import com.erp.inventory.events.StockMovementPosted;
import com.erp.platform.web.ApiException;
import com.erp.platform.web.ErrorCode;
import com.erp.platform.web.PlatformErrorCode;
import com.erp.support.IntegrationTest;
import com.erp.support.InventoryFixtures;
import com.erp.support.InventoryFixtures.Setup;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** The facade Procurement and Sales use: receipts, deliveries, returns and reservations. */
@RecordApplicationEvents
class InventoryFacadeIntegrationTest extends IntegrationTest {

    @Autowired
    InventoryFacade facade;

    @Autowired
    InventoryFixtures inv;

    @Autowired
    MockMvc mvc;

    @Autowired
    ApplicationEvents events;

    private Setup s;
    private UUID wh;
    private UUID ea;

    @BeforeEach
    void setUp() throws Exception {
        s = inv.setup();
        wh = s.warehouse().id();
        ea = inv.uom("EA");
    }

    @Test
    void receiptsAndIssuesFollowTheMovingAverageWithoutResidue() {
        receive("1", "1.00");
        receive("2", "2.00");
        // 3 units worth 5.00: one third rounds to 1.67, the rest takes exactly 3.33.
        PostedMovement first = issue(sourceRef("sales", "DELIVERY"), "1", null);
        assertThat(first.number()).startsWith("SM-");
        assertThat(first.lines().getFirst().valueBase()).isEqualByComparingTo("1.67");
        PostedMovement rest = issue(sourceRef("sales", "DELIVERY"), "2", null);
        assertThat(rest.lines().getFirst().valueBase()).isEqualByComparingTo("3.33");

        Availability empty = availability();
        assertThat(empty.onHand()).isEqualByComparingTo("0");
        assertThat(inv.inCompany(s, () -> facade.availability(s.variant(), wh)).available())
                .isEqualByComparingTo("0");
        // Nothing can be issued from an empty warehouse.
        assertError(() -> issue(sourceRef("sales", "DELIVERY"), "1", null), InventoryErrorCode.INSUFFICIENT_STOCK);
    }

    @Test
    void eachPostingPublishesOneSelfContainedEvent() {
        events.clear();
        receive("4", "2.50");
        SourceRef ret = sourceRef("procurement", "SUPPLIER_RETURN");
        PostedMovement out = inv.inCompany(
                s,
                () -> facade.returnToSupplier(new StockOutRequest(
                        ret,
                        s.today(),
                        wh,
                        null,
                        List.of(new OutLine(
                                s.variant(), new BigDecimal("1"), ea, null, null, new BigDecimal("3.00"), null)),
                        null)));

        List<StockMovementPosted> posted =
                events.stream(StockMovementPosted.class).toList();
        assertThat(posted).hasSize(2);
        StockMovementPosted receipt = posted.getFirst();
        assertThat(receipt.metadata().eventType()).isEqualTo(StockMovementPosted.TYPE);
        assertThat(receipt.metadata().companyId()).isEqualTo(s.company());
        assertThat(receipt.movementType()).isEqualTo("PURCHASE_RECEIPT");
        assertThat(receipt.lines()).singleElement().satisfies(line -> {
            assertThat(line.quantityBase()).isEqualByComparingTo("4");
            assertThat(line.valueBase()).isEqualByComparingTo("10.00");
            assertThat(line.branchId()).isEqualTo(s.branch());
            assertThat(line.categoryId()).isEqualTo(s.category());
            assertThat(line.locationId()).isEqualTo(s.warehouse().stock());
        });
        StockMovementPosted supplierReturn = posted.get(1);
        assertThat(supplierReturn.movementId()).isEqualTo(out.movementId());
        assertThat(supplierReturn.sourceRef()).isNotNull();
        assertThat(supplierReturn.sourceRef().id()).isEqualTo(ret.id());
        assertThat(supplierReturn.lines()).singleElement().satisfies(line -> {
            assertThat(line.quantityBase()).isEqualByComparingTo("-1");
            assertThat(line.valueBase()).isEqualByComparingTo("-2.50");
            assertThat(line.referenceValueBase()).isEqualByComparingTo("-3.00");
        });
    }

    @Test
    void customerReturnsComeBackAtTheGivenCost() {
        receive("2", "4.00");
        issue(sourceRef("sales", "DELIVERY"), "2", null);
        PostedMovement back = inv.inCompany(
                s,
                () -> facade.returnFromCustomer(new StockInRequest(
                        sourceRef("sales", "RETURN"),
                        s.today(),
                        wh,
                        null,
                        List.of(new InLine(s.variant(), new BigDecimal("1"), ea, null, new BigDecimal("4.00"), null)),
                        null)));
        assertThat(back.lines().getFirst().valueBase()).isEqualByComparingTo("4.00");
        assertThat(availability().onHand()).isEqualByComparingTo("1");
    }

    @Test
    void aSourceDocumentIsProcessedOnce() {
        SourceRef grn = sourceRef("procurement", "GOODS_RECEIPT");
        receive(grn, "1", "1");
        assertError(() -> receive(grn, "1", "1"), InventoryErrorCode.DUPLICATE_SOURCE_DOCUMENT);
        assertThat(availability().onHand()).isEqualByComparingTo("1");
    }

    @Test
    void reservationsHoldStockForTheirSourceLine() throws Exception {
        receive("10", "1");
        SourceRef orderA = sourceRef("sales", "ORDER");
        SourceRef orderB = sourceRef("sales", "ORDER");
        UUID lineA = UUID.randomUUID();
        ReservationResult a = reserve(orderA, lineA, "4", false);
        assertThat(a.reservedQuantityBase()).isEqualByComparingTo("4");
        assertThat(availability().available()).isEqualByComparingTo("6");

        // All-or-nothing fails; partial reserves what is free and reports the backorder.
        assertError(() -> reserve(orderB, UUID.randomUUID(), "7", false), InventoryErrorCode.INSUFFICIENT_STOCK);
        ReservationResult b = reserve(orderB, UUID.randomUUID(), "7", true);
        assertThat(b.reservedQuantityBase()).isEqualByComparingTo("6");
        assertThat(b.backorderQuantityBase()).isEqualByComparingTo("1");
        assertThat(availability().available()).isEqualByComparingTo("0");

        // Reserved stock cannot be taken by others: a delivery without (or with a foreign) reservation
        // finds nothing available, and an adjustment may not cut into reserved stock (API.md §6.1).
        assertError(() -> issue(sourceRef("sales", "DELIVERY"), "1", null), InventoryErrorCode.INSUFFICIENT_STOCK);
        adjustmentOut("1")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("RESERVED_STOCK_CONFLICT"));
        assertThat(availability().onHand()).isEqualByComparingTo("10");

        // Order A's delivery consumes its own reservation.
        issue(orderAsDelivery(orderA), "3", a.reservationId());
        Availability after = availability();
        assertThat(after.onHand()).isEqualByComparingTo("7");
        assertThat(after.reserved()).isEqualByComparingTo("7");

        // Releasing frees the stock again; a released reservation cannot be released twice.
        inv.inCompany(s, () -> {
            facade.release(a.reservationId(), null);
            return null;
        });
        assertThat(availability().available()).isEqualByComparingTo("1");
        assertError(
                () -> inv.inCompany(s, () -> {
                    facade.release(a.reservationId(), null);
                    return null;
                }),
                PlatformErrorCode.INVALID_STATE);
        inv.inCompany(s, () -> {
            facade.release(b.reservationId(), new BigDecimal("2"));
            return null;
        });
        assertThat(availability().reserved()).isEqualByComparingTo("4");
        assertError(() -> reserve(orderB, UUID.randomUUID(), "0", true), PlatformErrorCode.VALIDATION_FAILED);
    }

    @Test
    void reservationsOfTheSameLineAccumulate() {
        receive("10", "1");
        SourceRef order = sourceRef("sales", "ORDER");
        UUID line = UUID.randomUUID();
        ReservationResult first = reserve(order, line, "2", false);
        ReservationResult second = reserve(order, line, "3", false);
        assertThat(second.reservationId()).isEqualTo(first.reservationId());
        assertThat(availability().reserved()).isEqualByComparingTo("5");
    }

    @Test
    void unitsAndVariantInformation() throws Exception {
        assertThat(inv.inCompany(s, () -> facade.convertQuantity(s.variant(), new BigDecimal("2"), inv.uom("DOZ"))))
                .isEqualByComparingTo("24");
        assertError(
                () -> inv.inCompany(s, () -> facade.convertQuantity(s.variant(), BigDecimal.ONE, inv.uom("KG"))),
                InventoryErrorCode.UOM_NOT_CONVERTIBLE);
        var info = inv.inCompany(s, () -> facade.variantInfo(s.variant())).orElseThrow();
        assertThat(info.productId()).isEqualTo(s.product());
        assertThat(info.productType()).isEqualTo("STOCKABLE");
        assertThat(info.baseUomId()).isEqualTo(ea);
    }

    @Test
    void anotherCompanysItemsAndWarehousesAreInvisible() throws Exception {
        Setup other = inv.setup();
        assertThat(inv.inCompany(other, () -> facade.variantInfo(s.variant()))).isEmpty();
        assertError(
                () -> inv.inCompany(
                        other,
                        () -> facade.receive(new StockInRequest(
                                sourceRef("procurement", "GOODS_RECEIPT"),
                                s.today(),
                                wh,
                                null,
                                List.of(new InLine(s.variant(), BigDecimal.ONE, ea, null, BigDecimal.ONE, null)),
                                null))),
                PlatformErrorCode.VALIDATION_FAILED);
        // Their own warehouse with our variant: still rejected.
        assertError(
                () -> inv.inCompany(
                        other,
                        () -> facade.receive(new StockInRequest(
                                sourceRef("procurement", "GOODS_RECEIPT"),
                                s.today(),
                                other.warehouse().id(),
                                null,
                                List.of(new InLine(s.variant(), BigDecimal.ONE, ea, null, BigDecimal.ONE, null)),
                                null))),
                PlatformErrorCode.VALIDATION_FAILED);
        mvc.perform(get(s.path("/stock-levels")).cookie(s.session()))
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    // ------------------------------------------------------------------------------------------

    private static SourceRef sourceRef(String module, String type) {
        UUID id = UUID.randomUUID();
        return new SourceRef(module, type, id, type + "-" + id.toString().substring(0, 8));
    }

    private static SourceRef orderAsDelivery(SourceRef order) {
        return new SourceRef(order.module(), "DELIVERY", UUID.randomUUID(), null);
    }

    private PostedMovement receive(String qty, String cost) {
        return receive(sourceRef("procurement", "GOODS_RECEIPT"), qty, cost);
    }

    private PostedMovement receive(SourceRef source, String qty, String cost) {
        return inv.inCompany(
                s,
                () -> facade.receive(new StockInRequest(
                        source,
                        s.today(),
                        wh,
                        null,
                        List.of(new InLine(s.variant(), new BigDecimal(qty), ea, null, new BigDecimal(cost), null)),
                        null)));
    }

    private PostedMovement issue(SourceRef source, String qty, UUID reservation) {
        return inv.inCompany(
                s,
                () -> facade.issue(new StockOutRequest(
                        source,
                        s.today(),
                        wh,
                        null,
                        List.of(new OutLine(s.variant(), new BigDecimal(qty), ea, null, reservation, null, null)),
                        null)));
    }

    private ReservationResult reserve(SourceRef source, UUID line, String qty, boolean partial) {
        return inv.inCompany(
                s,
                () -> facade.reserve(
                        new ReservationRequest(source, line, s.variant(), wh, new BigDecimal(qty), partial)));
    }

    private Availability availability() {
        return inv.inCompany(s, () -> facade.availability(s.variant(), wh));
    }

    /** A manual adjustment taking stock out of the warehouse, created and posted via the API. */
    private ResultActions adjustmentOut(String qty) throws Exception {
        String created = inv.createMovement(
                        s,
                        inv.movement(
                                s,
                                "ADJUSTMENT",
                                wh,
                                "reasonCodeId",
                                s.adjustmentReason(),
                                "lines",
                                List.of(inv.line(s.variant(), s.warehouse().stock(), null, qty))))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return inv.post(s, UUID.fromString(JsonPath.read(created, "$.id")), 0);
    }

    private static void assertError(ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(code));
    }
}
