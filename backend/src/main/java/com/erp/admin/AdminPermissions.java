package com.erp.admin;

/** Permission codes of the Administration module (SECURITY.md §4.2). */
public final class AdminPermissions {

    public static final String AUDIT_READ = "admin.audit.read";
    public static final String AUDIT_READ_GLOBAL = "admin.audit.read_global";
    public static final String SETTINGS_MANAGE = "admin.settings.manage";
    public static final String SYSTEM_READ = "admin.system.read";

    private AdminPermissions() {}
}
