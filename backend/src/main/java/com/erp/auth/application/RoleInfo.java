package com.erp.auth.application;

import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** A role with its permission codes. */
public record RoleInfo(
        UUID id,
        String code,
        String name,
        @Nullable String description,
        boolean system,
        boolean requiresMfa,
        Set<String> permissions,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        int version) {}
