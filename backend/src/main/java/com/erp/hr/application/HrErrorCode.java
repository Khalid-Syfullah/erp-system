package com.erp.hr.application;

import com.erp.platform.web.ErrorCode;
import org.springframework.http.HttpStatus;

/** Error codes of the HR module (API.md §6.1). */
public enum HrErrorCode implements ErrorCode {
    ASSIGNMENT_OVERLAP(HttpStatus.CONFLICT, "Employment assignments overlap"),
    DEPARTMENT_HEAD_OVERLAP(HttpStatus.CONFLICT, "Department heads overlap"),
    DUPLICATE_WORK_EMAIL(HttpStatus.CONFLICT, "Work email already in use");

    private final HttpStatus status;
    private final String title;

    HrErrorCode(HttpStatus status, String title) {
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
