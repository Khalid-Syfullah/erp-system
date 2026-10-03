package com.erp.auth.web;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Handlers usable by a session that must enroll MFA first (SECURITY.md §3.5): profile, enrollment and
 * logout. Every other handler answers 403 {@code MFA_ENROLLMENT_REQUIRED} for such sessions.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@interface AllowedDuringMfaEnrollment {}
