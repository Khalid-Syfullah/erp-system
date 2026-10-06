package com.erp.reporting.domain;

import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * Validated parameter values of one report run, typed per {@link ParameterType}: {@link LocalDate},
 * {@link UUID}, {@link String} (enums), {@link Integer} and {@link Boolean}. Defaults are applied.
 */
public final class ReportParameters {

    private final Map<String, Object> values;

    public ReportParameters(Map<String, Object> values) {
        this.values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    public static ReportParameters empty() {
        return new ReportParameters(Map.of());
    }

    public @Nullable LocalDate date(String name) {
        return (LocalDate) values.get(name);
    }

    public LocalDate requireDate(String name) {
        return Objects.requireNonNull(date(name), name);
    }

    public @Nullable UUID id(String name) {
        return (UUID) values.get(name);
    }

    public @Nullable String text(String name) {
        return (String) values.get(name);
    }

    public String choice(String name) {
        return Objects.requireNonNull(text(name), name);
    }

    public int integer(String name) {
        return (Integer) Objects.requireNonNull(values.get(name), name);
    }

    public boolean flag(String name) {
        return Boolean.TRUE.equals(values.get(name));
    }

    public boolean has(String name) {
        return values.containsKey(name);
    }

    /** The values as strings (the effective parameters echoed in responses and stored in jobs). */
    public Map<String, String> asStrings() {
        Map<String, String> result = new LinkedHashMap<>();
        values.forEach((k, v) -> result.put(k, v.toString()));
        return result;
    }

    /** Sorted {@code name=value} pairs: part of a cursor's fingerprint. */
    public String canonical() {
        return new TreeMap<>(asStrings())
                .entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.joining("&"));
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ReportParameters p && p.values.equals(values);
    }

    @Override
    public int hashCode() {
        return values.hashCode();
    }

    @Override
    public String toString() {
        return canonical();
    }
}
