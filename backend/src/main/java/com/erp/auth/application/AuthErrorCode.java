package com.erp.auth.application;

import com.erp.platform.web.ErrorCode;
import org.springframework.http.HttpStatus;

/** Error codes of the Auth module (API.md §6.1). */
public enum AuthErrorCode implements ErrorCode {
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "Invalid credentials"),
    MFA_REQUIRED(HttpStatus.UNAUTHORIZED, "Second factor required"),
    INVALID_MFA_CODE(HttpStatus.UNAUTHORIZED, "Invalid verification code"),
    MFA_ENROLLMENT_REQUIRED(HttpStatus.FORBIDDEN, "Multi-factor enrollment required"),
    MFA_MANDATORY(HttpStatus.CONFLICT, "Multi-factor authentication is mandatory"),
    MFA_NOT_ENROLLED(HttpStatus.CONFLICT, "Multi-factor authentication not enrolled"),
    INVALID_TOKEN(HttpStatus.BAD_REQUEST, "Invalid or expired token"),
    LAST_SYSTEM_ADMIN(HttpStatus.CONFLICT, "Last system administrator"),
    SYSTEM_ROLE_IMMUTABLE(HttpStatus.CONFLICT, "System role cannot be changed"),
    PRIVILEGE_ESCALATION(HttpStatus.FORBIDDEN, "Privilege escalation denied"),
    DUPLICATE_EMAIL(HttpStatus.CONFLICT, "Email already in use"),
    DUPLICATE_ASSIGNMENT(HttpStatus.CONFLICT, "Role already assigned");

    private final HttpStatus status;
    private final String title;

    AuthErrorCode(HttpStatus status, String title) {
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
