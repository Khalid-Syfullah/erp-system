package com.erp.accounting.domain;

/**
 * Journal entry lifecycle (PRODUCT_SPEC.md §8.4): {@code DRAFT → POSTED}; a draft may be edited or
 * deleted; a posted entry is corrected only by a reversal (a new entry), which leaves it POSTED.
 */
public enum EntryStatus {
    DRAFT,
    POSTED;

    /** Actions on an entry. */
    public enum Action {
        EDIT,
        DELETE,
        POST,
        REVERSE
    }

    public boolean allows(Action action) {
        return switch (action) {
            case EDIT, DELETE, POST -> this == DRAFT;
            case REVERSE -> this == POSTED;
        };
    }

    public EntryStatus apply(Action action) {
        if (!allows(action)) {
            throw new IllegalStateException(action + " is not allowed for a " + this + " journal entry");
        }
        return action == Action.POST ? POSTED : this;
    }
}
