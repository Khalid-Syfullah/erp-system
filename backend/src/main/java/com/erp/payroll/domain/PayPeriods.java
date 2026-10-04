package com.erp.payroll.domain;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Payroll periods of a schedule for a calendar year (PRODUCT_SPEC.md §11.1): MONTHLY are calendar
 * months, SEMI_MONTHLY the 1st–15th and 16th–last, WEEKLY and BIWEEKLY consecutive 7- or 14-day
 * periods from the schedule's anchor date (those starting in the year). The pay date is the period
 * end plus the schedule's offset.
 */
public final class PayPeriods {

    /** A period's dates. */
    public record Period(LocalDate start, LocalDate end, LocalDate payDate) {}

    private PayPeriods() {}

    public static List<Period> forYear(String frequency, @Nullable LocalDate anchor, int year, int payDayOffset) {
        List<Period> periods = new ArrayList<>();
        switch (frequency) {
            case "MONTHLY" -> {
                for (int m = 1; m <= 12; m++) {
                    LocalDate start = LocalDate.of(year, m, 1);
                    periods.add(period(start, start.plusMonths(1).minusDays(1), payDayOffset));
                }
            }
            case "SEMI_MONTHLY" -> {
                for (int m = 1; m <= 12; m++) {
                    LocalDate start = LocalDate.of(year, m, 1);
                    periods.add(period(start, start.withDayOfMonth(15), payDayOffset));
                    periods.add(
                            period(start.withDayOfMonth(16), start.plusMonths(1).minusDays(1), payDayOffset));
                }
            }
            case "WEEKLY", "BIWEEKLY" -> {
                if (anchor == null) {
                    throw new IllegalArgumentException(frequency + " schedules need an anchor date");
                }
                int length = frequency.equals("WEEKLY") ? 7 : 14;
                LocalDate yearStart = LocalDate.of(year, 1, 1);
                long steps = Math.floorDiv(ChronoUnit.DAYS.between(anchor, yearStart), length);
                LocalDate start = anchor.plusDays(steps * length);
                if (start.isBefore(yearStart)) {
                    start = start.plusDays(length);
                }
                for (; start.getYear() == year; start = start.plusDays(length)) {
                    periods.add(period(start, start.plusDays(length - 1L), payDayOffset));
                }
            }
            default -> throw new IllegalArgumentException("Unknown frequency " + frequency);
        }
        return periods;
    }

    private static Period period(LocalDate start, LocalDate end, int offset) {
        return new Period(start, end, end.plusDays(offset));
    }
}
