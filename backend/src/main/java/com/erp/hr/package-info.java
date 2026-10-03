/**
 * HR module (ARCHITECTURE.md §4.1). Phase 4 delivers its organizational slice (ADR-033): positions
 * (designations / job titles), core employee records, effective-dated employment assignments with
 * reporting lines, and department heads. HR depends on Org's API for branches and departments and
 * reports their use back through the {@code OrganizationUsage} port.
 */
@ApplicationModule(
        displayName = "HR",
        allowedDependencies = {"platform", "db", "org :: api"})
package com.erp.hr;

import org.springframework.modulith.ApplicationModule;
