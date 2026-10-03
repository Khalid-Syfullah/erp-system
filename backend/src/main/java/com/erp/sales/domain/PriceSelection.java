package com.erp.sales.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * SAL-1 within one price list: of the items for the variant and unit valid on the date, the one with
 * the highest {@code min_quantity} not above the ordered quantity wins (a dated item beats an
 * undated one at the same tier).
 */
public final class PriceSelection {

    public record Item(
            BigDecimal minQuantity,
            BigDecimal unitPrice,
            @Nullable LocalDate validFrom,
            @Nullable LocalDate validTo) {

        boolean validOn(LocalDate date) {
            return (validFrom == null || !date.isBefore(validFrom)) && (validTo == null || !date.isAfter(validTo));
        }
    }

    private PriceSelection() {}

    public static Optional<Item> best(List<Item> items, BigDecimal quantity, LocalDate date) {
        return items.stream()
                .filter(i -> i.validOn(date) && i.minQuantity().compareTo(quantity) <= 0)
                .max(Comparator.comparing(Item::minQuantity)
                        .thenComparing(i -> i.validFrom() == null ? LocalDate.MIN : i.validFrom()));
    }
}
