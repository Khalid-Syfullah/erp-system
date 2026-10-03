package com.erp.org.api;

import java.util.UUID;

/**
 * The company settings other modules compute with: base currency and its minor units, rounding mode
 * (G-14), fiscal year start (numbering scopes) and timezone (business date).
 */
public record CompanyProfile(
        UUID id,
        String baseCurrency,
        int baseCurrencyMinorUnits,
        String roundingMode,
        int fiscalYearStartMonth,
        String timezone,
        boolean active) {}
