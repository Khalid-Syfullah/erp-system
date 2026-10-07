package com.erp.platform.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * One problem with one input (API.md §6). Exactly one of {@code pointer} (JSON Pointer into the
 * request body, RFC 6901) or {@code parameter} (query/path parameter or header name) is set.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FieldViolation(
        @Nullable String pointer,
        @Nullable String parameter,
        String code,
        String message,
        @Nullable Map<String, Object> meta) {

    public static FieldViolation atPointer(String pointer, String code, String message) {
        return new FieldViolation(pointer, null, code, message, null);
    }

    public static FieldViolation atParameter(String parameter, String code, String message) {
        return new FieldViolation(null, parameter, code, message, null);
    }
}
