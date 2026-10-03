package com.erp.org.application;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** A branch as exposed by the Organization module. */
public record BranchView(
        UUID id,
        UUID companyId,
        String code,
        String name,
        @Nullable String addressLine1,
        @Nullable String addressLine2,
        @Nullable String city,
        @Nullable String region,
        @Nullable String postalCode,
        @Nullable String countryCode,
        boolean active,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        int version) {}
