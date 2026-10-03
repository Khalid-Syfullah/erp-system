package com.erp.inventory.domain;

import java.util.Set;

/**
 * Stock movement types and their line shapes (PRODUCT_SPEC.md §6.3). A line moves a quantity out of
 * a location ({@code from}), into one ({@code to}), or both (transfers).
 */
public enum MovementType {
    OPENING(Shape.IN),
    PURCHASE_RECEIPT(Shape.IN),
    PURCHASE_RETURN(Shape.OUT),
    SALES_ISSUE(Shape.OUT),
    SALES_RETURN(Shape.IN),
    TRANSFER(Shape.MOVE),
    TRANSFER_SHIP(Shape.MOVE),
    TRANSFER_RECEIVE(Shape.MOVE),
    ADJUSTMENT(Shape.IN_OR_OUT),
    SCRAP(Shape.OUT),
    COUNT_ADJUSTMENT(Shape.IN_OR_OUT),
    REVERSAL(Shape.ANY);

    /** Which locations a line names. */
    public enum Shape {
        IN,
        OUT,
        MOVE,
        IN_OR_OUT,
        ANY
    }

    /** Types users create through {@code POST {c}/stock-movements}; the rest come from documents (API.md §17.5). */
    public static final Set<MovementType> API_CREATABLE = Set.of(OPENING, TRANSFER, TRANSFER_SHIP, ADJUSTMENT, SCRAP);

    private final Shape shape;

    MovementType(Shape shape) {
        this.shape = shape;
    }

    public Shape shape() {
        return shape;
    }

    public boolean requiresReason() {
        return this == ADJUSTMENT || this == SCRAP || this == COUNT_ADJUSTMENT;
    }

    /** Adjustments need {@code inventory.adjustment.manage} and may need approval (INV-7). */
    public boolean isAdjustment() {
        return requiresReason();
    }

    /** Inbound lines of these types must state their unit cost (no average to fall back on). */
    public boolean requiresInboundCost() {
        return this == OPENING || this == PURCHASE_RECEIPT || this == SALES_RETURN;
    }

    /** Whether a line with these locations is valid for the type. */
    public boolean accepts(boolean hasFrom, boolean hasTo) {
        return switch (shape) {
            case IN -> !hasFrom && hasTo;
            case OUT -> hasFrom && !hasTo;
            case MOVE -> hasFrom && hasTo;
            case IN_OR_OUT -> hasFrom != hasTo;
            case ANY -> hasFrom || hasTo;
        };
    }
}
