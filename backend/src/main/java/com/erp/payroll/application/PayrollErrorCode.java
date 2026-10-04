package com.erp.payroll.application;

import com.erp.platform.web.ErrorCode;
import org.springframework.http.HttpStatus;

/** Error codes of the Payroll module (API.md §6.1). */
public enum PayrollErrorCode implements ErrorCode {
    RUN_HAS_ISSUES(HttpStatus.CONFLICT, "Payroll run has issues"),
    COMPENSATION_OVERLAP(HttpStatus.CONFLICT, "Compensations overlap"),
    REGULAR_RUN_EXISTS(HttpStatus.CONFLICT, "Regular run exists"),
    MISSING_BANK_ACCOUNT(HttpStatus.UNPROCESSABLE_CONTENT, "Missing bank account"),
    PAYSLIP_NOT_RELEASED(HttpStatus.CONFLICT, "Payslip not released"),
    NOT_AN_EMPLOYEE(HttpStatus.NOT_FOUND, "No employee record");

    private final HttpStatus status;
    private final String title;

    PayrollErrorCode(HttpStatus status, String title) {
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
