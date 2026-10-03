package com.erp.org;

/** Permission codes of the Organization module used so far (SECURITY.md §4.2). */
public final class OrgPermissions {

    public static final String COMPANY_CREATE = "org.company.create";
    public static final String COMPANY_MANAGE = "org.company.manage";
    public static final String BRANCH_READ = "org.branch.read";
    public static final String BRANCH_MANAGE = "org.branch.manage";

    private OrgPermissions() {}
}
