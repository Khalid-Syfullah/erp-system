package com.erp.hr;

/** Permission codes of the HR module (SECURITY.md §4.2). */
public final class HrPermissions {

    public static final String EMPLOYEE_READ = "hr.employee.read";
    public static final String EMPLOYEE_MANAGE = "hr.employee.manage";
    public static final String EMPLOYEE_READ_SENSITIVE = "hr.employee.read_sensitive";
    public static final String EMPLOYEE_MANAGE_BANK = "hr.employee.manage_bank";
    public static final String EMPLOYEE_TERMINATE = "hr.employee.terminate";
    public static final String POSITION_MANAGE = "hr.position.manage";
    public static final String LEAVE_READ = "hr.leave.read";
    public static final String LEAVE_APPROVE = "hr.leave.approve";
    public static final String LEAVE_ADJUST = "hr.leave.adjust";
    public static final String LEAVE_CONFIGURE = "hr.leave.configure";
    public static final String ATTENDANCE_READ = "hr.attendance.read";
    public static final String ATTENDANCE_MANAGE = "hr.attendance.manage";

    private HrPermissions() {}
}
