package com.erp.hr.domain;

import java.time.LocalDate;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * An inclusive date range {@code [from, to]}; {@code to == null} means open-ended. Matches the
 * {@code daterange(effective_from, effective_to, '[]')} of the exclusion constraints.
 */
public record EffectivePeriod(LocalDate from, @Nullable LocalDate to) {

    public EffectivePeriod {
        if (from == null) {
            throw new IllegalArgumentException("from is required");
        }
        if (to != null && to.isBefore(from)) {
            throw new IllegalArgumentException("to must not be before from");
        }
    }

    public boolean contains(LocalDate date) {
        return !date.isBefore(from) && (to == null || !date.isAfter(to));
    }

    public boolean overlaps(EffectivePeriod other) {
        return !endsBefore(other.from) && !other.endsBefore(from);
    }

    /** The common part of both periods, if any. */
    public Optional<EffectivePeriod> intersect(EffectivePeriod other) {
        if (!overlaps(other)) {
            return Optional.empty();
        }
        LocalDate start = from.isAfter(other.from) ? from : other.from;
        LocalDate end = to == null ? other.to : other.to == null ? to : to.isBefore(other.to) ? to : other.to;
        return Optional.of(new EffectivePeriod(start, end));
    }

    /** Effective on {@code date} or later ("current or future"). */
    public boolean reachesOrPasses(LocalDate date) {
        return to == null || !to.isBefore(date);
    }

    private boolean endsBefore(LocalDate date) {
        return to != null && to.isBefore(date);
    }
}
