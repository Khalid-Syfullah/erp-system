/**
 * HR module (ARCHITECTURE.md §4.1, PRODUCT_SPEC.md §10): positions (designations), employees with
 * personal and field-encrypted sensitive data, effective-dated assignments with reporting lines,
 * department heads, bank accounts, documents, leave (types, ledger, requests, holidays), basic
 * attendance (ADR-039) and self-service. HR depends on Org's API for branches and departments and on
 * Auth's API to link and deactivate users; Payroll reads it through {@code hr.api}.
 */
@ApplicationModule(
        displayName = "HR",
        allowedDependencies = {"platform", "db", "org :: api", "auth :: api"})
package com.erp.hr;

import org.springframework.modulith.ApplicationModule;
