package com.erp.platform.web;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * An expected failure that maps to a problem response with a stable {@link ErrorCode}. Domain and
 * application code throw it (or a module-specific subclass); the global handler renders it.
 *
 * <p>No stack trace is captured: these are control-flow outcomes (4xx), not bugs.
 */
public class ApiException extends RuntimeException {

    private final ErrorCode errorCode;
    private final List<FieldViolation> violations;

    public ApiException(ErrorCode errorCode, @Nullable String detail, List<FieldViolation> violations) {
        super(detail == null ? errorCode.title() : detail, null, false, false);
        this.errorCode = errorCode;
        this.violations = List.copyOf(violations);
    }

    public ApiException(ErrorCode errorCode, @Nullable String detail) {
        this(errorCode, detail, List.of());
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public List<FieldViolation> violations() {
        return violations;
    }

    public static ApiException notFound() {
        return new ApiException(PlatformErrorCode.NOT_FOUND, "The requested resource was not found.");
    }

    public static ApiException badRequest(String detail, List<FieldViolation> violations) {
        return new ApiException(PlatformErrorCode.BAD_REQUEST, detail, violations);
    }

    public static ApiException validationFailed(String detail, List<FieldViolation> violations) {
        return new ApiException(PlatformErrorCode.VALIDATION_FAILED, detail, violations);
    }
}
