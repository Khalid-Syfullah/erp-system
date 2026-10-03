package com.erp.sales.domain;

/**
 * Sales return lifecycle (PRODUCT_SPEC.md §9.2): {@code DRAFT → RECEIVED} (the goods are back in
 * stock) or {@code DRAFT → CANCELLED}.
 */
public enum ReturnStatus {
    DRAFT,
    RECEIVED,
    CANCELLED;

    /** Actions on a return. */
    public enum Action {
        RECEIVE,
        CANCEL
    }

    public boolean allows(Action action) {
        return this == DRAFT;
    }

    public ReturnStatus apply(Action action) {
        if (!allows(action)) {
            throw new IllegalStateException(action + " is not allowed for a " + this + " return");
        }
        return action == Action.RECEIVE ? RECEIVED : CANCELLED;
    }
}
