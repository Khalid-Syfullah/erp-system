/**
 * Organization module (ARCHITECTURE.md §4.1). Phase 2 delivers the org core (ADR-024): reference
 * data (currencies, countries) plus the companies and branches tables that Auth scopes access to.
 */
@ApplicationModule(
        displayName = "Organization",
        allowedDependencies = {"platform", "db"})
package com.erp.org;

import org.springframework.modulith.ApplicationModule;
