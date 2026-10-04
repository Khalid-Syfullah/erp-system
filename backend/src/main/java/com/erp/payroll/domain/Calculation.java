package com.erp.payroll.domain;

/**
 * How a component's amount is computed (PRODUCT_SPEC.md §11.1). Rates are percentages, except for
 * {@code INPUT}, where the rate is the amount per unit of quantity.
 */
public enum Calculation {
    FIXED,
    PERCENT_OF_BASE,
    PERCENT_OF_GROSS,
    INPUT,
    STATUTORY;

    /** Earnings make up the gross, so they cannot be computed from it (or by a statutory rule). */
    public boolean allowedFor(ComponentKind kind) {
        return kind != ComponentKind.EARNING || this == FIXED || this == PERCENT_OF_BASE || this == INPUT;
    }
}
