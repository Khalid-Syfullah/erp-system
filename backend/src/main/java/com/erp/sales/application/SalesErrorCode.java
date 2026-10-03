package com.erp.sales.application;

import com.erp.platform.web.ErrorCode;
import org.springframework.http.HttpStatus;

/** Error codes of the Sales module (API.md §6.1). */
public enum SalesErrorCode implements ErrorCode {
    QUANTITY_EXCEEDS_REMAINING(HttpStatus.UNPROCESSABLE_CONTENT, "Quantity exceeds remaining"),
    CREDIT_LIMIT_EXCEEDED(HttpStatus.UNPROCESSABLE_CONTENT, "Credit limit exceeded"),
    PARTNER_BLOCKED(HttpStatus.UNPROCESSABLE_CONTENT, "Partner blocked"),
    PARTNER_ON_HOLD(HttpStatus.UNPROCESSABLE_CONTENT, "Partner on hold"),
    PRICE_MISSING(HttpStatus.UNPROCESSABLE_CONTENT, "Price missing"),
    EXCHANGE_RATE_MISSING(HttpStatus.UNPROCESSABLE_CONTENT, "Exchange rate missing");

    private final HttpStatus status;
    private final String title;

    SalesErrorCode(HttpStatus status, String title) {
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
