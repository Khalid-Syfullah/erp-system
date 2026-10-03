/**
 * Procurement module (ARCHITECTURE.md §4.1, PRODUCT_SPEC.md §7): requisitions, purchase orders,
 * goods receipts, purchase returns and supplier bills / debit notes. Stock moves only through
 * {@code InventoryFacade}; the financial effect of bills is booked by Accounting from the published
 * events (ADR-005). Accounting answers settlement questions through {@code BillSettlementPort}.
 */
@ApplicationModule(
        displayName = "Procurement",
        allowedDependencies = {"platform", "db", "org :: api", "partners :: api", "inventory :: api"})
package com.erp.procurement;

import org.springframework.modulith.ApplicationModule;
