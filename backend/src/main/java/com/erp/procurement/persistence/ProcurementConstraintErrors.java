package com.erp.procurement.persistence;

import com.erp.platform.web.ConstraintErrorMapping;
import com.erp.platform.web.ErrorCode;
import com.erp.platform.web.PlatformErrorCode;
import com.erp.procurement.application.ProcurementErrorCode;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Error codes for the Procurement module's constraints. The counter CHECKs and the frozen-document
 * triggers back up the services' own checks: hitting one means two writers raced past validation.
 */
@Component
class ProcurementConstraintErrors implements ConstraintErrorMapping {

    @Override
    public Map<String, ErrorCode> constraintErrors() {
        return Map.ofEntries(
                Map.entry(
                        "uq_supplier_bills__supplier_invoice_number", ProcurementErrorCode.DUPLICATE_SUPPLIER_INVOICE),
                Map.entry("uq_goods_receipts__stock_movement_id", PlatformErrorCode.INVALID_STATE),
                Map.entry("uq_purchase_returns__stock_movement_id", PlatformErrorCode.INVALID_STATE),
                Map.entry("ck_purchase_requisition_lines__ordered", ProcurementErrorCode.QUANTITY_EXCEEDS_REMAINING),
                Map.entry("ck_purchase_order_lines__received", ProcurementErrorCode.QUANTITY_EXCEEDS_REMAINING),
                Map.entry("ck_goods_receipt_lines__counters", ProcurementErrorCode.QUANTITY_EXCEEDS_REMAINING),
                Map.entry("ck_supplier_bills__posted_matched", ProcurementErrorCode.MATCH_EXCEPTION),
                Map.entry("ck_purchase_requisitions__frozen", PlatformErrorCode.INVALID_STATE),
                Map.entry("ck_purchase_requisition_lines__frozen", PlatformErrorCode.INVALID_STATE),
                Map.entry("ck_purchase_orders__frozen", PlatformErrorCode.INVALID_STATE),
                Map.entry("ck_purchase_order_lines__frozen", PlatformErrorCode.INVALID_STATE),
                Map.entry("ck_goods_receipts__frozen", PlatformErrorCode.INVALID_STATE),
                Map.entry("ck_goods_receipt_lines__frozen", PlatformErrorCode.INVALID_STATE),
                Map.entry("ck_purchase_returns__frozen", PlatformErrorCode.INVALID_STATE),
                Map.entry("ck_purchase_return_lines__frozen", PlatformErrorCode.INVALID_STATE),
                Map.entry("ck_supplier_bills__frozen", PlatformErrorCode.INVALID_STATE),
                Map.entry("ck_supplier_bill_lines__frozen", PlatformErrorCode.INVALID_STATE),
                Map.entry("ck_supplier_bill_taxes__frozen", PlatformErrorCode.INVALID_STATE));
    }
}
