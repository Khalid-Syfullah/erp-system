package com.erp.hr;

/** Permission codes of the HR module used so far (SECURITY.md §4.2). */
public final class HrPermissions {

    public static final String EMPLOYEE_READ = "hr.employee.read";
    public static final String EMPLOYEE_MANAGE = "hr.employee.manage";
    public static final String EMPLOYEE_TERMINATE = "hr.employee.terminate";
    public static final String POSITION_MANAGE = "hr.position.manage";

    private HrPermissions() {}
}
