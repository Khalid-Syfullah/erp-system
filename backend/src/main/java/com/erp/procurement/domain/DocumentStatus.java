package com.erp.procurement.domain;

/**
 * Lifecycle of goods receipts, purchase returns and supplier bills / debit notes (PRODUCT_SPEC.md
 * §7.2): {@code DRAFT → POSTED} or {@code DRAFT → CANCELLED}. Posted documents are corrected by a
 * new document (a return for a receipt, a debit note for a bill), never edited.
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
