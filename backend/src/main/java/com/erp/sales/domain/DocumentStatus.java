package com.erp.sales.domain;

/**
 * Lifecycle of deliveries and invoices / credit notes (PRODUCT_SPEC.md §9.2): {@code DRAFT → POSTED}
 * or {@code DRAFT → CANCELLED}. Posted documents are corrected by new ones (a return for a delivery,
 * a credit note for an invoice), never edited.
 */
public enum DocumentStatus {
    DRAFT,
    POSTED,
    CANCELLED;

    /** Actions on a document. */
    public enum Action {
        EDIT,
        DELETE,
        POST,
        CANCEL
    }

    public boolean allows(Action action) {
        return this == DRAFT;
    }

    public DocumentStatus apply(Action action) {
        if (!allows(action)) {
            throw new IllegalStateException(action + " is not allowed for a " + this + " document");
        }
        return switch (action) {
            case POST -> POSTED;
            case CANCEL -> CANCELLED;
            case EDIT, DELETE -> DRAFT;
        };
    }
}
