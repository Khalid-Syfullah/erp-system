package com.erp.sales.persistence;

import com.erp.platform.web.ConstraintErrorMapping;
import com.erp.platform.web.ErrorCode;
import com.erp.platform.web.PlatformErrorCode;
import com.erp.sales.application.SalesErrorCode;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Error codes for the Sales module's constraints. The counter CHECKs and the frozen-document
 * triggers back up the services' own checks: hitting one means two writers raced past validation.
 */
@Component
class SalesConstraintErrors implements ConstraintErrorMapping {

    @Override
    public Map<String, ErrorCode> constraintErrors() {
        return Map.ofEntries(
                Map.entry("uq_price_lists__company_id_code", PlatformErrorCode.DUPLICATE_CODE),
                Map.entry("uq_price_lists__default", PlatformErrorCode.CONFLICT),
                Map.entry("uq_price_list_items__tier", PlatformErrorCode.CONFLICT),
                Map.entry("uq_quotations__sales_order_id", PlatformErrorCode.INVALID_STATE),
                Map.entry("uq_deliveries__stock_movement_id", PlatformErrorCode.INVALID_STATE),
                Map.entry("uq_sales_returns__stock_movement_id", PlatformErrorCode.INVALID_STATE),
                Map.entry("ck_sales_order_lines__delivered", SalesErrorCode.QUANTITY_EXCEEDS_REMAINING),
                Map.entry("ck_delivery_lines__returned", SalesErrorCode.QUANTITY_EXCEEDS_REMAINING),
                Map.entry("ck_sales_return_lines__credited", SalesErrorCode.QUANTITY_EXCEEDS_REMAINING),
                Map.entry("ck_invoice_lines__credited", SalesErrorCode.QUANTITY_EXCEEDS_REMAINING),
                Map.entry("ck_quotations__frozen", PlatformErrorCode.INVALID_STATE),
                Map.entry("ck_quotation_lines__frozen", PlatformErrorCode.INVALID_STATE),
                Map.entry("ck_sales_orders__frozen", PlatformErrorCode.INVALID_STATE),
                Map.entry("ck_sales_order_lines__frozen", PlatformErrorCode.INVALID_STATE),
                Map.entry("ck_deliveries__frozen", PlatformErrorCode.INVALID_STATE),
                Map.entry("ck_delivery_lines__frozen", PlatformErrorCode.INVALID_STATE),
                Map.entry("ck_sales_returns__frozen", PlatformErrorCode.INVALID_STATE),
                Map.entry("ck_sales_return_lines__frozen", PlatformErrorCode.INVALID_STATE),
                Map.entry("ck_invoices__frozen", PlatformErrorCode.INVALID_STATE),
                Map.entry("ck_invoice_lines__frozen", PlatformErrorCode.INVALID_STATE),
                Map.entry("ck_invoice_taxes__frozen", PlatformErrorCode.INVALID_STATE));
    }
}
