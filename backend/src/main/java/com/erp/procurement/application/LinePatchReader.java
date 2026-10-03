package com.erp.procurement.application;

import com.erp.platform.web.ApiException;
import com.erp.platform.web.FieldViolation;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * Reads the {@code lines} array of a document merge patch (arrays are replaced whole, API.md §4),
 * as strictly as request bodies are read: decimals as strings, no unknown members, every problem
 * reported with its JSON pointer.
 */
final class LinePatchReader {

    enum Kind {
        UUID,
        DECIMAL,
        TEXT,
        DATE
    }

    record Member(String name, Kind kind, boolean required, int maxLength) {

        static Member uuid(String name, boolean required) {
            return new Member(name, Kind.UUID, required, 0);
        }

        static Member decimal(String name, boolean required) {
            return new Member(name, Kind.DECIMAL, required, 0);
        }

        static Member text(String name, int maxLength) {
            return new Member(name, Kind.TEXT, false, maxLength);
        }
    }

    /** One line's values by member name (absent and null are both {@code null}). */
    record Values(Map<String, @Nullable Object> values) {

        @Nullable UUID uuid(String name) {
            return (UUID) values.get(name);
        }

        @Nullable BigDecimal decimal(String name) {
            return (BigDecimal) values.get(name);
        }

        @Nullable String text(String name) {
            return (String) values.get(name);
        }
    }

    private static final Pattern DECIMAL = Pattern.compile("^(0|[1-9][0-9]{0,12})(\\.[0-9]{1,6})?$");

    private LinePatchReader() {}

    static List<Values> read(@Nullable JsonNode array, List<Member> members) {
        if (array == null || !array.isArray()) {
            throw ApiException.validationFailed(
                    "The lines are invalid.",
                    List.of(FieldViolation.atPointer("/lines", "INVALID_VALUE", "must be an array")));
        }
        Map<String, Member> byName = new HashMap<>();
        members.forEach(m -> byName.put(m.name(), m));
        List<FieldViolation> violations = new ArrayList<>();
        List<Values> lines = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            JsonNode node = array.get(i);
            String at = "/lines/" + i;
            if (!node.isObject()) {
                violations.add(FieldViolation.atPointer(at, "INVALID_VALUE", "must be an object"));
                continue;
            }
            node.propertyNames().forEach(name -> {
                if (!byName.containsKey(name)) {
                    violations.add(FieldViolation.atPointer(
                            at + "/" + name, "UNKNOWN_PROPERTY", "is not a recognized property"));
                }
            });
            Map<String, @Nullable Object> values = new HashMap<>();
            for (Member member : members) {
                values.put(member.name(), value(node.get(member.name()), member, at, violations));
            }
            lines.add(new Values(values));
        }
        if (!violations.isEmpty()) {
            throw ApiException.validationFailed("The lines are invalid.", violations);
        }
        return lines;
    }

    private static @Nullable Object value(
            @Nullable JsonNode value, Member member, String at, List<FieldViolation> violations) {
        String pointer = at + "/" + member.name();
        if (value == null || value.isNull()) {
            if (member.required()) {
                violations.add(FieldViolation.atPointer(pointer, "NOT_NULL", "must not be null"));
            }
            return null;
        }
        if (!value.isString()) {
            violations.add(FieldViolation.atPointer(pointer, "INVALID_VALUE", "must be a string"));
            return null;
        }
        String text = value.stringValue();
        return switch (member.kind()) {
            case UUID -> {
                try {
                    yield java.util.UUID.fromString(text);
                } catch (IllegalArgumentException e) {
                    violations.add(FieldViolation.atPointer(pointer, "INVALID_VALUE", "must be a UUID"));
                    yield null;
                }
            }
            case DECIMAL -> {
                if (!DECIMAL.matcher(text).matches()) {
                    violations.add(FieldViolation.atPointer(
                            pointer, "INVALID_VALUE", "must be a decimal string with at most 6 decimal places"));
                    yield null;
                }
                yield new BigDecimal(text);
            }
            case TEXT -> {
                String trimmed = text.strip();
                if (trimmed.length() > member.maxLength()) {
                    violations.add(FieldViolation.atPointer(
                            pointer, "TOO_LONG", "must be at most " + member.maxLength() + " characters"));
                    yield null;
                }
                yield trimmed.isEmpty() ? null : trimmed;
            }
            case DATE -> {
                try {
                    yield LocalDate.parse(text);
                } catch (java.time.format.DateTimeParseException e) {
                    violations.add(FieldViolation.atPointer(pointer, "INVALID_VALUE", "must be a date"));
                    yield null;
                }
            }
        };
    }
}
