package com.erp.procurement.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The three-way match of a supplier bill (PRC-3): for each line linked to an order, the billed
 * quantity must not exceed what is still open to bill (received − returned − already billed for
 * received stock; ordered − already billed otherwise), and the billed net unit price must be within
 * the price tolerance of the order's net unit price. Tolerances are percentages of the reference.
 */
public final class ThreeWayMatch {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /** Why a line does not match. */
    public enum Problem {
        QUANTITY,
        PRICE
    }

    /**
     * @param openQuantityBase quantity still open to bill (base units)
     * @param orderUnitPrice the order's net unit price per base unit, or {@code null} without an order
     * @param billUnitPrice the bill's net unit price per base unit (same currency)
     */
    public record Line(
            int index,
            BigDecimal billedQuantityBase,
            BigDecimal openQuantityBase,
            @Nullable BigDecimal orderUnitPrice,
            BigDecimal billUnitPrice) {}

    public record Issue(int index, Problem problem, BigDecimal expected, BigDecimal actual) {}

    private ThreeWayMatch() {}

    public static List<Issue> check(
            List<Line> lines, BigDecimal qtyTolerancePercent, BigDecimal priceTolerancePercent) {
        List<Issue> issues = new ArrayList<>();
        for (Line line : lines) {
            BigDecimal allowed = withTolerance(line.openQuantityBase(), qtyTolerancePercent);
            if (line.billedQuantityBase().compareTo(allowed) > 0) {
                issues.add(
                        new Issue(line.index(), Problem.QUANTITY, line.openQuantityBase(), line.billedQuantityBase()));
            }
            BigDecimal reference = line.orderUnitPrice();
            if (reference != null) {
                BigDecimal difference = line.billUnitPrice().subtract(reference).abs();
                BigDecimal limit = reference.multiply(priceTolerancePercent).divide(HUNDRED, 10, RoundingMode.HALF_UP);
                if (difference.compareTo(limit) > 0) {
                    issues.add(new Issue(line.index(), Problem.PRICE, reference, line.billUnitPrice()));
                }
            }
        }
        return issues;
    }

    /** {@code quantity × (1 + percent / 100)}. */
    public static BigDecimal withTolerance(BigDecimal quantity, BigDecimal percent) {
        return quantity.add(quantity.multiply(percent).divide(HUNDRED, 10, RoundingMode.HALF_UP));
    }
}
