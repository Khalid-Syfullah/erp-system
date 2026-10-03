package com.erp.sales;

/** Permission codes of the Sales module (SECURITY.md §4.2). */
public final class SalesPermissions {

    public static final String PRICE_LIST_READ = "sales.price_list.read";
    public static final String PRICE_LIST_MANAGE = "sales.price_list.manage";
    public static final String QUOTATION_READ = "sales.quotation.read";
    public static final String QUOTATION_MANAGE = "sales.quotation.manage";
    public static final String ORDER_READ = "sales.order.read";
    public static final String ORDER_CREATE = "sales.order.create";
    public static final String ORDER_CONFIRM = "sales.order.confirm";
    public static final String ORDER_OVERRIDE_CREDIT = "sales.order.override_credit";
    public static final String ORDER_OVERRIDE_PRICE = "sales.order.override_price";
    public static final String ORDER_DISCOUNT_HIGH = "sales.order.discount_high";
    public static final String ORDER_CANCEL = "sales.order.cancel";
    public static final String ORDER_CLOSE = "sales.order.close";
    public static final String DELIVERY_READ = "sales.delivery.read";
    public static final String DELIVERY_CREATE = "sales.delivery.create";
    public static final String DELIVERY_POST = "sales.delivery.post";
    public static final String RETURN_MANAGE = "sales.return.manage";
    public static final String INVOICE_READ = "sales.invoice.read";
    public static final String INVOICE_CREATE = "sales.invoice.create";
    public static final String INVOICE_CREATE_DIRECT = "sales.invoice.create_direct";
    public static final String INVOICE_POST = "sales.invoice.post";
    public static final String INVOICE_SEND = "sales.invoice.send";
    public static final String SETTINGS_MANAGE = "sales.settings.manage";

    private SalesPermissions() {}
}
