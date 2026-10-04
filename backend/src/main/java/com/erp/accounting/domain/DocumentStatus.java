package com.erp.accounting.domain;

import java.util.Map;
import java.util.Set;

/**
 * Lifecycle of payments ({@code DRAFT → POSTED → VOIDED}, PRODUCT_SPEC.md §8.8) and expense
 * vouchers ({@code DRAFT → POSTED → REVERSED}, §8.9). A posted document is undone only by a
 * reversing entry, never edited or deleted.
 */
public enum DocumentStatus {
    DRAFT,
    POSTED,
    VOIDED,
    REVERSED;

    /** Actions on a payment or expense. */
    public enum Action {
        EDIT,
        DELETE,
        POST,
        VOID,
        REVERSE,
        ALLOCATE
    }

    private static final Map<Action, Set<DocumentStatus>> FROM = Map.of(
            Action.EDIT, Set.of(DRAFT),
            Action.DELETE, Set.of(DRAFT),
            Action.POST, Set.of(DRAFT),
            Action.VOID, Set.of(POSTED),
            Action.REVERSE, Set.of(POSTED),
            Action.ALLOCATE, Set.of(POSTED));

    public boolean allows(Action action) {
        return FROM.get(action).contains(this);
    }

    public DocumentStatus apply(Action action) {
        if (!allows(action)) {
            throw new IllegalStateException(action + " is not allowed for a " + this + " document");
        }
        return switch (action) {
            case POST -> POSTED;
            case VOID -> VOIDED;
            case REVERSE -> REVERSED;
            case EDIT, DELETE, ALLOCATE -> this;
        };
    }
}
