package com.erp.org.api;

import java.util.UUID;

/** Minimal company data for other modules. */
public record CompanySummary(UUID id, String code, String displayName, String baseCurrency, boolean active) {}
