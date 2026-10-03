package com.erp.inventory.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Units, movement types and the movement and count state machines. */
class InventoryDomainTest {

    private static final UUID UNIT = UUID.randomUUID();
    private static final UUID WEIGHT = UUID.randomUUID();
    private static final UomConversion.Unit EA = unit("EA", UNIT, "1", 0);
    private static final UomConversion.Unit DOZ = unit("DOZ", UNIT, "12", 0);
    private static final UomConversion.Unit KG = unit("KG", WEIGHT, "1", 3);
    private static final UomConversion.Unit LB = unit("LB", WEIGHT, "0.45359237", 3);
    private static final UomConversion.Unit G = unit("G", WEIGHT, "0.001", 0);

    @Test
    void sameCategoryUnitsConvertThroughTheReferenceUnit() {
        assertThat(UomConversion.toBase(new BigDecimal("2"), DOZ, EA, Map.of()).quantityBase())
                .isEqualByComparingTo("24");
        assertThat(UomConversion.toBase(BigDecimal.ONE, LB, KG, Map.of()).quantityBase())
                .isEqualByComparingTo("0.454");
        assertThat(UomConversion.toBase(new BigDecimal("1500"), G, KG, Map.of()).quantityBase())
                .isEqualByComparingTo("1.5");
        assertThat(UomConversion.toBase(BigDecimal.ONE, EA, EA, Map.of()).quantityBase())
                .isEqualByComparingTo("1")
                .hasScaleOf(UomConversion.QUANTITY_SCALE);
    }

    @Test
    void otherCategoriesNeedAProductConversion() {
        assertThat(UomConversion.toBase(BigDecimal.ONE, KG, EA, Map.of()).failure())
                .isEqualTo(UomConversion.Failure.NOT_CONVERTIBLE);
        UomConversion.Result result =
                UomConversion.toBase(new BigDecimal("0.5"), KG, EA, Map.of(KG.id(), new BigDecimal("40")));
        assertThat(result.ok()).isTrue();
        assertThat(result.quantityBase()).isEqualByComparingTo("20");
    }

    @Test
    void quantitiesMustFitTheirUnitsAndSurviveConversion() {
        assertThat(UomConversion.toBase(new BigDecimal("1.5"), EA, EA, Map.of()).failure())
                .isEqualTo(UomConversion.Failure.TOO_PRECISE_FOR_UNIT);
        assertThat(UomConversion.toBase(new BigDecimal("1.00"), EA, EA, Map.of())
                        .ok())
                .isTrue();
        // 1 g is 0.001 kg: fine; 0.4 g would round to zero kilograms at three decimals.
        assertThat(UomConversion.toBase(BigDecimal.ONE, G, KG, Map.of()).quantityBase())
                .isEqualByComparingTo("0.001");
        assertThat(UomConversion.toBase(new BigDecimal("0.0004"), KG, unit("T", WEIGHT, "1000", 3), Map.of())
                        .failure())
                .isEqualTo(UomConversion.Failure.TOO_PRECISE_FOR_UNIT);
        assertThat(UomConversion.toBase(BigDecimal.ONE, unit("MG", WEIGHT, "0.000001", 0), KG, Map.of())
                        .failure())
                .isEqualTo(UomConversion.Failure.ZERO_IN_BASE_UNIT);
    }

    @Test
    void movementTypesDefineTheirLineShapes() {
        assertThat(MovementType.OPENING.accepts(false, true)).isTrue();
        assertThat(MovementType.OPENING.accepts(true, true)).isFalse();
        assertThat(MovementType.SALES_ISSUE.accepts(true, false)).isTrue();
        assertThat(MovementType.TRANSFER.accepts(true, false)).isFalse();
        assertThat(MovementType.TRANSFER.accepts(true, true)).isTrue();
        assertThat(MovementType.ADJUSTMENT.accepts(true, false)).isTrue();
        assertThat(MovementType.ADJUSTMENT.accepts(false, true)).isTrue();
        assertThat(MovementType.ADJUSTMENT.accepts(true, true)).isFalse();
        assertThat(MovementType.REVERSAL.accepts(false, false)).isFalse();
        assertThat(MovementType.REVERSAL.accepts(true, true)).isTrue();
        assertThat(MovementType.SCRAP.isAdjustment()).isTrue();
        assertThat(MovementType.TRANSFER.requiresReason()).isFalse();
        assertThat(MovementType.SALES_RETURN.requiresInboundCost()).isTrue();
        assertThat(MovementType.ADJUSTMENT.requiresInboundCost()).isFalse();
        assertThat(MovementType.API_CREATABLE).doesNotContain(MovementType.PURCHASE_RECEIPT, MovementType.REVERSAL);
        assertThat(LocationType.QUARANTINE.countsTowardWarehouse()).isFalse();
        assertThat(LocationType.TRANSIT.countsTowardWarehouse()).isFalse();
        assertThat(LocationType.RECEIVING.countsTowardWarehouse()).isTrue();
    }

    @Test
    void movementStateMachine() {
        assertThat(MovementStatus.DRAFT.apply(MovementStatus.Action.POST)).isEqualTo(MovementStatus.POSTED);
        assertThat(MovementStatus.DRAFT.apply(MovementStatus.Action.CANCEL)).isEqualTo(MovementStatus.CANCELLED);
        assertThat(MovementStatus.DRAFT.apply(MovementStatus.Action.EDIT)).isEqualTo(MovementStatus.DRAFT);
        assertThat(MovementStatus.POSTED.apply(MovementStatus.Action.REVERSE)).isEqualTo(MovementStatus.POSTED);
        assertThat(MovementStatus.DRAFT.allows(MovementStatus.Action.REVERSE)).isFalse();
        for (MovementStatus.Action action : MovementStatus.Action.values()) {
            if (action != MovementStatus.Action.REVERSE) {
                assertThat(MovementStatus.POSTED.allows(action))
                        .as("posted is immutable: %s", action)
                        .isFalse();
            }
            assertThat(MovementStatus.CANCELLED.allows(action))
                    .as("cancelled is final: %s", action)
                    .isFalse();
        }
        assertThatThrownBy(() -> MovementStatus.POSTED.apply(MovementStatus.Action.EDIT))
                .isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @EnumSource(CountStatus.Action.class)
    void countStateMachine(CountStatus.Action action) {
        assertThat(CountStatus.POSTED.allows(action)).isFalse();
        assertThat(CountStatus.CANCELLED.allows(action)).isFalse();
        CountStatus from = switch (action) {
            case START -> CountStatus.DRAFT;
            case ENTER, COMPLETE -> CountStatus.IN_PROGRESS;
            case POST -> CountStatus.COMPLETED;
            case CANCEL -> CountStatus.IN_PROGRESS;
        };
        CountStatus expected = switch (action) {
            case START -> CountStatus.IN_PROGRESS;
            case ENTER -> CountStatus.IN_PROGRESS;
            case COMPLETE -> CountStatus.COMPLETED;
            case POST -> CountStatus.POSTED;
            case CANCEL -> CountStatus.CANCELLED;
        };
        assertThat(from.apply(action)).isEqualTo(expected);
        assertThatThrownBy(() -> CountStatus.POSTED.apply(action)).isInstanceOf(IllegalStateException.class);
    }

    private static UomConversion.Unit unit(String code, UUID category, String factor, int scale) {
        return new UomConversion.Unit(UUID.randomUUID(), code, category, new BigDecimal(factor), scale);
    }
}
