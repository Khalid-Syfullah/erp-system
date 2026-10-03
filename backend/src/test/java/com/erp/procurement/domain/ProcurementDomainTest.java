package com.erp.procurement.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** State machines and the three-way match of the Procurement module. */
class ProcurementDomainTest {

    @Test
    void requisitionLifecycle() {
        assertThat(RequisitionStatus.DRAFT.apply(RequisitionStatus.Action.SUBMIT))
                .isEqualTo(RequisitionStatus.SUBMITTED);
        assertThat(RequisitionStatus.SUBMITTED.apply(RequisitionStatus.Action.APPROVE))
                .isEqualTo(RequisitionStatus.APPROVED);
        assertThat(RequisitionStatus.SUBMITTED.apply(RequisitionStatus.Action.REJECT))
                .isEqualTo(RequisitionStatus.REJECTED);
        assertThat(RequisitionStatus.APPROVED.apply(RequisitionStatus.Action.CANCEL))
                .isEqualTo(RequisitionStatus.CANCELLED);
        assertThat(RequisitionStatus.APPROVED.ordered(true, false)).isEqualTo(RequisitionStatus.PARTIALLY_ORDERED);
        assertThat(RequisitionStatus.PARTIALLY_ORDERED.ordered(true, true)).isEqualTo(RequisitionStatus.ORDERED);
        assertThat(RequisitionStatus.ORDERED.ordered(false, false)).isEqualTo(RequisitionStatus.APPROVED);
        assertThat(RequisitionStatus.PARTIALLY_ORDERED.allows(RequisitionStatus.Action.CANCEL))
                .isFalse();
        assertThatThrownBy(() -> RequisitionStatus.REJECTED.apply(RequisitionStatus.Action.CANCEL))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> RequisitionStatus.DRAFT.ordered(true, true)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> RequisitionStatus.APPROVED.apply(RequisitionStatus.Action.ORDER))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void purchaseOrderLifecycle() {
        assertThat(PurchaseOrderStatus.DRAFT.apply(PurchaseOrderStatus.Action.SUBMIT))
                .isEqualTo(PurchaseOrderStatus.PENDING_APPROVAL);
        assertThat(PurchaseOrderStatus.PENDING_APPROVAL.apply(PurchaseOrderStatus.Action.REJECT))
                .isEqualTo(PurchaseOrderStatus.DRAFT);
        assertThat(PurchaseOrderStatus.PENDING_APPROVAL.apply(PurchaseOrderStatus.Action.APPROVE))
                .isEqualTo(PurchaseOrderStatus.APPROVED);
        assertThat(PurchaseOrderStatus.APPROVED.received(true, false))
                .isEqualTo(PurchaseOrderStatus.PARTIALLY_RECEIVED);
        assertThat(PurchaseOrderStatus.PARTIALLY_RECEIVED.received(true, true)).isEqualTo(PurchaseOrderStatus.RECEIVED);
        assertThat(PurchaseOrderStatus.RECEIVED.received(true, false))
                .isEqualTo(PurchaseOrderStatus.PARTIALLY_RECEIVED);
        assertThat(PurchaseOrderStatus.RECEIVED.apply(PurchaseOrderStatus.Action.CLOSE))
                .isEqualTo(PurchaseOrderStatus.CLOSED);
        assertThat(PurchaseOrderStatus.CLOSED.allows(PurchaseOrderStatus.Action.BILL))
                .isTrue();
        assertThat(PurchaseOrderStatus.CLOSED.allows(PurchaseOrderStatus.Action.RECEIVE))
                .isFalse();
        assertThat(PurchaseOrderStatus.RECEIVED.allows(PurchaseOrderStatus.Action.CANCEL))
                .isFalse();
        assertThat(PurchaseOrderStatus.APPROVED.allows(PurchaseOrderStatus.Action.EDIT))
                .isFalse();
        assertThatThrownBy(() -> PurchaseOrderStatus.CLOSED.received(true, true))
                .isInstanceOf(IllegalStateException.class);
        assertThat(PurchaseOrderStatus.Billing.of(false, false)).isEqualTo(PurchaseOrderStatus.Billing.NOT_BILLED);
        assertThat(PurchaseOrderStatus.Billing.of(true, false)).isEqualTo(PurchaseOrderStatus.Billing.PARTIALLY_BILLED);
        assertThat(PurchaseOrderStatus.Billing.of(true, true)).isEqualTo(PurchaseOrderStatus.Billing.BILLED);
    }

