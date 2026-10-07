package com.erp.platform.web.paging;

import com.erp.platform.web.ApiException;
import com.erp.platform.web.FieldViolation;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.util.MultiValueMap;

/**
 * Parses and validates list query parameters against a {@link ListDefinition} (API.md §8). All
 * problems are collected and reported together as one 400 {@code BAD_REQUEST} with a
 * {@code parameter} per violation. Unknown parameters are rejected rather than ignored.
 */
public final class ListQueryParser {

    static final int MAX_IN_VALUES = 100;
    static final int MAX_TEXT_LENGTH = 100;
    static final int MIN_SEARCH_LENGTH = 2;

    private static final Set<String> RESERVED = Set.of("limit", "cursor", "includeTotal", "sort", "q");
    private static final Pattern FILTER = Pattern.compile("^filter\\[([A-Za-z][A-Za-z0-9]*)](?:\\[([A-Za-z]+)])?$");
    private static final Pattern INTEGER = Pattern.compile("^-?(0|[1-9][0-9]{0,17})$");
    private static final Pattern DECIMAL = Pattern.compile("^-?(0|[1-9][0-9]{0,30})(\\.[0-9]{1,12})?$");

    private final CursorCodec cursorCodec;

    public ListQueryParser(CursorCodec cursorCodec) {
        this.cursorCodec = cursorCodec;
    }

    public ListQuery parse(MultiValueMap<String, String> parameters, ListDefinition definition) {
        List<FieldViolation> violations = new ArrayList<>();
        Map<String, String> single = singleValues(parameters, violations);

        int limit = parseLimit(single.get("limit"), violations);
        boolean includeTotal = parseBoolean("includeTotal", single.get("includeTotal"), violations);
        List<SortOrder> sort = parseSort(single.get("sort"), definition, violations);
        String search = parseSearch(single.get("q"), definition, violations);

        List<FilterCriterion> filters = new ArrayList<>();
        for (Map.Entry<String, String> entry : single.entrySet()) {
            String name = entry.getKey();
            if (RESERVED.contains(name)) {
                continue;
            }
            Matcher matcher = FILTER.matcher(name);
            if (!matcher.matches()) {
                violations.add(FieldViolation.atParameter(name, "UNKNOWN_PARAMETER", "is not a supported parameter"));
                continue;
            }
            FilterCriterion criterion =
                    filter(name, matcher.group(1), matcher.group(2), entry.getValue(), definition, violations);
            if (criterion != null) {
                filters.add(criterion);
            }
        }

        if (!violations.isEmpty()) {
            throw ApiException.badRequest("One or more query parameters are invalid.", violations);
        }

        ListQuery query = new ListQuery(definition.name(), limit, sort, filters, search, null, includeTotal);
        String cursor = single.get("cursor");
        if (cursor == null) {
            return query;
        }
        List<String> after = cursorCodec
                .decode(cursor)
                .filter(position -> CursorCodec.sameFingerprint(position.fingerprint(), query.fingerprint()))
                .filter(position -> position.values().size() == sort.size() + 1)
                .map(CursorCodec.Position::values)
                .orElseThrow(() -> ApiException.badRequest(
                        "The cursor is invalid or does not belong to this query.",
                        List.of(FieldViolation.atParameter(
                                "cursor",
                                "INVALID_CURSOR",
                                "must be a nextCursor returned by this list with the same filters and sort"))));
        return new ListQuery(definition.name(), limit, sort, filters, search, after, includeTotal);
    }

    private static Map<String, String> singleValues(
            MultiValueMap<String, String> parameters, List<FieldViolation> violations) {
        Map<String, String> single = new java.util.LinkedHashMap<>();
        parameters.forEach((name, values) -> {
            if (values == null || values.size() != 1) {
                violations.add(FieldViolation.atParameter(name, "DUPLICATE_PARAMETER", "must be given exactly once"));
            } else {
                single.put(name, values.getFirst() == null ? "" : values.getFirst());
            }
        });
        return single;
    }

    private static int parseLimit(@Nullable String value, List<FieldViolation> violations) {
        if (value == null) {
            return ListDefinition.DEFAULT_LIMIT;
        }
        if (INTEGER.matcher(value).matches()) {
            long limit = Long.parseLong(value);
            if (limit >= 1 && limit <= ListDefinition.MAX_LIMIT) {
                return (int) limit;
            }
        }
        violations.add(FieldViolation.atParameter(
                "limit", "OUT_OF_RANGE", "must be an integer between 1 and " + ListDefinition.MAX_LIMIT));
        return ListDefinition.DEFAULT_LIMIT;
    }

    private static boolean parseBoolean(String name, @Nullable String value, List<FieldViolation> violations) {
        if (value == null || value.equals("false")) {
            return false;
        }
        if (value.equals("true")) {
            return true;
        }
        violations.add(FieldViolation.atParameter(name, "INVALID_VALUE", "must be true or false"));
        return false;
    }

