package com.erp.platform.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a system-administration handler whose transactions may read and write across companies
 * where the RLS policies allow it (DATABASE.md §3: {@code app.global_access}). Applied only after the
 * handler's permission check succeeded. ArchitectureTests restrict it to {@code /api/v1/admin}
 * controllers.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface GlobalAccess {}
