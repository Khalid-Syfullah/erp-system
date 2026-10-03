package com.erp.inventory.application;

import com.erp.inventory.domain.LocationType;
import com.erp.inventory.domain.MovementType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Inputs of the Inventory services and repositories. */
public final class InventoryCommands {

    public record Category(
            String code, String name, @Nullable UUID parentId, boolean active) {}

    public record Product(
            String code,
            String name,
            @Nullable String description,
            UUID categoryId,
            String productType,
            UUID baseUomId,
            @Nullable UUID purchaseUomId,
            @Nullable UUID salesUomId,
            boolean purchasable,
            boolean sellable,
            @Nullable UUID salesTaxCodeId,
            @Nullable UUID purchaseTaxCodeId,
            boolean hasVariants) {}

    /** {@code attributeValues}: attribute ID → value ID. */
    public record Variant(
            String sku,
            @Nullable String barcode,
            @Nullable String name,
            @Nullable BigDecimal weightKg,
            Map<UUID, UUID> attributeValues) {}

    public record Warehouse(
            UUID branchId,
            String code,
            String name,
            @Nullable String addressLine1,
            @Nullable String addressLine2,
            @Nullable String city,
            @Nullable String region,
            @Nullable String postalCode,
            @Nullable String countryCode) {}

    public record Location(
            String code, String name, @Nullable UUID parentId, LocationType type) {}

    public record Movement(
            MovementType type,
            LocalDate movementDate,
            UUID warehouseId,
            @Nullable UUID destWarehouseId,
            @Nullable UUID partnerId,
            @Nullable UUID reasonCodeId,
            @Nullable String sourceModule,
            @Nullable String sourceType,
            @Nullable UUID sourceId,
            @Nullable String sourceNumber,
            @Nullable UUID reversalOfId,
            @Nullable UUID relatedMovementId,
            @Nullable String notes,
            List<Line> lines) {}

    /** A movement line as entered; {@code quantityBase} is computed by the service. */
    public record Line(
            UUID variantId,
            @Nullable UUID fromLocationId,
            @Nullable UUID toLocationId,
            BigDecimal quantity,
            UUID uomId,
            @Nullable BigDecimal unitCostBase,
            @Nullable BigDecimal referenceUnitCostBase,
            @Nullable UUID reservationId,
            @Nullable UUID sourceLineId,
            @Nullable UUID reversalOfLineId) {}

    /** A validated line ready to store. */
    public record ResolvedLine(Line line, BigDecimal quantityBase) {}

    private InventoryCommands() {}
}
