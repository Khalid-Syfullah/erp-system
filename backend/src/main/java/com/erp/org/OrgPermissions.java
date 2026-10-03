package com.erp.org;

/** Permission codes of the Organization module used so far (SECURITY.md §4.2). */
public final class OrgPermissions {

    public static final String COMPANY_CREATE = "org.company.create";
    public static final String COMPANY_MANAGE = "org.company.manage";
    public static final String BRANCH_READ = "org.branch.read";
    public static final String BRANCH_MANAGE = "org.branch.manage";
    public static final String DEPARTMENT_READ = "org.department.read";
    public static final String DEPARTMENT_MANAGE = "org.department.manage";
    public static final String EXCHANGE_RATE_READ = "org.exchange_rate.read";
    public static final String EXCHANGE_RATE_MANAGE = "org.exchange_rate.manage";
    public static final String TAX_CODE_READ = "org.tax_code.read";
    public static final String TAX_CODE_MANAGE = "org.tax_code.manage";
    public static final String PAYMENT_TERMS_READ = "org.payment_terms.read";
    public static final String PAYMENT_TERMS_MANAGE = "org.payment_terms.manage";

    private OrgPermissions() {}
}
