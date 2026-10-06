package com.erp.reporting.application;

import com.erp.platform.web.ApiException;
import com.erp.platform.web.FieldViolation;
import com.erp.reporting.domain.ReportColumn;
import com.erp.reporting.domain.ReportDefinition;
import com.erp.reporting.domain.ReportParameter;
import com.erp.reporting.domain.ReportParameters;
import com.erp.reporting.domain.ReportSort;
import com.erp.reporting.domain.ReportSourceKind;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;

/**
 * Validates report parameters against the report's definition (API.md §8.1, §17.11): unknown or
 * repeated parameters, required ones, types, allowed values and ranges, and date ranges (from ≤ to,
 * at most {@value #MAX_RANGE_DAYS} days). All problems are reported at once. Query strings fail with
 * 400 per parameter; JSON bodies (exports, saved reports) with 422 per pointer.
 */
@Component
public class ReportParameterParser {

    public static final int MAX_RANGE_DAYS = 3660;
    static final String LIMIT = "limit";
    static final String CURSOR = "cursor";
    static final String SORT = "sort";

    /** A validated synchronous report request. */
    public record Request(
            ReportParameters parameters,
            int limit,
            @Nullable String cursor,
            List<ReportSort> sort) {}

    private record Problem(String name, String code, String message) {}

    /** The query string of {@code GET {c}/reports/{code}}: parameters plus limit, cursor and sort. */
    public Request parseQuery(
            ReportDefinition definition,
            MultiValueMap<String, String> query,
            LocalDate today,
            int defaultLimit,
            int maxLimit) {
        List<Problem> problems = new ArrayList<>();
        Map<String, String> values = new LinkedHashMap<>();
        int limit = defaultLimit;
        String cursor = null;
        List<ReportSort> sort = definition.defaultSort();
        for (Map.Entry<String, List<String>> entry : query.entrySet()) {
            String name = entry.getKey();
            if (entry.getValue().size() != 1) {
                problems.add(new Problem(name, "DUPLICATE_PARAMETER", "must be given once"));
                continue;
            }
            String value = entry.getValue().getFirst();
            switch (name) {
                case LIMIT -> {
                    try {
                        limit = Integer.parseInt(value);
                        if (limit < 1 || limit > maxLimit) {
                            problems.add(new Problem(name, "OUT_OF_RANGE", "must be between 1 and " + maxLimit));
                        }
                    } catch (NumberFormatException e) {
                        problems.add(new Problem(name, "INVALID_VALUE", "must be an integer"));
                    }
                }
                case CURSOR -> cursor = value;
                case SORT -> sort = sort(definition, value, problems);
                default -> values.put(name, value);
            }
        }
        ReportParameters parameters = validate(definition, values, today, problems);
        if (!problems.isEmpty()) {
            throw ApiException.badRequest(
                    "The report parameters are invalid.",
                    problems.stream()
                            .map(p -> FieldViolation.atParameter(p.name(), p.code(), p.message()))
                            .toList());
        }
        return new Request(parameters, limit, cursor, sort);
    }

    /** Parameters given as a JSON object (exports, saved reports), reported at {@code pointer/<name>}. */
    public ReportParameters parseBody(
            ReportDefinition definition, Map<String, String> values, LocalDate today, String pointer) {
        List<Problem> problems = new ArrayList<>();
        ReportParameters parameters = validate(definition, values, today, problems);
        if (!problems.isEmpty()) {
            throw ApiException.validationFailed(
                    "The report parameters are invalid.",
                    problems.stream()
                            .map(p -> FieldViolation.atPointer(pointer + "/" + p.name(), p.code(), p.message()))
                            .toList());
        }
        return parameters;
    }

