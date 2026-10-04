package com.erp.payroll.domain.statutory;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The flat-percentage example rule (PRODUCT_SPEC.md §11.1): {@code rate} % of the taxable gross,
 * limited to {@code amount} when the component has one (a ceiling, prorated with the period).
 */
public final class FlatPercentageRule implements StatutoryRule {

    public static final String CODE = "FLAT_PERCENT";
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public String description() {
        return "Rate % of the taxable gross, capped at the component amount (prorated) when set.";
    }

    @Override
    public BigDecimal calculate(Context context) {
        if (context.rate() == null || context.rate().signum() <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal value = context.taxableGross().multiply(context.rate()).divide(HUNDRED, 10, RoundingMode.HALF_UP);
        if (context.amount() != null) {
            BigDecimal cap = context.amount().multiply(context.prorationFactor());
            value = value.min(cap);
        }
        return value.max(BigDecimal.ZERO);
    }
}