    private static List<SortOrder> parseSort(
            @Nullable String value, ListDefinition definition, List<FieldViolation> violations) {
        if (value == null || value.isBlank()) {
            return definition.defaultSort();
        }
        List<SortOrder> orders = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String token : value.split(",", -1)) {
            boolean descending = token.startsWith("-");
            String field = descending ? token.substring(1) : token;
            if (!definition.sortableFields().contains(field)) {
                violations.add(FieldViolation.atParameter(
                        "sort",
                        "UNSUPPORTED_SORT",
                        "cannot sort by '" + truncate(field) + "'; sortable fields: " + definition.sortableFields()));
            } else if (!seen.add(field)) {
                violations.add(FieldViolation.atParameter("sort", "DUPLICATE_SORT", "lists '" + field + "' twice"));
            } else {
                orders.add(new SortOrder(field, descending));
            }
        }
        return orders;
    }

    private static @Nullable String parseSearch(
            @Nullable String value, ListDefinition definition, List<FieldViolation> violations) {
        if (value == null) {
            return null;
        }
        if (!definition.searchable()) {
            violations.add(
                    FieldViolation.atParameter("q", "UNKNOWN_PARAMETER", "search is not supported by this list"));
            return null;
        }
        // NFC: Bangla (and other scripts) typed with combining marks matches the stored NFC text.
        String trimmed = Normalizer.normalize(value.strip(), Normalizer.Form.NFC);
        if (trimmed.length() < MIN_SEARCH_LENGTH
                || trimmed.length() > MAX_TEXT_LENGTH
                || hasControlCharacter(trimmed)) {
            violations.add(FieldViolation.atParameter(
                    "q", "INVALID_VALUE", "must be " + MIN_SEARCH_LENGTH + " to " + MAX_TEXT_LENGTH + " characters"));
            return null;
        }
        return trimmed;
    }

    private static @Nullable FilterCriterion filter(
            String parameter,
            String field,
            @Nullable String operatorToken,
            String raw,
            ListDefinition definition,
            List<FieldViolation> violations) {
        FilterSpec spec = definition.filters().get(field);
        if (spec == null) {
            violations.add(FieldViolation.atParameter(
                    parameter,
                    "UNSUPPORTED_FILTER",
                    "filtering by '" + field + "' is not supported; filterable fields: "
                            + definition.filters().keySet()));
            return null;
        }
        FilterOperator operator = operatorToken == null
                ? FilterOperator.EQ
                : FilterOperator.fromToken(operatorToken).orElse(null);
        if (operator == null || !spec.operators().contains(operator)) {
            violations.add(FieldViolation.atParameter(
                    parameter,
                    "UNSUPPORTED_OPERATOR",
                    "supported operators for '" + field + "': "
                            + spec.operators().stream()
                                    .map(FilterOperator::token)
                                    .sorted()
                                    .toList()));
            return null;
        }
        int before = violations.size();
        List<Object> values = new ArrayList<>();
        switch (operator) {
            case IS_NULL -> {
                if (raw.equals("true") || raw.equals("false")) {
                    values.add(Boolean.valueOf(raw));
                } else {
                    violations.add(FieldViolation.atParameter(parameter, "INVALID_VALUE", "must be true or false"));
                }
            }
            case IN -> {
                String[] parts = raw.split(",", -1);
                if (parts.length > MAX_IN_VALUES) {
                    violations.add(FieldViolation.atParameter(
                            parameter, "TOO_MANY_VALUES", "accepts at most " + MAX_IN_VALUES + " values"));
                } else {
                    for (String part : parts) {
                        Object value = parseValue(parameter, spec, part, violations);
                        if (value != null) {
                            values.add(value);
                        }
                    }
                }
            }
            default -> {
                Object value = parseValue(parameter, spec, raw, violations);
                if (value != null) {
                    values.add(value);
                }
            }
        }
        return violations.size() == before ? new FilterCriterion(field, operator, values) : null;
    }

    private static @Nullable Object parseValue(
            String parameter, FilterSpec spec, String raw, List<FieldViolation> violations) {
        String value = Normalizer.normalize(raw.strip(), Normalizer.Form.NFC);
        Object parsed = switch (spec.type()) {
            case STRING ->
                value.isEmpty() || value.length() > MAX_TEXT_LENGTH || hasControlCharacter(value) ? null : value;
            case UUID -> parseUuid(value);
            case INTEGER -> INTEGER.matcher(value).matches() ? Long.valueOf(value) : null;
            case DECIMAL -> DECIMAL.matcher(value).matches() ? new BigDecimal(value) : null;
            case BOOLEAN -> value.equals("true") ? Boolean.TRUE : value.equals("false") ? Boolean.FALSE : null;
            case DATE -> parseDate(value);
            case ENUM -> spec.enumValues().contains(value) ? value : null;
        };
        if (parsed == null) {
            violations.add(FieldViolation.atParameter(parameter, "INVALID_VALUE", expected(spec)));
        }
        return parsed;
    }

    private static String expected(FilterSpec spec) {
        return switch (spec.type()) {
            case STRING -> "must be 1 to " + MAX_TEXT_LENGTH + " characters";
            case UUID -> "must be a UUID";
            case INTEGER -> "must be an integer";
            case DECIMAL -> "must be a plain decimal number";
            case BOOLEAN -> "must be true or false";
            case DATE -> "must be a date in the format YYYY-MM-DD";
            case ENUM -> "must be one of " + spec.enumValues().stream().sorted().toList();
        };
    }

    private static @Nullable UUID parseUuid(String value) {
        if (value.length() != 36) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static @Nullable LocalDate parseDate(String value) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static boolean hasControlCharacter(String value) {
        return value.chars().anyMatch(Character::isISOControl);
    }

    private static String truncate(String value) {
        return value.length() > 40 ? value.substring(0, 40) + "…" : value;
    }
}