    private static List<ReportSort> sort(ReportDefinition definition, String value, List<Problem> problems) {
        List<ReportSort> sort = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String part : value.split(",", -1)) {
            boolean descending = part.startsWith("-");
            String key = descending ? part.substring(1) : part;
            boolean sortable = definition.source() == ReportSourceKind.VIEWS
                    && definition.column(key).map(ReportColumn::sortable).orElse(false);
            if (!sortable) {
                problems.add(new Problem(SORT, "UNSUPPORTED_SORT", "cannot sort by '" + key + "'"));
            } else if (!seen.add(key)) {
                problems.add(new Problem(SORT, "DUPLICATE_SORT", "sorts by '" + key + "' twice"));
            } else {
                sort.add(new ReportSort(key, descending));
            }
        }
        return sort;
    }

    private static ReportParameters validate(
            ReportDefinition definition, Map<String, String> values, LocalDate today, List<Problem> problems) {
        Map<String, Object> parsed = new LinkedHashMap<>();
        for (String name : values.keySet()) {
            if (definition.parameter(name).isEmpty()) {
                problems.add(new Problem(name, "UNKNOWN_PARAMETER", "is not a parameter of " + definition.code()));
            }
        }
        for (ReportParameter parameter : definition.parameters()) {
            String raw = values.get(parameter.name());
            if (raw == null || raw.isBlank()) {
                if (raw != null) {
                    problems.add(new Problem(parameter.name(), "INVALID_VALUE", "must not be blank"));
                } else if (parameter.required()) {
                    problems.add(new Problem(parameter.name(), "REQUIRED", "is required"));
                } else if (parameter.defaultValue() != null) {
                    parsed.put(parameter.name(), defaultValue(parameter, today));
                }
                continue;
            }
            Object value = value(parameter, raw.strip(), problems);
            if (value != null) {
                parsed.put(parameter.name(), value);
            }
        }
        range(parsed, "from", "to", problems);
        range(parsed, "compareFrom", "compareTo", problems);
        if (parsed.containsKey("compareFrom") != parsed.containsKey("compareTo")) {
            problems.add(new Problem(
                    parsed.containsKey("compareFrom") ? "compareTo" : "compareFrom",
                    "REQUIRED",
                    "a comparison period needs both compareFrom and compareTo"));
        }
        return new ReportParameters(parsed);
    }

    private static Object defaultValue(ReportParameter parameter, LocalDate today) {
        String value = parameter.defaultValue();
        return switch (parameter.type()) {
            case DATE -> today;
            case INTEGER -> Integer.parseInt(value);
            case BOOLEAN -> Boolean.parseBoolean(value);
            default -> value;
        };
    }

    private static @Nullable Object value(ReportParameter parameter, String raw, List<Problem> problems) {
        String name = parameter.name();
        switch (parameter.type()) {
            case DATE -> {
                try {
                    return LocalDate.parse(raw);
                } catch (DateTimeParseException e) {
                    problems.add(new Problem(name, "INVALID_VALUE", "must be a date (yyyy-MM-dd)"));
                }
            }
            case ID -> {
                try {
                    return UUID.fromString(raw);
                } catch (IllegalArgumentException e) {
                    problems.add(new Problem(name, "INVALID_VALUE", "must be a UUID"));
                }
            }
            case ENUM -> {
                if (parameter.values().contains(raw)) {
                    return raw;
                }
                problems.add(new Problem(name, "INVALID_VALUE", "must be one of " + parameter.values()));
            }
            case INTEGER -> {
                try {
                    int value = Integer.parseInt(raw);
                    if (value < parameter.min() || value > parameter.max()) {
                        problems.add(new Problem(
                                name,
                                "OUT_OF_RANGE",
                                "must be between " + parameter.min() + " and " + parameter.max()));
                        return null;
                    }
                    return value;
                } catch (NumberFormatException e) {
                    problems.add(new Problem(name, "INVALID_VALUE", "must be an integer"));
                }
            }
            case BOOLEAN -> {
                if (raw.equals("true") || raw.equals("false")) {
                    return Boolean.parseBoolean(raw);
                }
                problems.add(new Problem(name, "INVALID_VALUE", "must be true or false"));
            }
        }
        return null;
    }

    private static void range(Map<String, Object> parsed, String fromName, String toName, List<Problem> problems) {
        if (parsed.get(fromName) instanceof LocalDate from && parsed.get(toName) instanceof LocalDate to) {
            if (to.isBefore(from)) {
                problems.add(new Problem(toName, "OUT_OF_RANGE", "must not be before " + fromName));
            } else if (ChronoUnit.DAYS.between(from, to) > MAX_RANGE_DAYS) {
                problems.add(
                        new Problem(toName, "OUT_OF_RANGE", "the range may span at most " + MAX_RANGE_DAYS + " days"));
            }
        }
    }
}
