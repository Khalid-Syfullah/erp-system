package com.erp.reporting;

/** Permission codes of the Reporting module (SECURITY.md §4.2). */
public final class ReportingPermissions {

    public static final String SALES_READ = "reporting.sales.read";
    public static final String PROCUREMENT_READ = "reporting.procurement.read";
    public static final String INVENTORY_READ = "reporting.inventory.read";
    public static final String HR_READ = "reporting.hr.read";
    public static final String EXPORT_CREATE = "reporting.export.create";
    public static final String SAVED_REPORT_SHARE = "reporting.saved_report.share";

    // Other modules' permissions that gate reports, by value (Reporting depends on no module's internals).
    public static final String INVENTORY_VALUATION_READ = "inventory.valuation.read";
    public static final String ACCOUNTING_REPORT_READ = "accounting.report.read";
    public static final String ACCOUNTING_AR_READ = "accounting.ar.read";
    public static final String ACCOUNTING_AP_READ = "accounting.ap.read";
    public static final String PAYROLL_REPORT_READ = "payroll.report.read";

    private ReportingPermissions() {}
}
