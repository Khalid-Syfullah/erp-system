package com.erp.org.api;

import java.util.UUID;

/**
 * Minimal company data for other modules. {@code timezone} (IANA) defines the company's business
 * date, e.g. which employment assignment is "current".
 */
public record CompanySummary(
        UUID id, String code, String displayName, String baseCurrency, String timezone, boolean active) {}
