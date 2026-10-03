package com.erp.inventory.persistence;

import com.erp.inventory.application.InventoryErrorCode;
import com.erp.platform.web.ConstraintErrorMapping;
import com.erp.platform.web.ErrorCode;
import com.erp.platform.web.PlatformErrorCode;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Error codes for the Inventory module's constraints. The stock CHECKs are a backstop behind the
 * posting engine's own checks: hitting one means two writers raced past application validation.
 */
@Component
class InventoryConstraintErrors implements ConstraintErrorMapping {

    @Override
    public Map<String, ErrorCode> constraintErrors() {
        return Map.ofEntries(
                Map.entry("uq_product_categories__company_id_code", PlatformErrorCode.DUPLICATE_CODE),
                Map.entry("uq_products__company_id_code", PlatformErrorCode.DUPLICATE_CODE),
                Map.entry("uq_product_attributes__company_id_code", PlatformErrorCode.DUPLICATE_CODE),
                Map.entry("uq_product_attribute_values__attribute_id_code", PlatformErrorCode.DUPLICATE_CODE),
                Map.entry("uq_warehouses__company_id_code", PlatformErrorCode.DUPLICATE_CODE),
                Map.entry("uq_locations__warehouse_id_code", PlatformErrorCode.DUPLICATE_CODE),
                Map.entry("uq_reason_codes__company_id_code", PlatformErrorCode.DUPLICATE_CODE),
                Map.entry("uq_product_variants__company_id_sku", InventoryErrorCode.DUPLICATE_SKU),
                Map.entry("uq_product_variants__company_id_barcode", InventoryErrorCode.DUPLICATE_BARCODE),
                Map.entry("uq_product_variants__product_id_attribute_signature", InventoryErrorCode.DUPLICATE_VARIANT),
                Map.entry("uq_product_uom_conversions__product_id_uom_id", PlatformErrorCode.CONFLICT),
                Map.entry("uq_stock_movements__company_id_source", InventoryErrorCode.DUPLICATE_SOURCE_DOCUMENT),
                Map.entry("uq_stock_movements__related_receive", PlatformErrorCode.INVALID_STATE),
                Map.entry("uq_stock_movements__reversal_of_id", PlatformErrorCode.INVALID_STATE),
                Map.entry("ck_stock_movements__immutable", PlatformErrorCode.INVALID_STATE),
                Map.entry("ck_stock_movement_lines__immutable", PlatformErrorCode.INVALID_STATE),
                Map.entry("ck_products__base_uom_locked", PlatformErrorCode.INVALID_STATE),
                Map.entry("ck_stock_balances__on_hand", InventoryErrorCode.INSUFFICIENT_STOCK),
                Map.entry("ck_warehouse_stock__on_hand", InventoryErrorCode.INSUFFICIENT_STOCK),
                Map.entry("ck_warehouse_stock__reserved_covered", InventoryErrorCode.RESERVED_STOCK_CONFLICT),
                Map.entry("ck_item_valuations__quantity", InventoryErrorCode.INSUFFICIENT_STOCK),
                Map.entry("ck_item_valuations__value", InventoryErrorCode.REVERSAL_NOT_POSSIBLE),
                Map.entry("ck_item_valuations__no_residual_value", InventoryErrorCode.REVERSAL_NOT_POSSIBLE));
    }
}
