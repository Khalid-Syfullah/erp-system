package com.erp.inventory.application;

import com.erp.inventory.domain.LocationType;
import com.erp.inventory.domain.MovementStatus;
import com.erp.inventory.domain.MovementType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Read models of the Inventory module. */
public final class InventoryViews {

    public record UomCategory(UUID id, String code, String name) {}

    public record Uom(
            UUID id,
            UUID categoryId,
            String code,
            String name,
            BigDecimal factorToReference,
            int roundingScale,
            boolean active) {}

    public record Category(
            UUID id,
            UUID companyId,
            String code,
            String name,
            @Nullable UUID parentId,
            String path,
            boolean active,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            int version) {}

    public record Product(
            UUID id,
            UUID companyId,
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
            boolean hasVariants,
            String status,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            int version) {

        public boolean stockable() {
            return "STOCKABLE".equals(productType);
        }

        public boolean active() {
            return "ACTIVE".equals(status);
        }
    }

    public record UomConversion(
            UUID id, UUID productId, UUID uomId, BigDecimal factorToBase, OffsetDateTime createdAt, int version) {}

    public record Attribute(
            UUID id, String code, String name, List<AttributeValue> values, OffsetDateTime createdAt, int version) {}

    public record AttributeValue(UUID id, UUID attributeId, String code, String name, int sortOrder) {}

    public record Variant(
            UUID id,
            UUID companyId,
            UUID productId,
            String sku,
            @Nullable String barcode,
            String name,
            boolean isDefault,
            String status,
            @Nullable BigDecimal weightKg,
            Map<UUID, UUID> attributeValues,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            int version) {

        public boolean active() {
            return "ACTIVE".equals(status);
        }
    }

    /** A variant with what stock posting needs to know about its product. */
    public record StockItem(
            UUID variantId,
            UUID productId,
            String sku,
            String variantStatus,
            String productStatus,
            String productType,
            UUID categoryId,
            UUID baseUomId) {

        public boolean usable() {
            return "ACTIVE".equals(variantStatus) && "ACTIVE".equals(productStatus);
        }
    }

    public record Warehouse(
            UUID id,
            UUID companyId,
            UUID branchId,
            String code,
            String name,
            @Nullable String addressLine1,
            @Nullable String addressLine2,
            @Nullable String city,
            @Nullable String region,
            @Nullable String postalCode,
            @Nullable String countryCode,
            boolean active,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            int version) {}

    public record Location(
            UUID id,
            UUID companyId,
            UUID warehouseId,
            String code,
            String name,
            @Nullable UUID parentId,
            LocationType type,
            boolean active,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            int version) {}

    public record ReasonCode(
            UUID id,
            String code,
            String name,
            String appliesTo,
            boolean active,
            OffsetDateTime createdAt,
            int version) {}

    public record Settings(
            String costingMethod,
            boolean allowNegativeStock,
            BigDecimal overReceiptTolerancePercent,
            @Nullable BigDecimal adjustmentApprovalThreshold,
            int version) {}

    public record Movement(
            UUID id,
            UUID companyId,
            @Nullable String number,
            MovementType type,
            MovementStatus status,
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
            @Nullable OffsetDateTime postedAt,
            @Nullable UUID postedBy,
            @Nullable String notes,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            int version) {}

    public record MovementLine(
            UUID id,
            UUID movementId,
            int lineNo,
            UUID variantId,
            @Nullable UUID fromLocationId,
            @Nullable UUID toLocationId,
            BigDecimal quantity,
            UUID uomId,
            BigDecimal quantityBase,
            @Nullable BigDecimal unitCostBase,
            @Nullable BigDecimal referenceUnitCostBase,
            @Nullable UUID reservationId,
            @Nullable UUID sourceLineId,
            @Nullable UUID reversalOfLineId) {}

    public record MovementDetail(Movement movement, List<MovementLine> lines) {}

    public record LedgerEntry(
            UUID id,
            UUID movementId,
            UUID movementLineId,
            String movementType,
            LocalDate transactionDate,
            UUID variantId,
            UUID warehouseId,
            UUID locationId,
            BigDecimal quantityBase,
            BigDecimal unitCostBase,
            BigDecimal valueBase,
            OffsetDateTime createdAt,
            @Nullable UUID createdBy) {}

    public record WarehouseStockLevel(
            UUID variantId, UUID warehouseId, BigDecimal onHand, BigDecimal reserved, OffsetDateTime updatedAt) {

        public BigDecimal available() {
            return onHand.subtract(reserved);
        }
    }

    public record LocationStockLevel(
            UUID variantId, UUID warehouseId, UUID locationId, BigDecimal onHand, OffsetDateTime updatedAt) {}

    public record ItemValuation(UUID variantId, BigDecimal quantityBase, BigDecimal totalValueBase) {}

    public record Reservation(
            UUID id,
            UUID variantId,
            UUID warehouseId,
            BigDecimal quantityBase,
            String sourceModule,
            String sourceType,
            UUID sourceId,
            UUID sourceLineId,
            String status,
            int version) {}

    public record Count(
            UUID id,
            UUID companyId,
            @Nullable String number,
            UUID warehouseId,
            LocalDate countDate,
            String status,
            @Nullable UUID reasonCodeId,
            @Nullable UUID adjustmentMovementId,
            @Nullable String notes,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            int version) {}

    public record CountLine(
            UUID id,
            UUID variantId,
            UUID locationId,
            BigDecimal systemQuantityBase,
            @Nullable BigDecimal countedQuantityBase) {}

    public record CountDetail(Count count, List<CountLine> lines) {}

    private InventoryViews() {}
}
