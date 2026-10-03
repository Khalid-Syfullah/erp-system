package com.erp.org.domain;

import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;

/**
 * How payment terms count the days to the due date (DATABASE.md §5.2, {@code org.payment_terms}).
 *
 * <ul>
 *   <li>{@code DOCUMENT_DATE}: document date + due days ("net 30").
 *   <li>{@code END_OF_MONTH}: last day of the document's month + due days ("30 days end of month";
 *       0 days means the end of the month).
 * </ul>
 */
public enum DueDateBasis {
    DOCUMENT_DATE,
    END_OF_MONTH;

    public LocalDate dueDate(LocalDate documentDate, int dueDays) {
        if (dueDays < 0) {
            throw new IllegalArgumentException("dueDays must not be negative");
        }
        LocalDate start = this == END_OF_MONTH ? documentDate.with(TemporalAdjusters.lastDayOfMonth()) : documentDate;
        return start.plusDays(dueDays);
    }
}
