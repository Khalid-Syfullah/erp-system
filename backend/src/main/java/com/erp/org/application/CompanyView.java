package com.erp.org.application;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** A company as exposed by the Organization module. */
public record CompanyView(
        UUID id,
        String code,
        String legalName,
        String displayName,
        @Nullable String taxRegistrationNo,
        @Nullable String registrationNo,
        String countryCode,
        String baseCurrency,
        String timezone,
        int fiscalYearStartMonth,
        @Nullable String addressLine1,
        @Nullable String addressLine2,
        @Nullable String city,
        @Nullable String region,
        @Nullable String postalCode,
        String roundingMode,
        String taxRounding,
        String status,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        int version) {}
