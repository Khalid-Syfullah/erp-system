package com.erp.org.api;

/** An ISO 4217 currency: its minor units decide how document amounts are rounded (G-14). */
public record CurrencyInfo(String code, int minorUnits, boolean active) {}
