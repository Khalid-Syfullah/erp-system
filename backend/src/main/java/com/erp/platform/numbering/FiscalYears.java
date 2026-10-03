package com.erp.platform.numbering;

import java.time.LocalDate;

/**
 * Fiscal year labels for numbering scopes (ADR-012, ADR-035): a fiscal year is named after the
 * calendar year in which it starts. With a start month of 1 that is the calendar year; with April,
 * 2026-04-01 … 2027-03-31 is "2026". Accounting's fiscal years (Phase 8) use the same convention.
 */
public final class FiscalYears {

    private FiscalYears() {}

    public static String label(LocalDate date, int startMonth) {
        if (startMonth < 1 || startMonth > 12) {
            throw new IllegalArgumentException("startMonth must be between 1 and 12");
        }
        int year = date.getMonthValue() >= startMonth ? date.getYear() : date.getYear() - 1;
        return Integer.toString(year);
    }
}
