package com.erp.hr.domain;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Working days (PRODUCT_SPEC.md §10.1): every date in a range except the company's weekend days and
 * the public holidays that apply. Both ends are inclusive.
 */
public final class WorkCalendar {

    private final Set<DayOfWeek> weekend;
    private final Set<LocalDate> holidays;

    public WorkCalendar(Set<DayOfWeek> weekend, Set<LocalDate> holidays) {
        this.weekend = Set.copyOf(weekend);
        this.holidays = Set.copyOf(holidays);
    }

    public boolean isWorkingDay(LocalDate date) {
        return !weekend.contains(date.getDayOfWeek()) && !holidays.contains(date);
    }

    public List<LocalDate> workingDates(LocalDate from, LocalDate to) {
        List<LocalDate> dates = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            if (isWorkingDay(d)) {
                dates.add(d);
            }
        }
        return dates;
    }

    public int workingDays(LocalDate from, LocalDate to) {
        return from.isAfter(to) ? 0 : workingDates(from, to).size();
    }

    /** ISO day numbers (1 = Monday) as days of the week. */
    public static Set<DayOfWeek> weekendOf(Short[] isoDays) {
        java.util.EnumSet<DayOfWeek> days = java.util.EnumSet.noneOf(DayOfWeek.class);
        for (Short day : isoDays) {
            days.add(DayOfWeek.of(day));
        }
        return days;
    }
}
