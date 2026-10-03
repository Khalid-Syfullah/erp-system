package com.erp.inventory.application;

import static com.erp.platform.web.paging.FilterOperator.EQ;
import static com.erp.platform.web.paging.FilterOperator.GTE;
import static com.erp.platform.web.paging.FilterOperator.IN;
import static com.erp.platform.web.paging.FilterOperator.IS_NULL;
import static com.erp.platform.web.paging.FilterOperator.LIKE;
import static com.erp.platform.web.paging.FilterOperator.LTE;

import com.erp.platform.web.paging.ListDefinition;
import com.erp.platform.web.paging.SortOrder;
import com.erp.platform.web.paging.ValueType;
import java.util.Set;

/** List contracts of the Inventory endpoints (API.md §17.5). */
public final class InventoryListings {

    public static final ListDefinition CATEGORIES = ListDefinition.builder("inventory.product_categories")
            .sortable("code", "name", "createdAt")
            .defaultSort(SortOrder.asc("code"))
            .filter("code", ValueType.STRING, EQ, IN, LIKE)
            .filter("parentId", ValueType.UUID, EQ, IN, IS_NULL)
            .filter("isActive", ValueType.BOOLEAN, EQ)
            .searchable()
            .build();

    public static final ListDefinition PRODUCTS = ListDefinition.builder("inventory.products")
            .sortable("code", "name", "createdAt")
            .defaultSort(SortOrder.asc("code"))
            .filter("code", ValueType.STRING, EQ, IN, LIKE)
            .filter("categoryId", ValueType.UUID, EQ, IN)
            .enumFilter("productType", Set.of("STOCKABLE", "CONSUMABLE", "SERVICE"), EQ, IN)
            .enumFilter("status", Set.of("ACTIVE", "ARCHIVED"), EQ)
            .filter("isPurchasable", ValueType.BOOLEAN, EQ)
            .filter("isSellable", ValueType.BOOLEAN, EQ)
            .searchable()
            .build();

    public static final ListDefinition VARIANTS = ListDefinition.builder("inventory.product_variants")
            .sortable("sku", "name", "createdAt")
            .defaultSort(SortOrder.asc("sku"))
            .filter("productId", ValueType.UUID, EQ, IN)
            .filter("sku", ValueType.STRING, EQ, IN, LIKE)
            .filter("barcode", ValueType.STRING, EQ)
            .enumFilter("status", Set.of("ACTIVE", "ARCHIVED"), EQ)
            .searchable()
            .build();

    public static final ListDefinition ATTRIBUTES = ListDefinition.builder("inventory.product_attributes")
            .sortable("code", "name")
            .defaultSort(SortOrder.asc("code"))
            .filter("code", ValueType.STRING, EQ, IN)
            .searchable()
            .build();

    public static final ListDefinition WAREHOUSES = ListDefinition.builder("inventory.warehouses")
            .sortable("code", "name", "createdAt")
            .defaultSort(SortOrder.asc("code"))
            .filter("code", ValueType.STRING, EQ, IN, LIKE)
            .filter("branchId", ValueType.UUID, EQ, IN)
            .filter("isActive", ValueType.BOOLEAN, EQ)
            .searchable()
            .build();

    public static final ListDefinition LOCATIONS = ListDefinition.builder("inventory.locations")
            .sortable("code", "name")
            .defaultSort(SortOrder.asc("code"))
            .filter("code", ValueType.STRING, EQ, IN, LIKE)
            .enumFilter("locationType", Set.of("INTERNAL", "RECEIVING", "SHIPPING", "QUARANTINE", "TRANSIT"), EQ, IN)
            .filter("parentId", ValueType.UUID, EQ, IS_NULL)
            .filter("isActive", ValueType.BOOLEAN, EQ)
            .searchable()
            .build();

