/**
 * Reporting module (ARCHITECTURE.md §4.1, PRODUCT_SPEC.md §13, ADR-040): the report catalogue with
 * its permission mapping, synchronous bounded reports, asynchronous exports (CSV, XLSX, PDF), saved
 * report parameters and role dashboards. It owns no transactional data. Operational reports read
 * the modules' published {@code v_rpt_*} views through the read-only {@code erp_reporting}
 * connection pool (RLS applies); the financial statements come from Accounting's
 * {@code FinancialReports} API, so every figure has a single implementation.
 */
@ApplicationModule(
        displayName = "Reporting",
        allowedDependencies = {"platform", "db", "org :: api", "accounting :: api"})
package com.erp.reporting;

import org.springframework.modulith.ApplicationModule;
