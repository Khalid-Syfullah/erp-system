package com.erp.procurement.application;

import com.erp.platform.web.ErrorCode;
import org.springframework.http.HttpStatus;

/** Error codes of the Procurement module (API.md §6.1). */
public enum ProcurementErrorCode implements ErrorCode {
    QUANTITY_EXCEEDS_REMAINING(HttpStatus.UNPROCESSABLE_CONTENT, "Quantity exceeds remaining"),
    MATCH_EXCEPTION(HttpStatus.UNPROCESSABLE_CONTENT, "Three-way match failed"),
    PARTNER_BLOCKED(HttpStatus.UNPROCESSABLE_CONTENT, "Partner blocked"),
    EXCHANGE_RATE_MISSING(HttpStatus.UNPROCESSABLE_CONTENT, "Exchange rate missing"),
    DEBIT_NOTE_EXCEEDS_BILL(HttpStatus.UNPROCESSABLE_CONTENT, "Debit note exceeds bill"),
    DUPLICATE_SUPPLIER_INVOICE(HttpStatus.CONFLICT, "Duplicate supplier invoice");

    private final HttpStatus status;
    private final String title;

    ProcurementErrorCode(HttpStatus status, String title) {
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
