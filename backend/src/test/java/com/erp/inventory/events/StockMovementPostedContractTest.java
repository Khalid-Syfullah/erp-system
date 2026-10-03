package com.erp.inventory.events;

import static org.assertj.core.api.Assertions.assertThat;

import com.erp.platform.events.DomainEvent;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Contract snapshot of {@code inventory.stock_movement.posted} v1 (ARCHITECTURE.md §7). Accounting
 * (Phase 8) books from these fields: renaming, removing or retyping one is a breaking change that
 * needs a new schema version, so this test fails until the snapshot is updated deliberately.
 */
class StockMovementPostedContractTest {

    @Test
    void eventTypeAndVersion() {
        assertThat(StockMovementPosted.TYPE).isEqualTo("inventory.stock_movement.posted");
        assertThat(StockMovementPosted.SCHEMA_VERSION).isEqualTo(1);
    }

    @Test
    void payloadFields() {
        assertThat(shape(StockMovementPosted.class))
                .containsExactly(
                        "metadata:EventMetadata",
                        "movementId:UUID",
                        "number:String",
                        "movementType:String",
                        "accountingDate:LocalDate",
                        "sourceRef:SourceRef",
                        "partnerId:UUID",
                        "reasonCodeId:UUID",
                        "reversalOfId:UUID",
                        "lines:List");
        assertThat(shape(StockMovementPosted.SourceRef.class))
                .containsExactly("module:String", "type:String", "id:UUID", "number:String");
        assertThat(shape(StockMovementPosted.Line.class))
                .containsExactly(
                        "lineId:UUID",
                        "variantId:UUID",
                        "categoryId:UUID",
                        "warehouseId:UUID",
                        "branchId:UUID",
                        "locationId:UUID",
                        "quantityBase:BigDecimal",
                        "unitCostBase:BigDecimal",
                        "valueBase:BigDecimal",
                        "referenceValueBase:BigDecimal");
        assertThat(shape(DomainEvent.EventMetadata.class))
                .containsExactly(
                        "eventId:UUID",
                        "eventType:String",
                        "schemaVersion:int",
                        "occurredAt:Instant",
                        "companyId:UUID",
                        "actorUserId:UUID",
                        "correlationId:String");
    }

    private static List<String> shape(Class<? extends Record> type) {
        return Arrays.stream(type.getRecordComponents())
                .map(RecordComponent.class::cast)
                .map(c -> c.getName() + ":" + c.getType().getSimpleName())
                .toList();
    }
}
