package com.erp.inventory.events;

import com.erp.platform.events.DomainEvent;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * {@code inventory.stock_movement.posted} (ARCHITECTURE.md §7), schema version 1: published in the
 * posting transaction; Accounting (Phase 8) books stock valuation entries from it synchronously.
 * Self-contained: one line per inventory ledger row with its category, branch and signed values.
 *
 * <pre>
 * {"metadata": {...}, "movementId": "…", "number": "SM-2026-000001", "movementType": "TRANSFER",
 *  "accountingDate": "2026-10-05", "sourceRef": null | {"module","type","id","number"},
 *  "partnerId": null, "reasonCodeId": null, "reversalOfId": null,
 *  "lines": [{"lineId","variantId","categoryId","warehouseId","branchId","locationId",
 *             "quantityBase": "-5.000000", "unitCostBase": "2.000000", "valueBase": "-10.00",
 *             "referenceValueBase": null}]}
 * </pre>
 */
public record StockMovementPosted(
        EventMetadata metadata,
        UUID movementId,
        String number,
        String movementType,
        LocalDate accountingDate,
        @Nullable SourceRef sourceRef,
        @Nullable UUID partnerId,
        @Nullable UUID reasonCodeId,
        @Nullable UUID reversalOfId,
        List<Line> lines)
        implements DomainEvent {

    public static final String TYPE = "inventory.stock_movement.posted";
    public static final int SCHEMA_VERSION = 1;

    public record SourceRef(
            String module, String type, UUID id, @Nullable String number) {}

    /**
     * @param quantityBase signed: positive into the location, negative out of it
     * @param valueBase signed, rounded to base currency minor units
     * @param referenceValueBase PURCHASE_RETURN: value at the original receipt cost, signed like {@code valueBase}
     */
    public record Line(
            UUID lineId,
            UUID variantId,
            UUID categoryId,
            UUID warehouseId,
            UUID branchId,
            UUID locationId,
            BigDecimal quantityBase,
            BigDecimal unitCostBase,
            BigDecimal valueBase,
            @Nullable BigDecimal referenceValueBase) {}
}
