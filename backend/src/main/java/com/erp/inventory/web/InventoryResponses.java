package com.erp.inventory.web;

import com.erp.inventory.application.InventoryViews;
import com.erp.platform.web.EntityTags;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;

/** Response shapes of the Inventory API (decimals are serialized as strings, API.md §11). */
final class InventoryResponses {

    static final String MERGE_PATCH = "application/merge-patch+json";

    record ListResponse<T>(List<T> data) {}

    record Category(
            UUID id,
            String code,
            String name,
            @Nullable UUID parentId,
            String path,
            boolean isActive,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            int version) {
        static Category from(InventoryViews.Category c) {
            return new Category(
                    c.id(),
                    c.code(),
                    c.name(),
                    c.parentId(),
                    c.path(),
                    c.active(),
                    c.createdAt(),
                    c.updatedAt(),
                    c.version());
        }
    }

    record Product(
            UUID id,
            String code,
            String name,
            @Nullable String description,
            UUID categoryId,
            String productType,
            UUID baseUomId,
            @Nullable UUID purchaseUomId,
            @Nullable UUID salesUomId,
            boolean isPurchasable,
            boolean isSellable,
            @Nullable UUID salesTaxCodeId,
            @Nullable UUID purchaseTaxCodeId,
            boolean hasVariants,
            String status,
            @Nullable List<Variant> variants,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            int version) {
        static Product from(InventoryViews.Product p, @Nullable List<InventoryViews.Variant> variants) {
            return new Product(
                    p.id(),
                    p.code(),
                    p.name(),
                    p.description(),
                    p.categoryId(),
                    p.productType(),
                    p.baseUomId(),
                    p.purchaseUomId(),
                    p.salesUomId(),
                    p.purchasable(),
                    p.sellable(),
                    p.salesTaxCodeId(),
                    p.purchaseTaxCodeId(),
                    p.hasVariants(),
                    p.status(),
                    variants == null
                            ? null
                            : variants.stream().map(Variant::from).toList(),
                    p.createdAt(),
                    p.updatedAt(),
                    p.version());
        }
    }

    record Variant(
            UUID id,
            UUID productId,
            String sku,
            @Nullable String barcode,
            String name,
            boolean isDefault,
            String status,
            @Nullable BigDecimal weightKg,
            Map<UUID, UUID> attributes,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            int version) {
        static Variant from(InventoryViews.Variant v) {
            return new Variant(
                    v.id(),
                    v.productId(),
                    v.sku(),
                    v.barcode(),
                    v.name(),
                    v.isDefault(),
                    v.status(),
                    v.weightKg(),
                    v.attributeValues(),
                    v.createdAt(),
                    v.updatedAt(),
                    v.version());
        }
    }

    record Conversion(UUID id, UUID productId, UUID uomId, BigDecimal factorToBase, OffsetDateTime createdAt) {
        static Conversion from(InventoryViews.UomConversion c) {
            return new Conversion(c.id(), c.productId(), c.uomId(), c.factorToBase(), c.createdAt());
        }
    }

    record Attribute(UUID id, String code, String name, List<InventoryViews.AttributeValue> values) {
        static Attribute from(InventoryViews.Attribute a) {
            return new Attribute(a.id(), a.code(), a.name(), a.values());
        }
    }

    record Warehouse(
            UUID id,
            UUID branchId,
            String code,
            String name,
            @Nullable String addressLine1,
            @Nullable String addressLine2,
            @Nullable String city,
            @Nullable String region,
            @Nullable String postalCode,
            @Nullable String countryCode,
            boolean isActive,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            int version) {
        static Warehouse from(InventoryViews.Warehouse w) {
            return new Warehouse(
                    w.id(),
                    w.branchId(),
                    w.code(),
                    w.name(),
                    w.addressLine1(),
                    w.addressLine2(),
                    w.city(),
                    w.region(),
                    w.postalCode(),
                    w.countryCode(),
                    w.active(),
                    w.createdAt(),
                    w.updatedAt(),
                    w.version());
        }
    }

    record Location(
            UUID id,
            UUID warehouseId,
            String code,
            String name,
            @Nullable UUID parentId,
            String locationType,
            boolean isActive,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            int version) {
        static Location from(InventoryViews.Location l) {
            return new Location(
                    l.id(),
                    l.warehouseId(),
                    l.code(),
                    l.name(),
                    l.parentId(),
                    l.type().name(),
                    l.active(),
                    l.createdAt(),
                    l.updatedAt(),
                    l.version());
        }
    }

    record ReasonCode(UUID id, String code, String name, String appliesTo, boolean isActive) {
        static ReasonCode from(InventoryViews.ReasonCode r) {
            return new ReasonCode(r.id(), r.code(), r.name(), r.appliesTo(), r.active());
        }
    }

    record Settings(
            String costingMethod,
            boolean allowNegativeStock,
            BigDecimal overReceiptTolerancePercent,
            @Nullable BigDecimal adjustmentApprovalThreshold) {
        static Settings from(InventoryViews.Settings s) {
            return new Settings(
                    s.costingMethod(),
                    s.allowNegativeStock(),
                    s.overReceiptTolerancePercent(),
                    s.adjustmentApprovalThreshold());
        }
    }

