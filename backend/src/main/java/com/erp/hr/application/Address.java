package com.erp.hr.application;

import org.jspecify.annotations.Nullable;

/** A postal address (stored as JSON on the employee). */
public record Address(
        @Nullable String line1,
        @Nullable String line2,
        @Nullable String city,
        @Nullable String region,
        @Nullable String postalCode,
        @Nullable String countryCode) {}
