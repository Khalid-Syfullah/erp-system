package com.erp.org.application;

/** A currency as exposed by the Organization module. */
public record CurrencyView(String code, String name, int minorUnits, boolean active) {}
