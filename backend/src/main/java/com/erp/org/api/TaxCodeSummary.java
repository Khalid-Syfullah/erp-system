package com.erp.org.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A tax code as seen by other modules (ADR-020): one rate, for SALES, PURCHASE or BOTH ({@code scope}),
 * optionally limited to a validity period.
 */
public record TaxCodeSummary(
        UUID id,
        String code,
        String scope,
        BigDecimal ratePercent,
        boolean exempt,
        @Nullable LocalDate validFrom,
        @Nullable LocalDate validTo,
        boolean active) {

    public boolean appliesToSales() {
        return !"PURCHASE".equals(scope);
    }

    public boolean appliesToPurchases() {
        return !"SALES".equals(scope);
    }

    /** Active and within its validity period on the date. */
    public boolean usableOn(LocalDate date) {
        return active
                && (validFrom == null || !date.isBefore(validFrom))
                && (validTo == null || !date.isAfter(validTo));
    }
}