    record MovementLine(
            UUID id,
            int lineNo,
            UUID variantId,
            @Nullable UUID fromLocationId,
            @Nullable UUID toLocationId,
            BigDecimal quantity,
            UUID uomId,
            BigDecimal quantityBase,
            @Nullable BigDecimal unitCostBase,
            @Nullable UUID reversalOfLineId) {
        static MovementLine from(InventoryViews.MovementLine l) {
            return new MovementLine(
                    l.id(),
                    l.lineNo(),
                    l.variantId(),
                    l.fromLocationId(),
                    l.toLocationId(),
                    l.quantity(),
                    l.uomId(),
                    l.quantityBase(),
                    l.unitCostBase(),
                    l.reversalOfLineId());
        }
    }

    record Movement(
            UUID id,
            @Nullable String number,
            String movementType,
            String status,
            LocalDate movementDate,
            UUID warehouseId,
            @Nullable UUID destWarehouseId,
            @Nullable UUID partnerId,
            @Nullable UUID reasonCodeId,
            @Nullable SourceRef source,
            @Nullable UUID reversalOfId,
            @Nullable UUID relatedMovementId,
            @Nullable OffsetDateTime postedAt,
            @Nullable UUID postedBy,
            @Nullable String notes,
            @Nullable List<MovementLine> lines,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            int version) {
        static Movement from(InventoryViews.Movement m, @Nullable List<InventoryViews.MovementLine> lines) {
            return new Movement(
                    m.id(),
                    m.number(),
                    m.type().name(),
                    m.status().name(),
                    m.movementDate(),
                    m.warehouseId(),
                    m.destWarehouseId(),
                    m.partnerId(),
                    m.reasonCodeId(),
                    m.sourceId() == null
                            ? null
                            : new SourceRef(m.sourceModule(), m.sourceType(), m.sourceId(), m.sourceNumber()),
                    m.reversalOfId(),
                    m.relatedMovementId(),
                    m.postedAt(),
                    m.postedBy(),
                    m.notes(),
                    lines == null
                            ? null
                            : lines.stream().map(MovementLine::from).toList(),
                    m.createdAt(),
                    m.updatedAt(),
                    m.version());
        }

        static ResponseEntity<Movement> entity(InventoryViews.MovementDetail detail) {
            return ResponseEntity.ok()
                    .eTag(EntityTags.forVersion(detail.movement().version()))
                    .body(from(detail.movement(), detail.lines()));
        }
    }

    record SourceRef(
            String module, String type, UUID id, @Nullable String number) {}

    record StockLevel(UUID variantId, UUID warehouseId, BigDecimal onHand, BigDecimal reserved, BigDecimal available) {
        static StockLevel from(InventoryViews.WarehouseStockLevel l) {
            return new StockLevel(l.variantId(), l.warehouseId(), l.onHand(), l.reserved(), l.available());
        }
    }

    record LocationLevel(UUID variantId, UUID warehouseId, UUID locationId, BigDecimal onHand) {
        static LocationLevel from(InventoryViews.LocationStockLevel l) {
            return new LocationLevel(l.variantId(), l.warehouseId(), l.locationId(), l.onHand());
        }
    }

    record LedgerEntry(
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
            @Nullable UUID createdBy) {
        static LedgerEntry from(InventoryViews.LedgerEntry e) {
            return new LedgerEntry(
                    e.id(),
                    e.movementId(),
                    e.movementLineId(),
                    e.movementType(),
                    e.transactionDate(),
                    e.variantId(),
                    e.warehouseId(),
                    e.locationId(),
                    e.quantityBase(),
                    e.unitCostBase(),
                    e.valueBase(),
                    e.createdAt(),
                    e.createdBy());
        }
    }

    record Valuation(
            UUID variantId,
            BigDecimal quantityBase,
            BigDecimal totalValueBase,
            @Nullable BigDecimal averageCostBase) {
        static Valuation from(InventoryViews.ItemValuation v) {
            com.erp.inventory.domain.Valuation valuation =
                    new com.erp.inventory.domain.Valuation(v.quantityBase(), v.totalValueBase());
            return new Valuation(v.variantId(), v.quantityBase(), v.totalValueBase(), valuation.averageCost());
        }
    }

    record ValuationResponse(@Nullable LocalDate asOf, BigDecimal totalValueBase, List<Valuation> data) {}

    record CountLine(
            UUID id,
            UUID variantId,
            UUID locationId,
            BigDecimal systemQuantityBase,
            @Nullable BigDecimal countedQuantityBase) {
        static CountLine from(InventoryViews.CountLine l) {
            return new CountLine(
                    l.id(), l.variantId(), l.locationId(), l.systemQuantityBase(), l.countedQuantityBase());
        }
    }

    record Count(
            UUID id,
            @Nullable String number,
            UUID warehouseId,
            LocalDate countDate,
            String status,
            @Nullable UUID reasonCodeId,
            @Nullable UUID adjustmentMovementId,
            @Nullable String notes,
            @Nullable List<CountLine> lines,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            int version) {
        static Count from(InventoryViews.Count c, @Nullable List<InventoryViews.CountLine> lines) {
            return new Count(
                    c.id(),
                    c.number(),
                    c.warehouseId(),
                    c.countDate(),
                    c.status(),
                    c.reasonCodeId(),
                    c.adjustmentMovementId(),
                    c.notes(),
                    lines == null ? null : lines.stream().map(CountLine::from).toList(),
                    c.createdAt(),
                    c.updatedAt(),
                    c.version());
        }

        static ResponseEntity<Count> entity(InventoryViews.CountDetail detail) {
            return ResponseEntity.ok()
                    .eTag(EntityTags.forVersion(detail.count().version()))
                    .body(from(detail.count(), detail.lines()));
        }
    }

    private InventoryResponses() {}
}
