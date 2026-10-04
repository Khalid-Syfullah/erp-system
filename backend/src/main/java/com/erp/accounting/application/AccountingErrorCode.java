package com.erp.accounting.application;

import com.erp.platform.web.ErrorCode;
import org.springframework.http.HttpStatus;

/** Error codes of the Accounting module (API.md §6.1). */
public enum AccountingErrorCode implements ErrorCode {
    UNBALANCED_ENTRY(HttpStatus.UNPROCESSABLE_CONTENT, "Unbalanced entry"),
    PERIOD_CLOSED(HttpStatus.UNPROCESSABLE_CONTENT, "Period closed"),
    ACCOUNT_NOT_POSTABLE(HttpStatus.UNPROCESSABLE_CONTENT, "Account not postable"),
    CONTROL_ACCOUNT_MANUAL_POSTING(HttpStatus.UNPROCESSABLE_CONTENT, "Control account manual posting"),
    ACCOUNT_MAPPING_MISSING(HttpStatus.UNPROCESSABLE_CONTENT, "Account mapping missing"),
    ALLOCATION_INVALID(HttpStatus.UNPROCESSABLE_CONTENT, "Allocation invalid"),
    EXCHANGE_RATE_MISSING(HttpStatus.UNPROCESSABLE_CONTENT, "Exchange rate missing");

    private final HttpStatus status;
    private final String title;

    AccountingErrorCode(HttpStatus status, String title) {
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
