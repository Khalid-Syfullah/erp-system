package com.erp.accounting.domain;

import java.util.Map;
import java.util.Set;

/**
 * Accounting period lifecycle (PRODUCT_SPEC.md §8.5): {@code OPEN → SOFT_CLOSED → CLOSED} (closing
 * directly from OPEN is allowed); a soft-closed or closed period is reopened back to OPEN.
 */
public enum PeriodStatus {
    OPEN,
    SOFT_CLOSED,
    CLOSED;

    /** Actions on a period. */
    public enum Action {
        SOFT_CLOSE,
        CLOSE,
        REOPEN
    }

    private static final Map<Action, Set<PeriodStatus>> FROM = Map.of(
            Action.SOFT_CLOSE, Set.of(OPEN),
            Action.CLOSE, Set.of(OPEN, SOFT_CLOSED),
            Action.REOPEN, Set.of(SOFT_CLOSED, CLOSED));

    public boolean allows(Action action) {
        return FROM.get(action).contains(this);
    }

    public PeriodStatus apply(Action action) {
        if (!allows(action)) {
            throw new IllegalStateException(action + " is not allowed for a " + this + " period");
        }
        return switch (action) {
            case SOFT_CLOSE -> SOFT_CLOSED;
            case CLOSE -> CLOSED;
            case REOPEN -> OPEN;
        };
    }

    /**
     * Whether an entry may be posted into a period in this state: OPEN always, SOFT_CLOSED only for
     * privileged users (ACC-4), CLOSED never.
     */
    public boolean takesPostings(boolean privileged) {
        return this == OPEN || (this == SOFT_CLOSED && privileged);
    }
}
