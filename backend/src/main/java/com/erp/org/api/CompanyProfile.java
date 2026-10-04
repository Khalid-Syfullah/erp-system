package com.erp.org.api;

import java.util.UUID;

/**
 * The company settings other modules compute with: base currency and its minor units, rounding mode
 * and tax rounding (G-14), fiscal year start (numbering scopes), timezone (business date) and country
 * (payroll statutory rules).
 *
 * @param taxRounding {@code PER_LINE} or {@code PER_DOCUMENT}
 */
public record CompanyProfile(
        UUID id,
        String baseCurrency,
        int baseCurrencyMinorUnits,
        String roundingMode,
        String taxRounding,
        int fiscalYearStartMonth,
        String timezone,
        boolean active,
        String countryCode) {}
