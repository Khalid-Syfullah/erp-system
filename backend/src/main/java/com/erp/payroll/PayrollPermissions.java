package com.erp.payroll;

/** Permission codes of the Payroll module (SECURITY.md §4.2); all are sensitive (MFA). */
public final class PayrollPermissions {

    public static final String CONFIGURATION_MANAGE = "payroll.configuration.manage";
    public static final String COMPENSATION_READ = "payroll.compensation.read";
    public static final String COMPENSATION_MANAGE = "payroll.compensation.manage";
    public static final String RUN_READ = "payroll.run.read";
    public static final String RUN_PREPARE = "payroll.run.prepare";
    public static final String RUN_APPROVE = "payroll.run.approve";
    public static final String RUN_POST = "payroll.run.post";
    public static final String RUN_PAY = "payroll.run.pay";
    public static final String PAYSLIP_READ = "payroll.payslip.read";
    public static final String REPORT_READ = "payroll.report.read";

    private PayrollPermissions() {}
}
