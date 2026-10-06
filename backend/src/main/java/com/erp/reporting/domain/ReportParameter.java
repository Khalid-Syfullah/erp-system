package com.erp.reporting.domain;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A report parameter (filters, dates, grouping).
 *
 * @param defaultValue applied when absent; {@code "today"} for dates means the company's business date
 * @param values the allowed values of an ENUM parameter
 */
public record ReportParameter(
        String name,
        ParameterType type,
        boolean required,
        @Nullable String defaultValue,
        List<String> values,
        int min,
        int max,
        String description) {

    public ReportParameter {
        values = List.copyOf(values);
    }

    public static ReportParameter date(String name, boolean required, String description) {
        return new ReportParameter(name, ParameterType.DATE, required, null, List.of(), 0, 0, description);
    }

    /** A date that defaults to the company's business date. */
    public static ReportParameter dateDefaultToday(String name, String description) {
        return new ReportParameter(name, ParameterType.DATE, false, "today", List.of(), 0, 0, description);
    }

    public static ReportParameter id(String name, String description) {
        return new ReportParameter(name, ParameterType.ID, false, null, List.of(), 0, 0, description);
    }

    public static ReportParameter requiredId(String name, String description) {
        return new ReportParameter(name, ParameterType.ID, true, null, List.of(), 0, 0, description);
    }

    public static ReportParameter choice(
            String name, @Nullable String defaultValue, List<String> values, String description) {
        return new ReportParameter(name, ParameterType.ENUM, false, defaultValue, values, 0, 0, description);
    }

    public static ReportParameter integer(String name, int defaultValue, int min, int max, String description) {
        return new ReportParameter(
                name, ParameterType.INTEGER, false, Integer.toString(defaultValue), List.of(), min, max, description);
    }

    public static ReportParameter flag(String name, String description) {
        return new ReportParameter(name, ParameterType.BOOLEAN, false, "false", List.of(), 0, 0, description);
    }
}
