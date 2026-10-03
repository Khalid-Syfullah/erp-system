package com.erp.inventory.domain;

import java.util.Map;
import java.util.Set;

/**
 * Physical count state machine (INV-8): {@code DRAFT → IN_PROGRESS} (start: snapshot),
 * {@code → COMPLETED} (all lines counted), {@code → POSTED} (difference to the current quantity
 * posted as a COUNT_ADJUSTMENT). Unposted counts can be cancelled.
 */
public enum CountStatus {
    DRAFT,
    IN_PROGRESS,
    COMPLETED,
    POSTED,
    CANCELLED;

    /** Actions on a count. */
    public enum Action {
        START,
        ENTER,
        COMPLETE,
        POST,
        CANCEL
    }

    private static final Map<Action, Set<CountStatus>> FROM = Map.of(
            Action.START, Set.of(DRAFT),
            Action.ENTER, Set.of(IN_PROGRESS),
            Action.COMPLETE, Set.of(IN_PROGRESS),
            Action.POST, Set.of(COMPLETED),
            Action.CANCEL, Set.of(DRAFT, IN_PROGRESS, COMPLETED));

    public boolean allows(Action action) {
        return FROM.get(action).contains(this);
    }

    public CountStatus apply(Action action) {
        if (!allows(action)) {
            throw new IllegalStateException(action + " is not allowed for a " + this + " count");
        }
        return switch (action) {
            case START -> IN_PROGRESS;
            case ENTER -> this;
            case COMPLETE -> COMPLETED;
            case POST -> POSTED;
            case CANCEL -> CANCELLED;
        };
    }
}
