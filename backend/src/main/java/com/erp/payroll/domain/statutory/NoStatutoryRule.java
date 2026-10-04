package com.erp.payroll.domain.statutory;

import java.math.BigDecimal;

/** The "none" rule: a statutory component that is not applicable yields zero. */
public final class NoStatutoryRule implements StatutoryRule {

    public static final String CODE = "NONE";

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public String description() {
        return "Not applicable: always zero.";
    }

    @Override
    public BigDecimal calculate(Context context) {
        return BigDecimal.ZERO;
    }
}
