package com.erp.auth.application;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** An API token's metadata; the secret is never stored or returned after creation. */
public record ApiTokenInfo(
        UUID id,
        UUID userId,
        String name,
        String prefix,
        @Nullable UUID companyId,
        @Nullable List<String> allowedPermissions,
        @Nullable Integer rateLimitPerMinute,
        OffsetDateTime expiresAt,
        @Nullable OffsetDateTime lastUsedAt,
        @Nullable OffsetDateTime revokedAt,
        OffsetDateTime createdAt) {}
