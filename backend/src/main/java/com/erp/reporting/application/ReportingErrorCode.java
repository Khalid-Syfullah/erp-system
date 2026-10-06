package com.erp.reporting.application;

import com.erp.platform.web.ErrorCode;
import org.springframework.http.HttpStatus;

/** Error codes of the Reporting module (API.md §6.1, §17.11). */
public enum ReportingErrorCode implements ErrorCode {
    DUPLICATE_SAVED_REPORT(HttpStatus.CONFLICT, "A saved report with this name exists"),
    EXPORT_NOT_READY(HttpStatus.CONFLICT, "The export has not completed"),
    EXPORT_EXPIRED(HttpStatus.GONE, "The export has expired"),
    EXPORT_FAILED(HttpStatus.CONFLICT, "The export failed");

    private final HttpStatus status;
    private final String title;

    ReportingErrorCode(HttpStatus status, String title) {
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
}
