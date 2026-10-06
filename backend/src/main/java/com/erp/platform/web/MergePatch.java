package com.erp.platform.web;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * Reads a JSON Merge Patch document (RFC 7396, API.md §4): distinguishes "absent" (unchanged) from
 * {@code null} (clear) and validates every member. Unknown members are rejected, like everywhere
 * else in the API. Violations are collected; call {@link #throwIfInvalid()} before applying.
 */
public final class MergePatch {

    /** A member of the patch: present or not, and its (possibly null) value. */
    public record Member<T>(boolean present, @Nullable T value) {

        static <T> Member<T> absent() {
            return new Member<>(false, null);
        }

        /** The new value if present, otherwise the current one. */
        public @Nullable T orElse(@Nullable T current) {
            return present ? value : current;
        }
    }

    /** Same grammar as the request-body decimal deserializer (API.md §11). */
    private static final Pattern PLAIN_DECIMAL = Pattern.compile("^-?(0|[1-9][0-9]{0,30})(\\.[0-9]{1,12})?$");

    private final JsonNode document;
    private final List<FieldViolation> violations = new ArrayList<>();

    private MergePatch(JsonNode document) {
        this.document = document;
    }

    /** @param allowedMembers the members this resource accepts; anything else is a 400 */
    public static MergePatch of(@Nullable JsonNode document, Set<String> allowedMembers) {
        if (document == null || !document.isObject()) {
            throw ApiException.badRequest("The request body must be a JSON object (merge patch).", List.of());
        }
        List<FieldViolation> unknown = new ArrayList<>();
        for (Iterator<String> names = document.propertyNames().iterator(); names.hasNext(); ) {
            String name = names.next();
            if (!allowedMembers.contains(name)) {
                unknown.add(FieldViolation.atPointer("/" + name, "UNKNOWN_PROPERTY", "is not a recognized property"));
            }
        }
        if (!unknown.isEmpty()) {
            throw ApiException.badRequest("The request body contains an unknown property.", unknown);
        }
        return new MergePatch(document);
    }

    /**
     * A text member, trimmed. {@code required} members may not be set to null or blank.
     *
     * @param maxLength maximum length after trimming
     */
    public Member<String> text(String name, boolean required, int maxLength) {
        if (!document.has(name)) {
            return Member.absent();
        }
        JsonNode node = document.get(name);
        if (node.isNull()) {
            if (required) {
                violations.add(FieldViolation.atPointer("/" + name, "NOT_NULL", "must not be null"));
            }
            return new Member<>(true, null);
        }
        if (!node.isString()) {
            violations.add(FieldViolation.atPointer("/" + name, "INVALID_VALUE", "must be a string"));
            return Member.absent();
        }
        String value = node.stringValue();
        if (containsControlCharacter(value)) {
            violations.add(
                    FieldViolation.atPointer("/" + name, "INVALID_VALUE", "must not contain control characters"));
            return Member.absent();
        }
        String trimmed = value.strip();
        if (required && trimmed.isEmpty()) {
            violations.add(FieldViolation.atPointer("/" + name, "NOT_BLANK", "must not be blank"));
        } else if (trimmed.length() > maxLength) {
            violations.add(FieldViolation.atPointer("/" + name, "SIZE", "size must be at most " + maxLength));
        }
        return new Member<>(true, trimmed.isEmpty() ? null : trimmed);
    }

    /** A text member that must satisfy {@code validator} (returns an error message or null). */
    public Member<String> text(
            String name, boolean required, int maxLength, Function<String, @Nullable String> validator) {
        Member<String> member = text(name, required, maxLength);
        if (member.present() && member.value() != null) {
            String error = validator.apply(member.value());
            if (error != null) {
                violations.add(FieldViolation.atPointer("/" + name, "INVALID_VALUE", error));
            }
        }
        return member;
    }

    public Member<Integer> integer(String name, int min, int max) {
        if (!document.has(name)) {
            return Member.absent();
        }
        JsonNode node = document.get(name);
        if (!node.isInt() || node.intValue() < min || node.intValue() > max) {
            violations.add(FieldViolation.atPointer(
                    "/" + name, "INVALID_VALUE", "must be an integer between " + min + " and " + max));
            return Member.absent();
        }
        return new Member<>(true, node.intValue());
    }

    public Member<Boolean> bool(String name) {
        if (!document.has(name)) {
            return Member.absent();
        }
        JsonNode node = document.get(name);
        if (!node.isBoolean()) {
            violations.add(FieldViolation.atPointer("/" + name, "INVALID_VALUE", "must be true or false"));
            return Member.absent();
        }
        return new Member<>(true, node.booleanValue());
    }

    /** An object member of string values (e.g. report parameters); {@code null} clears it to empty. */
    public Member<Map<String, String>> stringMap(String name, int maxEntries) {
        if (!document.has(name)) {
            return Member.absent();
        }
        JsonNode node = document.get(name);
        if (node.isNull()) {
            return new Member<>(true, Map.of());
        }
        if (!node.isObject() || node.size() > maxEntries) {
            violations.add(FieldViolation.atPointer(
                    "/" + name, "INVALID_VALUE", "must be an object of at most " + maxEntries + " string values"));
            return Member.absent();
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (Iterator<String> names = node.propertyNames().iterator(); names.hasNext(); ) {
            String key = names.next();
            JsonNode value = node.get(key);
            if (!value.isString()) {
                violations.add(FieldViolation.atPointer("/" + name + "/" + key, "INVALID_VALUE", "must be a string"));
            } else {
                values.put(key, value.stringValue());
            }
        }
        return new Member<>(true, values);
    }

    /** A reference member: a UUID string, or {@code null} to clear an optional reference. */
    public Member<UUID> uuid(String name, boolean required) {
        Member<String> member = text(name, required, 36);
        if (!member.present() || member.value() == null) {
            return member.present() ? new Member<>(true, null) : Member.absent();
        }
        try {
            return new Member<>(true, UUID.fromString(member.value()));
        } catch (IllegalArgumentException e) {
            violations.add(FieldViolation.atPointer("/" + name, "INVALID_VALUE", "must be a UUID"));
            return Member.absent();
        }
    }

    /** An ISO-8601 calendar date ({@code yyyy-MM-dd}), or {@code null} when not required. */
    public Member<LocalDate> date(String name, boolean required) {
        Member<String> member = text(name, required, 10);
        if (!member.present() || member.value() == null) {
            return member.present() ? new Member<>(true, null) : Member.absent();
        }
        try {
            return new Member<>(true, LocalDate.parse(member.value()));
        } catch (DateTimeParseException e) {
            violations.add(FieldViolation.atPointer("/" + name, "INVALID_VALUE", "must be a date (yyyy-MM-dd)"));
            return Member.absent();
        }
    }

    /**
     * A decimal carried as a JSON string in plain notation (API.md §11), never null.
     *
     * @param maxScale maximum number of fraction digits
     */
    public Member<BigDecimal> decimal(String name, BigDecimal min, BigDecimal max, int maxScale) {
        if (!document.has(name)) {
            return Member.absent();
        }
        JsonNode node = document.get(name);
        if (!node.isString()) {
            violations.add(FieldViolation.atPointer("/" + name, "INVALID_VALUE", "must be a decimal string"));
            return Member.absent();
        }
        String text = node.stringValue();
        if (!PLAIN_DECIMAL.matcher(text).matches()) {
            violations.add(FieldViolation.atPointer("/" + name, "INVALID_VALUE", "must be a plain decimal string"));
            return Member.absent();
        }
        BigDecimal value = new BigDecimal(text);
        if (value.compareTo(min) < 0
                || value.compareTo(max) > 0
                || value.stripTrailingZeros().scale() > maxScale) {
            violations.add(FieldViolation.atPointer(
                    "/" + name,
                    "INVALID_VALUE",
                    "must be between " + min.toPlainString() + " and " + max.toPlainString() + " with at most "
                            + maxScale + " decimal places"));
            return Member.absent();
        }
        return new Member<>(true, value);
    }

    /** Records a violation found by the caller's own checks (e.g. cross-field rules). */
    public void reject(String name, String code, String message) {
        violations.add(FieldViolation.atPointer("/" + name, code, message));
    }

    public void throwIfInvalid() {
        if (!violations.isEmpty()) {
            throw ApiException.validationFailed("The patch is invalid.", violations);
        }
    }

    private static boolean containsControlCharacter(String value) {
        return value.chars()
                .anyMatch(c -> (c < 0x20 && c != '\t' && c != '\n' && c != '\r') || (c >= 0x7F && c <= 0x9F));
    }
}
