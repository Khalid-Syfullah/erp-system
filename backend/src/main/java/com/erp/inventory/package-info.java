/**
 * Inventory module (ARCHITECTURE.md §4.1): catalog (categories, products, variants, units), warehouses
 * and locations, stock movements with the append-only inventory ledger, balances, reservations,
 * moving-average valuation and physical counts. Procurement and Sales change stock only through
 * {@code InventoryFacade}; every stock change is a posted movement (PRODUCT_SPEC.md §6.3).
 */
@ApplicationModule(
        displayName = "Inventory",
        allowedDependencies = {"platform", "db", "org :: api"})
package com.erp.inventory;

import org.springframework.modulith.ApplicationModule;
