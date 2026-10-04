package com.erp.accounting.domain;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/** Ageing buckets of open items by days past due as of a date (PRODUCT_SPEC.md §8.7). */
public enum Ageing {
    CURRENT,
    DAYS_1_30,
    DAYS_31_60,
    DAYS_61_90,
    OVER_90;

    public static Ageing of(LocalDate dueDate, LocalDate asOf) {
        long overdue = ChronoUnit.DAYS.between(dueDate, asOf);
        if (overdue <= 0) {
            return CURRENT;
        }
        if (overdue <= 30) {
            return DAYS_1_30;
        }
        if (overdue <= 60) {
            return DAYS_31_60;
        }
        return overdue <= 90 ? DAYS_61_90 : OVER_90;
    }
}
