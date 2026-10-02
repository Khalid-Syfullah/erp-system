package com.erp.platform.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an endpoint reachable without authentication (login, password reset, CSRF bootstrap, error
 * page). Every controller method must carry exactly one of {@link PublicEndpoint},
 * {@link AuthenticatedEndpoint} or {@link RequiresPermission} (SECURITY.md §1, enforced by
 * ArchitectureTests and at runtime by {@link EndpointAccessInterceptor}).
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface PublicEndpoint {}
