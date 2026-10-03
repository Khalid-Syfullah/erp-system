package com.erp.sales.domain;

import java.math.BigDecimal;
import org.jspecify.annotations.Nullable;

/**
 * The credit check on confirmation (SAL-2, PRODUCT_SPEC.md §8.7): exposure = open receivables +
 * confirmed but uninvoiced orders; if exposure plus the new order exceeds the credit limit, the
 * company mode decides (NONE: ignore, WARN: confirm with a warning, BLOCK: refuse unless
 * overridden). A customer on hold is always refused unless overridden.
 */
public final class CreditCheck {

    /** Company setting {@code credit_check_mode}. */
    public enum Mode {
        NONE,
        WARN,
        BLOCK
    }

    /** What the check decides; {@code BLOCKED_*} need {@code sales.order.override_credit}. */
    public enum Outcome {
        PASSED,
        WARNED,
        BLOCKED_LIMIT,
        BLOCKED_ON_HOLD;

        public boolean blocked() {
            return this == BLOCKED_LIMIT || this == BLOCKED_ON_HOLD;
        }
    }

    private CreditCheck() {}

    /**
     * @param creditLimit in base currency; {@code null}: no limit
     * @param exposure open receivables and other open orders, base currency
     * @param orderAmount the order being confirmed, base currency
     */
    public static Outcome evaluate(
            Mode mode, @Nullable BigDecimal creditLimit, boolean onHold, BigDecimal exposure, BigDecimal orderAmount) {
        if (onHold) {
            return Outcome.BLOCKED_ON_HOLD;
        }
        if (mode == Mode.NONE || creditLimit == null) {
            return Outcome.PASSED;
        }
        if (exposure.add(orderAmount).compareTo(creditLimit) <= 0) {
            return Outcome.PASSED;
        }
        return mode == Mode.WARN ? Outcome.WARNED : Outcome.BLOCKED_LIMIT;
    }
}
