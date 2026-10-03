package com.erp.inventory.application;

import com.erp.inventory.domain.LocationType;
import com.erp.inventory.domain.MovementType;
import com.erp.inventory.domain.UomConversion;
import com.erp.inventory.persistence.ProductRepository;
import com.erp.inventory.persistence.UomRepository;
import com.erp.inventory.persistence.VariantRepository;
import com.erp.inventory.persistence.WarehouseRepository;
import com.erp.platform.web.ApiException;
import com.erp.platform.web.FieldViolation;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Validates movement lines and converts their quantities to base units (G-15). The same rules apply
 * to drafts entered through the API and to movements created by Procurement and Sales; the posting
 * engine re-checks everything that may have changed since (statuses, stock).
 */
@Component
class MovementLineResolver {

    static final int MAX_LINES = 500;
    static final int COST_SCALE = 6;

    private final VariantRepository variants;
    private final ProductRepository products;
    private final UomRepository uoms;
    private final WarehouseRepository warehouses;

    MovementLineResolver(
            VariantRepository variants,
            ProductRepository products,
            UomRepository uoms,
            WarehouseRepository warehouses) {
        this.variants = variants;
        this.products = products;
        this.uoms = uoms;
        this.warehouses = warehouses;
    }

    List<InventoryCommands.ResolvedLine> resolve(
            UUID companyId,
            MovementType type,
            UUID warehouseId,
            @Nullable UUID destWarehouseId,
            List<InventoryCommands.Line> lines) {
        if (lines.isEmpty() || lines.size() > MAX_LINES) {
            throw ApiException.validationFailed(
                    "The movement is invalid.",
                    List.of(FieldViolation.atPointer("/lines", "SIZE", "must have 1 to " + MAX_LINES + " lines")));
        }
        Set<UUID> locationIds = new HashSet<>();
        Set<UUID> variantIds = new HashSet<>();
        Set<UUID> uomIds = new HashSet<>();
        for (InventoryCommands.Line line : lines) {
            if (line.fromLocationId() != null) {
                locationIds.add(line.fromLocationId());
            }
            if (line.toLocationId() != null) {
                locationIds.add(line.toLocationId());
            }
            variantIds.add(line.variantId());
            uomIds.add(line.uomId());
        }
        Map<UUID, InventoryViews.Location> locations = warehouses.locations(companyId, locationIds);
        Map<UUID, InventoryViews.StockItem> items = variants.items(companyId, variantIds);
        Map<UUID, UomConversion.Unit> units = new HashMap<>(uoms.units(uomIds));
        items.values().forEach(i -> uomIds.add(i.baseUomId()));
        units.putAll(uoms.units(uomIds));
        Map<UUID, Map<UUID, BigDecimal>> factors = new HashMap<>();

        List<FieldViolation> violations = new ArrayList<>();
        List<FieldViolation> conversion = new ArrayList<>();
        List<InventoryCommands.ResolvedLine> resolved = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            InventoryCommands.Line line = lines.get(i);
            String at = "/lines/" + i;
            boolean hasFrom = line.fromLocationId() != null;
            boolean hasTo = line.toLocationId() != null;
            if (!type.accepts(hasFrom, hasTo)) {
                violations.add(FieldViolation.atPointer(at, "INVALID_LOCATIONS", locationRule(type)));
                continue;
            }
            if (hasFrom) {
                checkLocation(
                        type,
                        locations.get(line.fromLocationId()),
                        warehouseId,
                        true,
                        at + "/fromLocationId",
                        violations);
            }
            if (hasTo) {
                UUID expected = switch (type) {
                    case TRANSFER -> destWarehouseId != null ? destWarehouseId : warehouseId;
                    case TRANSFER_SHIP -> Objects.requireNonNull(destWarehouseId);
                    default -> warehouseId;
                };
                checkLocation(
                        type, locations.get(line.toLocationId()), expected, false, at + "/toLocationId", violations);
            }
            InventoryViews.StockItem item = items.get(line.variantId());
            if (item == null) {
                violations.add(FieldViolation.atPointer(
                        at + "/variantId", "UNKNOWN_VARIANT", "is not a variant of the company"));
                continue;
            }
            if (!"STOCKABLE".equals(item.productType())) {
                violations.add(FieldViolation.atPointer(
                        at + "/variantId",
                        "NOT_STOCKABLE",
                        "belongs to a " + item.productType() + " product, which has no stock"));
            } else if (!item.usable() && type != MovementType.REVERSAL) {
                violations.add(FieldViolation.atPointer(at + "/variantId", "INACTIVE", "is archived"));
            }
            checkCosts(type, line, hasFrom, hasTo, at, violations);
            UomConversion.Unit unit = units.get(line.uomId());
            UomConversion.Unit base = units.get(item.baseUomId());
            if (unit == null) {
                violations.add(FieldViolation.atPointer(at + "/uomId", "UNKNOWN_UOM", "is not a unit of measure"));
                continue;
            }
            Map<UUID, BigDecimal> productFactors =
                    factors.computeIfAbsent(item.productId(), p -> products.conversionFactors(companyId, p));
            UomConversion.Result result = UomConversion.toBase(line.quantity(), unit, base, productFactors);
            if (!result.ok()) {
                FieldViolation failure = switch (result.failure()) {
                    case NOT_CONVERTIBLE ->
                        FieldViolation.atPointer(
                                at + "/uomId",
                                "UOM_NOT_CONVERTIBLE",
                                unit.code() + " cannot be converted to " + base.code());
                    case TOO_PRECISE_FOR_UNIT ->
                        FieldViolation.atPointer(
                                at + "/quantity",
                                "TOO_PRECISE",
                                "has more decimal places than " + unit.code() + " allows");
                    case ZERO_IN_BASE_UNIT ->
                        FieldViolation.atPointer(
                                at + "/quantity", "TOO_SMALL", "is less than one " + base.code() + " step");
                };
                (result.failure() == UomConversion.Failure.NOT_CONVERTIBLE ? conversion : violations).add(failure);
                continue;
            }
            resolved.add(new InventoryCommands.ResolvedLine(line, result.quantityBase()));
        }
        if (!violations.isEmpty()) {
            violations.addAll(conversion);
            throw ApiException.validationFailed("The movement lines are invalid.", violations);
        }
        if (!conversion.isEmpty()) {
            throw new ApiException(
                    InventoryErrorCode.UOM_NOT_CONVERTIBLE,
                    "A line's unit cannot be converted to the base unit.",
                    conversion);
        }
        return resolved;
    }

    private static void checkLocation(
            MovementType type,
            InventoryViews.@Nullable Location location,
            UUID expectedWarehouse,
            boolean from,
            String pointer,
            List<FieldViolation> violations) {
        if (location == null
                || (type != MovementType.REVERSAL && !location.warehouseId().equals(expectedWarehouse))) {
            violations.add(FieldViolation.atPointer(
                    pointer, "UNKNOWN_LOCATION", "is not a location of the movement's warehouse"));
            return;
        }
        if (!location.active() && type != MovementType.REVERSAL) {
            violations.add(FieldViolation.atPointer(pointer, "INACTIVE", "is an inactive location"));
            return;
        }
        boolean transit = location.type() == LocationType.TRANSIT;
        boolean transitExpected =
                (type == MovementType.TRANSFER_SHIP && !from) || (type == MovementType.TRANSFER_RECEIVE && from);
        if (type != MovementType.REVERSAL && transit != transitExpected) {
            violations.add(FieldViolation.atPointer(
                    pointer,
                    "INVALID_LOCATION_TYPE",
                    transitExpected
                            ? "must be the transit location of the destination warehouse"
                            : "transit locations are used only by two-step transfers"));
            return;
        }
        if (type == MovementType.SALES_ISSUE && location.type() == LocationType.QUARANTINE) {
            violations.add(FieldViolation.atPointer(
                    pointer, "INVALID_LOCATION_TYPE", "quarantined stock cannot be delivered"));
        }
    }

    private static void checkCosts(
            MovementType type,
            InventoryCommands.Line line,
            boolean hasFrom,
            boolean hasTo,
            String at,
            List<FieldViolation> violations) {
        boolean inbound = hasTo && !hasFrom;
        BigDecimal cost = line.unitCostBase();
        if (cost != null) {
            if (!inbound || type == MovementType.REVERSAL) {
                violations.add(FieldViolation.atPointer(
                        at + "/unitCostBase", "NOT_ALLOWED", "is computed from the moving average for this line"));
            } else if (cost.signum() < 0 || cost.stripTrailingZeros().scale() > COST_SCALE) {
                violations.add(FieldViolation.atPointer(
                        at + "/unitCostBase",
                        "INVALID_VALUE",
                        "must be ≥ 0 with at most " + COST_SCALE + " decimal places"));
            }
        } else if (inbound && type.requiresInboundCost()) {
            violations.add(FieldViolation.atPointer(
                    at + "/unitCostBase", "REQUIRED", "is required for incoming stock of this type"));
        }
        if (line.reservationId() != null && type != MovementType.SALES_ISSUE) {
            violations.add(FieldViolation.atPointer(
                    at + "/reservationId", "NOT_ALLOWED", "only deliveries consume reservations"));
        }
        if (line.referenceUnitCostBase() != null && type != MovementType.PURCHASE_RETURN) {
            violations.add(FieldViolation.atPointer(
                    at + "/referenceUnitCostBase", "NOT_ALLOWED", "only purchase returns carry a reference cost"));
        }
    }

    private static String locationRule(MovementType type) {
        return switch (type.shape()) {
            case IN -> "must name only a destination location (toLocationId)";
            case OUT -> "must name only a source location (fromLocationId)";
            case MOVE -> "must name a source and a destination location";
            case IN_OR_OUT -> "must name either a source (stock out) or a destination (stock in) location";
            case ANY -> "must name a location";
        };
    }
}
