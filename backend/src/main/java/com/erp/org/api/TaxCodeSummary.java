package com.erp.org.api;

import java.util.UUID;

/** A tax code as seen by other modules; {@code scope} is SALES, PURCHASE or BOTH. */
public record TaxCodeSummary(UUID id, String code, String scope, boolean active) {

    public boolean appliesToSales() {
        return !"PURCHASE".equals(scope);
    }

    public boolean appliesToPurchases() {
        return !"SALES".equals(scope);
    }
}
