/**
 * Platform kernel (ARCHITECTURE.md §4.3): request context, transactions with row-level-security
 * context, JSON strictness, error model, list conventions and endpoint security. Contains no
 * business logic. Every module may use it.
 */
@ApplicationModule(
        displayName = "Platform kernel",
        type = ApplicationModule.Type.OPEN,
        allowedDependencies = {})
package com.erp.platform;

import org.springframework.modulith.ApplicationModule;
