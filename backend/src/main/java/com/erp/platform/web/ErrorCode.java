package com.erp.platform.web;

import org.springframework.http.HttpStatus;

/**
 * A stable, documented error code (API.md §6.1). The platform defines generic codes in
 * {@link PlatformErrorCode}; business modules define their own enums for domain errors.
 */
public interface ErrorCode {

    /** UPPER_SNAKE_CASE identifier that clients branch on. */
    String code();

    HttpStatus status();

    /** Short, human-readable summary of the problem type. */
    String title();

    /** RFC 9457 problem type URI derived from the code, e.g. {@code urn:erp:problem:not-found}. */
    default String type() {
        return "urn:erp:problem:" + code().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
    }
}
