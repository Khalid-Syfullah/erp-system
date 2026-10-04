package com.erp.hr.domain;

import java.time.Duration;
import java.time.OffsetDateTime;
import org.jspecify.annotations.Nullable;

/** Daily attendance states (ADR-039). */
public enum AttendanceStatus {
    PRESENT,
    ABSENT,
    HALF_DAY,
    REMOTE,
    ON_LEAVE,
    HOLIDAY;

    /** Whether the status records time worked (check-in and check-out make sense). */
    public boolean worked() {
        return this == PRESENT || this == HALF_DAY || this == REMOTE;
    }

    /** Minutes between check-in and check-out, or null while either is missing. */
    public static @Nullable Integer workedMinutes(@Nullable OffsetDateTime checkIn, @Nullable OffsetDateTime checkOut) {
        if (checkIn == null || checkOut == null) {
            return null;
        }
        long minutes = Duration.between(checkIn, checkOut).toMinutes();
        if (minutes < 0 || minutes > 1440) {
            throw new IllegalArgumentException("Check-out must be within 24 hours after check-in");
        }
        return (int) minutes;
    }
}
