package com.erp.inventory.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Stock operations for Procurement and Sales (DEVELOPMENT_PLAN.md Phase 5). Every method runs in the
 * caller's transaction and company context; stock-changing methods create and post a stock movement
 * at once, writing the inventory ledger, balances and valuation atomically, or fail without effect.
 * A source document produces at most one movement of each kind (409 on a second attempt).
 */
public interface InventoryFacade {

    /** Goods receipt ({@code PURCHASE_RECEIPT}): stock in at the given base-currency unit cost. */
    PostedMovement receive(StockInRequest request);

    /** Customer return ({@code SALES_RETURN}): stock in at the original issue unit cost. */
    PostedMovement returnFromCustomer(StockInRequest request);

    /** Delivery ({@code SALES_ISSUE}): stock out at the moving average; lines may consume their reservation. */
    PostedMovement issue(StockOutRequest request);

    /** Return to supplier ({@code PURCHASE_RETURN}): stock out at the moving average. */
    PostedMovement returnToSupplier(StockOutRequest request);

    /** Reserves available stock for a source line (INV-6); partial reservations only when allowed. */
    ReservationResult reserve(ReservationRequest request);

    /** Releases a reservation, fully ({@code quantityBase == null}) or partly. */
    void release(UUID reservationId, @Nullable BigDecimal quantityBase);

    /** On-hand, reserved and available quantity of a variant in a warehouse (base units). */
    Availability availability(UUID variantId, UUID warehouseId);

    /** Converts a quantity in any unit to the variant's base unit (422 {@code UOM_NOT_CONVERTIBLE} if impossible). */
    BigDecimal convertQuantity(UUID variantId, BigDecimal quantity, UUID uomId);

    Optional<VariantInfo> variantInfo(UUID variantId);

    /** The originating document. */
    record SourceRef(
            String module, String type, UUID id, @Nullable String number) {}

    record StockInRequest(
            SourceRef source,
            LocalDate movementDate,
            UUID warehouseId,
            @Nullable UUID partnerId,
            List<InLine> lines,
            @Nullable String notes) {}

    /** {@code locationId == null}: the warehouse's default stock location. */
    record InLine(
            UUID variantId,
            BigDecimal quantity,
            UUID uomId,
            @Nullable UUID locationId,
            BigDecimal unitCostBase,
            @Nullable UUID sourceLineId) {}

    record StockOutRequest(
            SourceRef source,
            LocalDate movementDate,
            UUID warehouseId,
            @Nullable UUID partnerId,
            List<OutLine> lines,
            @Nullable String notes) {}

    /**
     * @param reservationId the line's own reservation, which it may consume (INV-2)
     * @param referenceUnitCostBase PURCHASE_RETURN: the original receipt unit cost (GRNI clearing)
     */
    record OutLine(
            UUID variantId,
            BigDecimal quantity,
            UUID uomId,
            @Nullable UUID locationId,
            @Nullable UUID reservationId,
            @Nullable BigDecimal referenceUnitCostBase,
            @Nullable UUID sourceLineId) {}

    record PostedMovement(UUID movementId, String number, List<PostedLine> lines) {}

    /** Valued result of one line; outbound values come from the moving average (INV-4). */
    record PostedLine(
            UUID lineId,
            @Nullable UUID sourceLineId,
            UUID variantId,
            BigDecimal quantityBase,
            BigDecimal unitCostBase,
            BigDecimal valueBase) {}

    record ReservationRequest(
            SourceRef source,
            UUID sourceLineId,
            UUID variantId,
            UUID warehouseId,
            BigDecimal quantityBase,
            boolean allowPartial) {}

    /** {@code reservationId} is null when nothing could be reserved. */
    record ReservationResult(
            @Nullable UUID reservationId, BigDecimal reservedQuantityBase, BigDecimal backorderQuantityBase) {}

    record Availability(
            UUID variantId, UUID warehouseId, BigDecimal onHand, BigDecimal reserved, BigDecimal available) {}

    record VariantInfo(
            UUID variantId,
            UUID productId,
            String sku,
            String name,
            String productType,
            String status,
            UUID categoryId,
            UUID baseUomId,
            @Nullable UUID purchaseUomId,
            @Nullable UUID salesUomId,
            boolean purchasable,
            boolean sellable,
            @Nullable UUID salesTaxCodeId,
            @Nullable UUID purchaseTaxCodeId) {}
}
