package com.erp.org.api;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Payment terms as seen by other modules, with the due date they give for a document date.
 *
 * @param dueBasis {@code DOCUMENT_DATE} or {@code END_OF_MONTH}
 */
public record PaymentTermsSummary(UUID id, String code, String name, int dueDays, String dueBasis, boolean active) {

    public LocalDate dueDate(LocalDate documentDate) {
        LocalDate start = "END_OF_MONTH".equals(dueBasis)
                ? documentDate.with(java.time.temporal.TemporalAdjusters.lastDayOfMonth())
                : documentDate;
        return start.plusDays(dueDays);
    }
}
