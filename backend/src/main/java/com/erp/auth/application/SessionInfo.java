package com.erp.auth.application;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** A browser session (without its secret). */
public record SessionInfo(
        UUID id,
        UUID userId,
        OffsetDateTime createdAt,
        OffsetDateTime lastSeenAt,
        OffsetDateTime authenticatedAt,
        @Nullable OffsetDateTime reauthenticatedAt,
        OffsetDateTime absoluteExpiresAt,
        boolean mfaVerified,
        boolean mfaEnrollmentRequired,
        @Nullable String ip,
        @Nullable String userAgent) {}
