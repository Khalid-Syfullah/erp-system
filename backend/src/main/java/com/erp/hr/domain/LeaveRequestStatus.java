package com.erp.hr.domain;

import java.util.Map;
import java.util.Set;

/**
 * Leave request lifecycle (PRODUCT_SPEC.md §10.1): {@code DRAFT → SUBMITTED → APPROVED | REJECTED};
 * drafts, submitted and approved requests can be cancelled (an approved one gives its days back).
 */
public enum LeaveRequestStatus {
    DRAFT,
    SUBMITTED,
    APPROVED,
    REJECTED,
    CANCELLED;

    /** Actions on a request. */
    public enum Action {
        EDIT,
        SUBMIT,
        APPROVE,
        REJECT,
        CANCEL
    }

    private static final Map<Action, Set<LeaveRequestStatus>> FROM = Map.of(
            Action.EDIT, Set.of(DRAFT),
            Action.SUBMIT, Set.of(DRAFT),
            Action.APPROVE, Set.of(SUBMITTED),
            Action.REJECT, Set.of(SUBMITTED),
            Action.CANCEL, Set.of(DRAFT, SUBMITTED, APPROVED));

    public boolean allows(Action action) {
        return FROM.get(action).contains(this);
    }

    public LeaveRequestStatus apply(Action action) {
        if (!allows(action)) {
            throw new IllegalStateException(action + " is not allowed for a " + this + " leave request");
        }
        return switch (action) {
            case EDIT -> this;
            case SUBMIT -> SUBMITTED;
            case APPROVE -> APPROVED;
            case REJECT -> REJECTED;
            case CANCEL -> CANCELLED;
        };
    }
}
