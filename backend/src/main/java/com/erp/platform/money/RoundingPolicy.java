package com.erp.platform.money;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * How amounts of one currency are rounded (PRODUCT_SPEC.md G-14): to the currency's minor units with
 * the company's rounding mode (HALF_UP or HALF_EVEN).
 */
public record RoundingPolicy(int minorUnits, RoundingMode mode) {

    public RoundingPolicy {
        if (minorUnits < 0 || minorUnits > 4) {
            throw new IllegalArgumentException("minorUnits must be between 0 and 4");
        }
        if (mode != RoundingMode.HALF_UP && mode != RoundingMode.HALF_EVEN) {
            throw new IllegalArgumentException("Only HALF_UP and HALF_EVEN are supported");
        }
    }

    public static RoundingPolicy of(int minorUnits, String companyRoundingMode) {
        return new RoundingPolicy(minorUnits, RoundingMode.valueOf(companyRoundingMode));
    }

    public BigDecimal round(BigDecimal amount) {
        return amount.setScale(minorUnits, mode);
    }
}
