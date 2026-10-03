package com.erp.inventory;

/** Permission codes of the Inventory module (SECURITY.md §4.2). */
public final class InventoryPermissions {

    public static final String PRODUCT_READ = "inventory.product.read";
    public static final String PRODUCT_MANAGE = "inventory.product.manage";
    public static final String WAREHOUSE_READ = "inventory.warehouse.read";
    public static final String WAREHOUSE_MANAGE = "inventory.warehouse.manage";
    public static final String STOCK_READ = "inventory.stock.read";
    public static final String VALUATION_READ = "inventory.valuation.read";
    public static final String MOVEMENT_READ = "inventory.movement.read";
    public static final String MOVEMENT_CREATE = "inventory.movement.create";
    public static final String MOVEMENT_POST = "inventory.movement.post";
    public static final String MOVEMENT_REVERSE = "inventory.movement.reverse";
    public static final String ADJUSTMENT_MANAGE = "inventory.adjustment.manage";
    public static final String ADJUSTMENT_APPROVE = "inventory.adjustment.approve";
    public static final String COUNT_MANAGE = "inventory.count.manage";
    public static final String COUNT_POST = "inventory.count.post";
    public static final String SETTINGS_MANAGE = "inventory.settings.manage";

    private InventoryPermissions() {}
}
