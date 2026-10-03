package com.erp.platform.web;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
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
