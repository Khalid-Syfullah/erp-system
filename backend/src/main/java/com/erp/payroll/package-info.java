/**
 * Payroll module (ARCHITECTURE.md §4.1, PRODUCT_SPEC.md §11): pay components with pluggable statutory
 * rules, salary structures, pay schedules and periods, effective-dated compensations, period inputs,
 * payroll runs calculated by a deterministic engine, payslips with PDFs, the bank file and payroll
 * reports. Payroll reads HR through its API and reacts to {@code hr.employee.terminated}; Accounting
 * books {@code payroll.run.posted} and {@code payroll.run.paid}.
 */
@ApplicationModule(
        displayName = "Payroll",
        allowedDependencies = {"platform", "db", "org :: api", "hr :: api", "hr :: events"})
package com.erp.payroll;

import org.springframework.modulith.ApplicationModule;
