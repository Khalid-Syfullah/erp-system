package com.erp.hr.application;

import com.erp.platform.web.ErrorCode;
import org.springframework.http.HttpStatus;

/** Error codes of the HR module (API.md §6.1). */
public enum HrErrorCode implements ErrorCode {
    ASSIGNMENT_OVERLAP(HttpStatus.CONFLICT, "Employment assignments overlap"),
    DEPARTMENT_HEAD_OVERLAP(HttpStatus.CONFLICT, "Department heads overlap"),
    DUPLICATE_WORK_EMAIL(HttpStatus.CONFLICT, "Work email already in use"),
    USER_ALREADY_LINKED(HttpStatus.CONFLICT, "User already linked to an employee"),
    NOT_AN_EMPLOYEE(HttpStatus.NOT_FOUND, "No employee record"),
    LEAVE_OVERLAP(HttpStatus.CONFLICT, "Leave overlaps other leave"),
    LEAVE_BALANCE_INSUFFICIENT(HttpStatus.UNPROCESSABLE_CONTENT, "Insufficient leave balance"),
    NO_WORKING_DAYS(HttpStatus.UNPROCESSABLE_CONTENT, "No working days in the leave period"),
    ATTENDANCE_STATE(HttpStatus.CONFLICT, "Attendance state conflict");

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
