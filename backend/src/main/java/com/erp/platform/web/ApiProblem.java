package com.erp.platform.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * RFC 9457 problem details body, extended with {@code code}, {@code requestId} and {@code errors}
 * (API.md §6). Serialized as {@code application/problem+json}.
 */
@JsonPropertyOrder({"type", "title", "status", "code", "detail", "instance", "requestId", "errors"})
public record ApiProblem(
        String type,
        String title,
        int status,
        String code,
        @Nullable String detail,
        @Nullable String instance,
        @Nullable String requestId,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<FieldViolation> errors) {

    public static final String MEDIA_TYPE = "application/problem+json";

    public ApiProblem {
        errors = errors == null ? List.of() : List.copyOf(errors);
    }

    public static ApiProblem of(
            ErrorCode code,
            @Nullable String detail,
            @Nullable String instance,
            @Nullable String requestId,
            List<FieldViolation> errors) {
        return new ApiProblem(
                code.type(), code.title(), code.status().value(), code.code(), detail, instance, requestId, errors);
    }
}
