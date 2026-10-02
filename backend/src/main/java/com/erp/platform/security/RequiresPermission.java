package com.erp.platform.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Requires all listed permissions ({@code module.resource.action}, SECURITY.md §4.2) for the active
 * company. Evaluated by {@link EndpointAccessInterceptor} through the {@link PermissionCheck} port.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface RequiresPermission {

    /** Permission codes; all are required. */
    String[] value();
}
