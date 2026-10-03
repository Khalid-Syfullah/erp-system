package com.erp.sales.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** The Sales state machines (PRODUCT_SPEC.md §9.2), the credit check (SAL-2) and price tiers (SAL-1). */
class SalesDomainTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 7);

    @Test
    void quotationLifecycle() {
        assertThat(QuotationStatus.DRAFT.apply(QuotationStatus.Action.SEND)).isEqualTo(QuotationStatus.SENT);
        assertThat(QuotationStatus.SENT.apply(QuotationStatus.Action.ACCEPT)).isEqualTo(QuotationStatus.ACCEPTED);
        assertThat(QuotationStatus.SENT.apply(QuotationStatus.Action.REJECT)).isEqualTo(QuotationStatus.REJECTED);
        assertThat(QuotationStatus.SENT.apply(QuotationStatus.Action.EXPIRE)).isEqualTo(QuotationStatus.EXPIRED);
        assertThat(QuotationStatus.DRAFT.apply(QuotationStatus.Action.CANCEL)).isEqualTo(QuotationStatus.CANCELLED);
        assertThat(QuotationStatus.SENT.apply(QuotationStatus.Action.CANCEL)).isEqualTo(QuotationStatus.CANCELLED);
        assertThat(QuotationStatus.DRAFT.apply(QuotationStatus.Action.EDIT)).isEqualTo(QuotationStatus.DRAFT);
        assertThat(QuotationStatus.DRAFT.allows(QuotationStatus.Action.ACCEPT)).isFalse();
        assertThat(QuotationStatus.SENT.allows(QuotationStatus.Action.EDIT)).isFalse();
        assertThatThrownBy(() -> QuotationStatus.ACCEPTED.apply(QuotationStatus.Action.CANCEL))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> QuotationStatus.EXPIRED.apply(QuotationStatus.Action.ACCEPT))
                .isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @EnumSource(
            value = QuotationStatus.class,
            names = {"ACCEPTED", "REJECTED", "EXPIRED", "CANCELLED"})
    void finalQuotationsAllowNothing(QuotationStatus status) {
        for (QuotationStatus.Action action : QuotationStatus.Action.values()) {
            assertThat(status.allows(action)).as(action.name()).isFalse();
        }
    }

    @Test
    void orderLifecycle() {
        assertThat(SalesOrderStatus.DRAFT.apply(SalesOrderStatus.Action.CONFIRM))
                .isEqualTo(SalesOrderStatus.CONFIRMED);
        assertThat(SalesOrderStatus.CONFIRMED.apply(SalesOrderStatus.Action.CANCEL))
                .isEqualTo(SalesOrderStatus.CANCELLED);
        assertThat(SalesOrderStatus.DELIVERED.apply(SalesOrderStatus.Action.CLOSE))
                .isEqualTo(SalesOrderStatus.CLOSED);
        assertThat(SalesOrderStatus.CONFIRMED.delivered(true, false)).isEqualTo(SalesOrderStatus.PARTIALLY_DELIVERED);
        assertThat(SalesOrderStatus.PARTIALLY_DELIVERED.delivered(true, true)).isEqualTo(SalesOrderStatus.DELIVERED);
        assertThat(SalesOrderStatus.CONFIRMED.delivered(false, false)).isEqualTo(SalesOrderStatus.CONFIRMED);
        assertThat(SalesOrderStatus.CLOSED.allows(SalesOrderStatus.Action.INVOICE))
                .isTrue();
        assertThat(SalesOrderStatus.PARTIALLY_DELIVERED.allows(SalesOrderStatus.Action.CANCEL))
                .isFalse();
        assertThat(SalesOrderStatus.DRAFT.allows(SalesOrderStatus.Action.DELIVER))
                .isFalse();
        assertThat(SalesOrderStatus.DELIVERED.allows(SalesOrderStatus.Action.RESERVE))
                .isFalse();
        assertThatThrownBy(() -> SalesOrderStatus.CONFIRMED.apply(SalesOrderStatus.Action.DELIVER))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> SalesOrderStatus.CANCELLED.apply(SalesOrderStatus.Action.CONFIRM))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> SalesOrderStatus.DELIVERED.delivered(true, true))
                .isInstanceOf(IllegalStateException.class);
        assertThat(SalesOrderStatus.Invoicing.of(false, false)).isEqualTo(SalesOrderStatus.Invoicing.NOT_INVOICED);
        assertThat(SalesOrderStatus.Invoicing.of(true, false)).isEqualTo(SalesOrderStatus.Invoicing.PARTIALLY_INVOICED);
        assertThat(SalesOrderStatus.Invoicing.of(true, true)).isEqualTo(SalesOrderStatus.Invoicing.INVOICED);
    }

    @Test
    void documentAndReturnLifecycles() {
        assertThat(DocumentStatus.DRAFT.apply(DocumentStatus.Action.POST)).isEqualTo(DocumentStatus.POSTED);
        assertThat(DocumentStatus.DRAFT.apply(DocumentStatus.Action.CANCEL)).isEqualTo(DocumentStatus.CANCELLED);
        assertThat(DocumentStatus.DRAFT.apply(DocumentStatus.Action.EDIT)).isEqualTo(DocumentStatus.DRAFT);
        assertThatThrownBy(() -> DocumentStatus.POSTED.apply(DocumentStatus.Action.CANCEL))
                .isInstanceOf(IllegalStateException.class);
        assertThat(ReturnStatus.DRAFT.apply(ReturnStatus.Action.RECEIVE)).isEqualTo(ReturnStatus.RECEIVED);
        assertThat(ReturnStatus.DRAFT.apply(ReturnStatus.Action.CANCEL)).isEqualTo(ReturnStatus.CANCELLED);
        assertThatThrownBy(() -> ReturnStatus.RECEIVED.apply(ReturnStatus.Action.RECEIVE))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void creditCheck() {
        BigDecimal limit = new BigDecimal("1000");
        assertThat(CreditCheck.evaluate(
                        CreditCheck.Mode.BLOCK, limit, false, new BigDecimal("600"), new BigDecimal("400")))
                .isEqualTo(CreditCheck.Outcome.PASSED);
        assertThat(CreditCheck.evaluate(
                        CreditCheck.Mode.BLOCK, limit, false, new BigDecimal("600"), new BigDecimal("401")))
                .isEqualTo(CreditCheck.Outcome.BLOCKED_LIMIT);
        assertThat(CreditCheck.evaluate(
                        CreditCheck.Mode.WARN, limit, false, new BigDecimal("600"), new BigDecimal("401")))
                .isEqualTo(CreditCheck.Outcome.WARNED);
        assertThat(CreditCheck.evaluate(CreditCheck.Mode.NONE, limit, false, new BigDecimal("6000"), BigDecimal.ONE))
                .isEqualTo(CreditCheck.Outcome.PASSED);
        assertThat(CreditCheck.evaluate(CreditCheck.Mode.BLOCK, null, false, new BigDecimal("6000"), BigDecimal.ONE))
                .isEqualTo(CreditCheck.Outcome.PASSED);
        assertThat(CreditCheck.evaluate(CreditCheck.Mode.NONE, null, true, BigDecimal.ZERO, BigDecimal.ONE))
                .isEqualTo(CreditCheck.Outcome.BLOCKED_ON_HOLD);
        assertThat(CreditCheck.Outcome.BLOCKED_ON_HOLD.blocked()).isTrue();
        assertThat(CreditCheck.Outcome.WARNED.blocked()).isFalse();
    }

    @Test
    void priceTiers() {
        List<PriceSelection.Item> items = List.of(
                new PriceSelection.Item(BigDecimal.ZERO, new BigDecimal("10"), null, null),
                new PriceSelection.Item(new BigDecimal("10"), new BigDecimal("9"), null, null),
                new PriceSelection.Item(new BigDecimal("10"), new BigDecimal("8"), DAY.minusDays(1), DAY.plusDays(1)),
                new PriceSelection.Item(new BigDecimal("100"), new BigDecimal("7"), null, DAY.minusDays(1)));
        assertThat(PriceSelection.best(items, new BigDecimal("5"), DAY))
                .hasValueSatisfying(i -> assertThat(i.unitPrice()).isEqualByComparingTo("10"));
        assertThat(PriceSelection.best(items, new BigDecimal("10"), DAY))
                .hasValueSatisfying(i -> assertThat(i.unitPrice()).isEqualByComparingTo("8"));
        assertThat(PriceSelection.best(items, new BigDecimal("10"), DAY.plusDays(5)))
                .hasValueSatisfying(i -> assertThat(i.unitPrice()).isEqualByComparingTo("9"));
        assertThat(PriceSelection.best(items, new BigDecimal("500"), DAY))
                .hasValueSatisfying(i -> assertThat(i.unitPrice()).isEqualByComparingTo("8"));
        assertThat(PriceSelection.best(
                        List.of(new PriceSelection.Item(new BigDecimal("5"), BigDecimal.ONE, null, null)),
                        BigDecimal.ONE,
                        DAY))
                .isEmpty();
    }
}
