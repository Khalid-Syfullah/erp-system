/**
 * Sales module (ARCHITECTURE.md §4.1, PRODUCT_SPEC.md §9): price lists, quotations, sales orders
 * with credit check and stock reservation, deliveries, sales returns and invoices / credit notes.
 * Stock moves only through {@code InventoryFacade}; the financial effect of invoices is booked by
 * Accounting from the published events (ADR-005), which answers through the ports in {@code api}.
 */
@ApplicationModule(
        displayName = "Sales",
        allowedDependencies = {"platform", "db", "org :: api", "partners :: api", "inventory :: api"})
package com.erp.sales;

import org.springframework.modulith.ApplicationModule;
