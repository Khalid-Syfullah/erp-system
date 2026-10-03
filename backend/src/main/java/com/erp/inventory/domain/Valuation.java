package com.erp.inventory.domain;

import com.erp.platform.money.RoundingPolicy;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Moving-average valuation of one variant in a company (INV-4): quantity in base units and total
 * value in base currency. Values are always rounded to the currency's minor units, so the ledger's
 * values sum exactly to the valuation (INV-5).
 */
public record Valuation(BigDecimal quantity, BigDecimal value) {

    public static final int UNIT_COST_SCALE = 6;
    public static final Valuation EMPTY = new Valuation(BigDecimal.ZERO, BigDecimal.ZERO);

    /** Whether the state satisfies the valuation invariants (no negatives, no value without quantity). */
    public boolean isValid() {
        return quantity.signum() >= 0 && value.signum() >= 0 && (quantity.signum() > 0 || value.signum() == 0);
    }

    /** {@code new_value = old_value + round(qty × unit_cost)}. */
    public Movement receive(BigDecimal qty, BigDecimal unitCost, RoundingPolicy rounding) {
        BigDecimal in = rounding.round(qty.multiply(unitCost));
        return new Movement(new Valuation(quantity.add(qty), value.add(in)), in);
    }

    /**
     * {@code value_out = round(qty × value / quantity)}, except that taking everything takes the whole
     * value, so no residue remains. The caller ensures {@code qty ≤ quantity}.
     */
    public Movement issue(BigDecimal qty, RoundingPolicy rounding) {
        BigDecimal out = valueOf(qty, rounding);
        return new Movement(new Valuation(quantity.subtract(qty), value.subtract(out)), out);
    }

    /** The value {@code qty} would carry if issued now (transfers move it without changing the valuation). */
    public BigDecimal valueOf(BigDecimal qty, RoundingPolicy rounding) {
        if (qty.signum() <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        if (qty.compareTo(quantity) > 0) {
            throw new IllegalArgumentException("cannot value more than the valued quantity");
        }
        if (qty.compareTo(quantity) == 0) {
            return value;
        }
        return rounding.round(qty.multiply(value).divide(quantity, 20, RoundingMode.HALF_EVEN));
    }

    /** Applies a fixed signed change (reversals mirror original ledger values). */
    public Valuation plus(BigDecimal qtyDelta, BigDecimal valueDelta) {
        return new Valuation(quantity.add(qtyDelta), value.add(valueDelta));
    }

    /** Current average unit cost, or {@code null} when nothing is in stock. */
    public BigDecimal averageCost() {
        return quantity.signum() == 0 ? null : value.divide(quantity, UNIT_COST_SCALE, RoundingMode.HALF_UP);
    }

    public static BigDecimal unitCost(BigDecimal value, BigDecimal qty) {
        return value.abs().divide(qty.abs(), UNIT_COST_SCALE, RoundingMode.HALF_UP);
    }

    /** A valuation change: the new state and the (positive) value that moved. */
    public record Movement(Valuation after, BigDecimal value) {}
}
