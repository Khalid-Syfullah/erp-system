package com.erp.platform.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an endpoint open to any authenticated principal without a specific permission, e.g. global
 * reference data ({@code /reference/currencies}) and the caller's own profile ({@code /me}).
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface AuthenticatedEndpoint {}
