package com.erp.procurement.domain;

/**
 * What a supplier bill line invoices (DATABASE.md §5.6): received stock (against a receipt line;
 * clears GRNI), a service, or non-stock goods (consumables). Each maps to its accounting treatment
 * in the posting matrix (PRODUCT_SPEC.md §8.6).
 */
public enum BillLineKind {
    RECEIVED_STOCK,
    SERVICE,
    NON_STOCK_GOODS;

    /** The line type of the {@code supplier_bill.posted} event (ARCHITECTURE.md §7). */
    public String eventType() {
        return switch (this) {
            case RECEIVED_STOCK -> "STOCK_RECEIVED";
            case SERVICE -> "SERVICE";
            case NON_STOCK_GOODS -> "EXPENSE";
        };
    }

    /** Lines without a receipt follow from the product type. */
    public static BillLineKind ofProductType(String productType) {
        return switch (productType) {
            case "SERVICE" -> SERVICE;
            case "CONSUMABLE" -> NON_STOCK_GOODS;
            default -> throw new IllegalArgumentException("Stockable goods are billed against a receipt");
        };
    }
}
