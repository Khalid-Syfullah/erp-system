package com.erp.inventory.domain;

/**
 * Location types (PRODUCT_SPEC.md §6.2). Only INTERNAL, RECEIVING and SHIPPING stock counts toward a
 * warehouse's on-hand and available-to-promise quantity; QUARANTINE and TRANSIT stock is tracked per
 * location only.
 */
public enum LocationType {
    INTERNAL,
    RECEIVING,
    SHIPPING,
    QUARANTINE,
    TRANSIT;

    public boolean countsTowardWarehouse() {
        return this == INTERNAL || this == RECEIVING || this == SHIPPING;
    }
}
