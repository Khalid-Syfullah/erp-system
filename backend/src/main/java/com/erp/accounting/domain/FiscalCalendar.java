package com.erp.accounting.domain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * The fiscal calendar (PRODUCT_SPEC.md §8.1, Q-11 default): a fiscal year of twelve monthly periods
 * starting on the first day of the company's start month, named after the calendar year it starts
 * in.
 */
public final class FiscalCalendar {

    /** A period's number and dates. */
    public record Period(int number, LocalDate start, LocalDate end) {}

    private FiscalCalendar() {}

    /** The first day of the fiscal year containing {@code date}. */
    public static LocalDate yearStart(LocalDate date, int startMonth) {
        if (startMonth < 1 || startMonth > 12) {
            throw new IllegalArgumentException("startMonth must be between 1 and 12");
        }
        int year = date.getMonthValue() >= startMonth ? date.getYear() : date.getYear() - 1;
        return LocalDate.of(year, startMonth, 1);
    }

    public static LocalDate yearEnd(LocalDate yearStart) {
        return yearStart.plusYears(1).minusDays(1);
    }

    public static String code(LocalDate yearStart) {
        return Integer.toString(yearStart.getYear());
    }

    public static List<Period> periods(LocalDate yearStart) {
        List<Period> periods = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            LocalDate start = yearStart.plusMonths(i);
            periods.add(new Period(i + 1, start, start.plusMonths(1).minusDays(1)));
        }
        return periods;
    }
}
