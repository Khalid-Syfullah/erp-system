package com.erp.procurement;

/** Permission codes of the Procurement module (SECURITY.md §4.2). */
public final class ProcurementPermissions {

    public static final String REQUISITION_READ = "procurement.requisition.read";
    public static final String REQUISITION_CREATE = "procurement.requisition.create";
    public static final String REQUISITION_APPROVE = "procurement.requisition.approve";
    public static final String PO_READ = "procurement.purchase_order.read";
    public static final String PO_CREATE = "procurement.purchase_order.create";
    public static final String PO_APPROVE = "procurement.purchase_order.approve";
    public static final String PO_APPROVE_HIGH = "procurement.purchase_order.approve_high";
    public static final String PO_CANCEL = "procurement.purchase_order.cancel";
    public static final String PO_CLOSE = "procurement.purchase_order.close";
    public static final String RECEIPT_READ = "procurement.receipt.read";
    public static final String RECEIPT_CREATE = "procurement.receipt.create";
    public static final String RECEIPT_POST = "procurement.receipt.post";
    public static final String RETURN_MANAGE = "procurement.return.manage";
    public static final String BILL_READ = "procurement.supplier_bill.read";
    public static final String BILL_CREATE = "procurement.supplier_bill.create";
    public static final String BILL_CREATE_DIRECT = "procurement.supplier_bill.create_direct";
    public static final String BILL_POST = "procurement.supplier_bill.post";
    public static final String BILL_OVERRIDE_MATCH = "procurement.supplier_bill.override_match";
    public static final String SETTINGS_MANAGE = "procurement.settings.manage";

    private ProcurementPermissions() {}
}
