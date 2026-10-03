package com.erp.inventory.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Converts quantities to a product's base unit (DATABASE.md §5.5): within a UoM category through the
 * reference factors, across categories only through a product-specific conversion.
 *
 * <ul>
 *   <li>The entered quantity must fit its unit's precision (e.g. 1.5 EA is rejected).
 *   <li>The base quantity is rounded HALF_UP to the base unit's precision when the factor is inexact
 *       (1 LB → 0.454 KG); the line keeps both quantities, so the rounding is visible.
 *   <li>A quantity that rounds to zero base units is rejected.
 * </ul>
 */
public final class UomConversion {

    /** A unit with its category, factor to the category's reference unit and decimal places. */
    public record Unit(UUID id, String code, UUID categoryId, BigDecimal factorToReference, int roundingScale) {}

    /** Why a conversion failed. */
    public enum Failure {
        NOT_CONVERTIBLE,
        TOO_PRECISE_FOR_UNIT,
        ZERO_IN_BASE_UNIT
    }

    /** The converted quantity or the failure. */
    public record Result(BigDecimal quantityBase, Failure failure) {

        public boolean ok() {
            return failure == null;
        }
    }

    /** Scale of stored quantities (numeric(18,6)). */
    public static final int QUANTITY_SCALE = 6;

    private UomConversion() {}

    /**
     * @param productFactors product-specific conversions: unit → base units per unit
     */
    public static Result toBase(BigDecimal quantity, Unit unit, Unit base, Map<UUID, BigDecimal> productFactors) {
        if (quantity.stripTrailingZeros().scale() > unit.roundingScale()) {
            return new Result(null, Failure.TOO_PRECISE_FOR_UNIT);
        }
        Optional<BigDecimal> factor = factor(unit, base, productFactors);
        if (factor.isEmpty()) {
            return new Result(null, Failure.NOT_CONVERTIBLE);
        }
        BigDecimal converted = quantity.multiply(factor.get())
                .setScale(Math.min(base.roundingScale(), QUANTITY_SCALE), RoundingMode.HALF_UP);
        if (converted.signum() <= 0) {
            return new Result(null, Failure.ZERO_IN_BASE_UNIT);
        }
        return new Result(converted.setScale(QUANTITY_SCALE, RoundingMode.UNNECESSARY), null);
    }

    /** Base units per one {@code unit}, if a conversion path exists. */
    public static Optional<BigDecimal> factor(Unit unit, Unit base, Map<UUID, BigDecimal> productFactors) {
        if (unit.id().equals(base.id())) {
            return Optional.of(BigDecimal.ONE);
        }
        BigDecimal specific = productFactors.get(unit.id());
        if (specific != null) {
            return Optional.of(specific);
        }
        if (unit.categoryId().equals(base.categoryId())) {
            return Optional.of(unit.factorToReference()
                    .divide(base.factorToReference(), 24, RoundingMode.HALF_EVEN)
                    .stripTrailingZeros());
        }
        return Optional.empty();
    }
}