    @ParameterizedTest
    @EnumSource(DocumentStatus.Action.class)
    void documentsChangeOnlyAsDrafts(DocumentStatus.Action action) {
        assertThat(DocumentStatus.DRAFT.allows(action)).isTrue();
        assertThat(DocumentStatus.POSTED.allows(action)).isFalse();
        assertThat(DocumentStatus.CANCELLED.allows(action)).isFalse();
        assertThatThrownBy(() -> DocumentStatus.POSTED.apply(action)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void documentTransitions() {
        assertThat(DocumentStatus.DRAFT.apply(DocumentStatus.Action.POST)).isEqualTo(DocumentStatus.POSTED);
        assertThat(DocumentStatus.DRAFT.apply(DocumentStatus.Action.CANCEL)).isEqualTo(DocumentStatus.CANCELLED);
        assertThat(DocumentStatus.DRAFT.apply(DocumentStatus.Action.EDIT)).isEqualTo(DocumentStatus.DRAFT);
    }

    @Test
    void billLineKinds() {
        assertThat(BillLineKind.ofProductType("SERVICE")).isEqualTo(BillLineKind.SERVICE);
        assertThat(BillLineKind.ofProductType("CONSUMABLE")).isEqualTo(BillLineKind.NON_STOCK_GOODS);
        assertThatThrownBy(() -> BillLineKind.ofProductType("STOCKABLE")).isInstanceOf(IllegalArgumentException.class);
        assertThat(BillLineKind.RECEIVED_STOCK.eventType()).isEqualTo("STOCK_RECEIVED");
        assertThat(BillLineKind.NON_STOCK_GOODS.eventType()).isEqualTo("EXPENSE");
        assertThat(BillLineKind.SERVICE.eventType()).isEqualTo("SERVICE");
    }

    @Test
    void threeWayMatchChecksQuantityAndPriceWithinTolerances() {
        var exact = new ThreeWayMatch.Line(
                1, new BigDecimal("10"), new BigDecimal("10"), new BigDecimal("4"), new BigDecimal("4"));
        var over = new ThreeWayMatch.Line(
                2, new BigDecimal("11"), new BigDecimal("10"), new BigDecimal("4"), new BigDecimal("4"));
        var dearer = new ThreeWayMatch.Line(
                3, new BigDecimal("1"), new BigDecimal("10"), new BigDecimal("4"), new BigDecimal("4.2"));
        var direct = new ThreeWayMatch.Line(4, new BigDecimal("5"), new BigDecimal("5"), null, new BigDecimal("99"));

        assertThat(ThreeWayMatch.check(List.of(exact, direct), BigDecimal.ZERO, BigDecimal.ZERO))
                .isEmpty();
        assertThat(ThreeWayMatch.check(List.of(over, dearer), BigDecimal.ZERO, BigDecimal.ZERO))
                .extracting(ThreeWayMatch.Issue::problem)
                .containsExactly(ThreeWayMatch.Problem.QUANTITY, ThreeWayMatch.Problem.PRICE);
        assertThat(ThreeWayMatch.check(List.of(over, dearer), BigDecimal.TEN, new BigDecimal("5")))
                .isEmpty();
        assertThat(ThreeWayMatch.check(List.of(dearer), BigDecimal.ZERO, new BigDecimal("4.9")))
                .hasSize(1);
        assertThat(ThreeWayMatch.withTolerance(new BigDecimal("10"), new BigDecimal("2.5")))
                .isEqualByComparingTo("10.25");
    }
}
