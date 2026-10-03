/**
 * Administration module (ARCHITECTURE.md §4.1): stores and serves the business audit trail
 * (implements the platform's {@code AuditPort}) and runs its maintenance. Settings, retention and
 * feature flags follow in later phases.
 */
@ApplicationModule(
        displayName = "Administration",
        allowedDependencies = {"platform", "db"})
package com.erp.admin;

import org.springframework.modulith.ApplicationModule;
