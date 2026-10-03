package com.erp.inventory.domain;

import java.util.Map;

/**
 * Stock movement state machine (PRODUCT_SPEC.md §6.5): {@code DRAFT → POSTED} (post) and
 * {@code DRAFT → CANCELLED} (cancel). Posted movements are corrected by reversal, never changed.
 */
public enum MovementStatus {
    DRAFT,
    POSTED,
    CANCELLED;

    /** Actions on a movement. */
    public enum Action {
        EDIT,
        POST,
        CANCEL,
        DELETE,
        REVERSE
    }

    private static final Map<Action, MovementStatus> REQUIRED = Map.of(
            Action.EDIT, DRAFT,
            Action.POST, DRAFT,
            Action.CANCEL, DRAFT,
            Action.DELETE, DRAFT,
            Action.REVERSE, POSTED);

    public boolean allows(Action action) {
        return REQUIRED.get(action) == this;
    }

    /** The state after the action; throws if the action is not allowed in this state. */
    public MovementStatus apply(Action action) {
        if (!allows(action)) {
            throw new IllegalStateException(action + " is not allowed for a " + this + " movement");
        }
        return switch (action) {
            case POST -> POSTED;
            case CANCEL -> CANCELLED;
            case EDIT, DELETE, REVERSE -> this;
        };
    }
}