    public static final ListDefinition REASON_CODES = ListDefinition.builder("inventory.reason_codes")
            .sortable("code", "name")
            .defaultSort(SortOrder.asc("code"))
            .enumFilter("appliesTo", Set.of("ADJUSTMENT", "SCRAP", "COUNT"), EQ)
            .filter("isActive", ValueType.BOOLEAN, EQ)
            .build();

    public static final ListDefinition MOVEMENTS = ListDefinition.builder("inventory.stock_movements")
            .sortable("movementDate", "createdAt")
            .defaultSort(SortOrder.desc("createdAt"))
            .enumFilter(
                    "movementType",
                    Set.of(
                            "OPENING",
                            "PURCHASE_RECEIPT",
                            "PURCHASE_RETURN",
                            "SALES_ISSUE",
                            "SALES_RETURN",
                            "TRANSFER",
                            "TRANSFER_SHIP",
                            "TRANSFER_RECEIVE",
                            "ADJUSTMENT",
                            "SCRAP",
                            "COUNT_ADJUSTMENT",
                            "REVERSAL"),
                    EQ,
                    IN)
            .enumFilter("status", Set.of("DRAFT", "POSTED", "CANCELLED"), EQ, IN)
            .filter("warehouseId", ValueType.UUID, EQ, IN)
            .filter("destWarehouseId", ValueType.UUID, EQ, IN)
            .filter("movementDate", ValueType.DATE, EQ, GTE, LTE)
            .filter("sourceId", ValueType.UUID, EQ)
            .filter("number", ValueType.STRING, EQ, LIKE)
            .searchable()
            .build();

    /** Warehouse stock rows are unique per (variant, warehouse): sorted by variant, then warehouse. */
    public static final ListDefinition STOCK_LEVELS = ListDefinition.builder("inventory.warehouse_stock")
            .sortable("variantId")
            .defaultSort(SortOrder.asc("variantId"))
            .filter("variantId", ValueType.UUID, EQ, IN)
            .filter("warehouseId", ValueType.UUID, EQ, IN)
            .build();

    public static final ListDefinition STOCK_BY_LOCATION = ListDefinition.builder("inventory.stock_balances")
            .sortable("variantId")
            .defaultSort(SortOrder.asc("variantId"))
            .filter("variantId", ValueType.UUID, EQ, IN)
            .filter("warehouseId", ValueType.UUID, EQ, IN)
            .filter("locationId", ValueType.UUID, EQ, IN)
            .build();

    public static final ListDefinition LEDGER = ListDefinition.builder("inventory.inventory_transactions")
            .sortable("createdAt", "transactionDate")
            .defaultSort(SortOrder.desc("createdAt"))
            .filter("variantId", ValueType.UUID, EQ, IN)
            .filter("warehouseId", ValueType.UUID, EQ, IN)
            .filter("locationId", ValueType.UUID, EQ, IN)
            .filter("movementId", ValueType.UUID, EQ)
            .enumFilter(
                    "movementType",
                    Set.of(
                            "OPENING",
                            "PURCHASE_RECEIPT",
                            "PURCHASE_RETURN",
                            "SALES_ISSUE",
                            "SALES_RETURN",
                            "TRANSFER",
                            "TRANSFER_SHIP",
                            "TRANSFER_RECEIVE",
                            "ADJUSTMENT",
                            "SCRAP",
                            "COUNT_ADJUSTMENT",
                            "REVERSAL"),
                    EQ,
                    IN)
            .filter("transactionDate", ValueType.DATE, EQ, GTE, LTE)
            .build();

    public static final ListDefinition COUNTS = ListDefinition.builder("inventory.stock_counts")
            .sortable("countDate", "createdAt")
            .defaultSort(SortOrder.desc("createdAt"))
            .filter("warehouseId", ValueType.UUID, EQ, IN)
            .enumFilter("status", Set.of("DRAFT", "IN_PROGRESS", "COMPLETED", "POSTED", "CANCELLED"), EQ, IN)
            .build();

    private InventoryListings() {}
}
