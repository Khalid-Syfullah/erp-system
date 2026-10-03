/**
 * Platform kernel (ARCHITECTURE.md §4.3): request context, transactions with row-level-security
 * context, JSON strictness, error model, list conventions, endpoint security, document numbering and
 * idempotency. Contains no business logic. Every module may use it; its own tables live in the
 * {@code platform} schema (generated classes in {@code com.erp.db.platform}).
 */
@ApplicationModule(
        displayName = "Platform kernel",
        type = ApplicationModule.Type.OPEN,
        allowedDependencies = {"db"})
package com.erp.platform;

import org.springframework.modulith.ApplicationModule;
