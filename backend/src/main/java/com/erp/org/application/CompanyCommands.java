package com.erp.org.application;

import org.jspecify.annotations.Nullable;

/** Input for creating a company (validated in {@link CompanyService}). */
public record CompanyCommands() {

    public record Create(
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
            @Nullable String postalCode) {}

    /** Complete updatable state of a company (code, country and base currency are immutable). */
    public record UpdateCompany(
            String legalName,
            String displayName,
            @Nullable String taxRegistrationNo,
            @Nullable String registrationNo,
            String timezone,
            int fiscalYearStartMonth,
            @Nullable String addressLine1,
            @Nullable String addressLine2,
            @Nullable String city,
            @Nullable String region,
            @Nullable String postalCode,
            String status) {}

    /** Complete updatable state of a branch. */
    public record UpdateBranch(
            String name,
            @Nullable String addressLine1,
            @Nullable String addressLine2,
            @Nullable String city,
            @Nullable String region,
            @Nullable String postalCode,
            @Nullable String countryCode,
            boolean active) {}

    public record CreateBranch(
            String code,
            String name,
            @Nullable String addressLine1,
            @Nullable String addressLine2,
            @Nullable String city,
            @Nullable String region,
            @Nullable String postalCode,
            @Nullable String countryCode) {}
}
