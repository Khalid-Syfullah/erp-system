package com.erp.partners;

/** Permission codes of the Partners module (SECURITY.md §4.2). */
public final class PartnersPermissions {

    public static final String PARTNER_READ = "partners.partner.read";
    public static final String PARTNER_MANAGE = "partners.partner.manage";
    public static final String BANK_READ = "partners.partner.read_bank";
    public static final String BANK_MANAGE = "partners.partner.manage_bank";
    public static final String CUSTOMER_MANAGE = "partners.customer.manage";
    public static final String SUPPLIER_MANAGE = "partners.supplier.manage";

    private PartnersPermissions() {}
}
