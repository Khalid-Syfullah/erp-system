package com.erp.platform.web;

import org.springframework.http.HttpStatus;

/** Generic error codes shared by every module (API.md §6.1). */
public enum PlatformErrorCode implements ErrorCode {
    BAD_REQUEST(HttpStatus.BAD_REQUEST, "Bad request"),
    VALIDATION_FAILED(HttpStatus.UNPROCESSABLE_CONTENT, "Validation failed"),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "Authentication required"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "Forbidden"),
    CSRF_INVALID(HttpStatus.FORBIDDEN, "Missing or invalid CSRF token"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "Not found"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "Method not allowed"),
    NOT_ACCEPTABLE(HttpStatus.NOT_ACCEPTABLE, "Not acceptable"),
    CONFLICT(HttpStatus.CONFLICT, "Conflict"),
    DUPLICATE_CODE(HttpStatus.CONFLICT, "Duplicate code"),
    RESOURCE_IN_USE(HttpStatus.CONFLICT, "Resource in use"),
    RESOURCE_BUSY(HttpStatus.CONFLICT, "Resource busy"),
    VERSION_CONFLICT(HttpStatus.CONFLICT, "Version conflict"),
    PRECONDITION_FAILED(HttpStatus.PRECONDITION_FAILED, "Precondition failed"),
    PAYLOAD_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE, "Payload too large"),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported media type"),
    PRECONDITION_REQUIRED(HttpStatus.PRECONDITION_REQUIRED, "Precondition required"),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "Too many requests"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error"),
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Service unavailable");

    private final HttpStatus status;
    private final String title;

    PlatformErrorCode(HttpStatus status, String title) {
        this.status = status;
        this.title = title;
    }

    @Override
    public String code() {
        return name();
    }

    @Override
    public HttpStatus status() {
        return status;
    }

    @Override
    public String title() {
        return title;
    }

    /** The generic code for an HTTP status, used where no specific code is known. */
    public static PlatformErrorCode forStatus(int status) {
        return switch (status) {
            case 400 -> BAD_REQUEST;
            case 401 -> UNAUTHENTICATED;
            case 403 -> FORBIDDEN;
            case 404 -> NOT_FOUND;
            case 405 -> METHOD_NOT_ALLOWED;
            case 406 -> NOT_ACCEPTABLE;
            case 409 -> CONFLICT;
            case 412 -> PRECONDITION_FAILED;
            case 413 -> PAYLOAD_TOO_LARGE;
            case 415 -> UNSUPPORTED_MEDIA_TYPE;
            case 422 -> VALIDATION_FAILED;
            case 428 -> PRECONDITION_REQUIRED;
            case 429 -> RATE_LIMITED;
            case 503 -> SERVICE_UNAVAILABLE;
            default -> status >= 400 && status < 500 ? BAD_REQUEST : INTERNAL_ERROR;
        };
    }
}
