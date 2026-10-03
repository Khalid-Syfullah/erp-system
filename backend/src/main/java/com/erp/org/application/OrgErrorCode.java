package com.erp.org.application;

import com.erp.platform.web.ErrorCode;
import org.springframework.http.HttpStatus;

/** Error codes of the Organization module (API.md §6.1). */
public enum OrgErrorCode implements ErrorCode {
    EXCHANGE_RATE_MISSING(HttpStatus.UNPROCESSABLE_CONTENT, "Exchange rate missing"),
    DUPLICATE_EXCHANGE_RATE(HttpStatus.CONFLICT, "Exchange rate already exists");

    private final HttpStatus status;
    private final String title;

    OrgErrorCode(HttpStatus status, String title) {
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
