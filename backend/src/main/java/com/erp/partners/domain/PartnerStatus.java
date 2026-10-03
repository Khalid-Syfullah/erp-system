package com.erp.partners.domain;

import java.util.Map;
import java.util.Set;

/**
 * Partner status (PRODUCT_SPEC.md §5): only {@code ACTIVE} partners are used on new documents.
 * {@code INACTIVE} is a partner no longer dealt with; {@code BLOCKED} is a stop (fraud, disputes).
 */
public enum PartnerStatus {
    ACTIVE,
    INACTIVE,
    BLOCKED;

    /** Status actions. */
    public enum Action {
        ACTIVATE,
        DEACTIVATE,
        BLOCK
    }

    private static final Map<Action, Set<PartnerStatus>> FROM = Map.of(
            Action.ACTIVATE, Set.of(INACTIVE, BLOCKED),
            Action.DEACTIVATE, Set.of(ACTIVE),
            Action.BLOCK, Set.of(ACTIVE, INACTIVE));

    public boolean allows(Action action) {
        return FROM.get(action).contains(this);
    }

    public PartnerStatus apply(Action action) {
        if (!allows(action)) {
            throw new IllegalStateException(action + " is not allowed for a " + this + " partner");
        }
        return switch (action) {
            case ACTIVATE -> ACTIVE;
            case DEACTIVATE -> INACTIVE;
            case BLOCK -> BLOCKED;
        };
    }
}
