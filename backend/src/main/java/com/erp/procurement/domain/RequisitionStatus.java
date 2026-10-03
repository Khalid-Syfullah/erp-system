package com.erp.procurement.domain;

import java.util.Map;
import java.util.Set;

/**
 * Purchase requisition lifecycle (PRODUCT_SPEC.md §7.2): {@code DRAFT → SUBMITTED → APPROVED |
 * REJECTED}; {@code APPROVED → PARTIALLY_ORDERED → ORDERED} as lines are converted to purchase
 * orders; {@code DRAFT | SUBMITTED | APPROVED → CANCELLED}.
 */
public enum RequisitionStatus {
    DRAFT,
    SUBMITTED,
    APPROVED,
    REJECTED,
    PARTIALLY_ORDERED,
    ORDERED,
    CANCELLED;

    /** Actions on a requisition; {@code ORDER} records conversion progress. */
    public enum Action {
        EDIT,
        DELETE,
        SUBMIT,
        APPROVE,
        REJECT,
        CANCEL,
        ORDER
    }

    private static final Map<Action, Set<RequisitionStatus>> FROM = Map.of(
            Action.EDIT, Set.of(DRAFT),
            Action.DELETE, Set.of(DRAFT),
            Action.SUBMIT, Set.of(DRAFT),
            Action.APPROVE, Set.of(SUBMITTED),
            Action.REJECT, Set.of(SUBMITTED),
            Action.CANCEL, Set.of(DRAFT, SUBMITTED, APPROVED),
            Action.ORDER, Set.of(APPROVED, PARTIALLY_ORDERED, ORDERED));

    public boolean allows(Action action) {
        return FROM.get(action).contains(this);
    }

    /** The state after a fixed transition; {@code ORDER} transitions use {@link #ordered}. */
    public RequisitionStatus apply(Action action) {
        if (!allows(action) || action == Action.ORDER) {
            throw new IllegalStateException(action + " is not allowed for a " + this + " requisition");
        }
        return switch (action) {
            case SUBMIT -> SUBMITTED;
            case APPROVE -> APPROVED;
            case REJECT -> REJECTED;
            case CANCEL -> CANCELLED;
            case EDIT, DELETE, ORDER -> this;
        };
    }

    /** The ordering state from the converted quantities (none, some or all lines fully ordered). */
    public RequisitionStatus ordered(boolean anythingOrdered, boolean everythingOrdered) {
        if (!allows(Action.ORDER)) {
            throw new IllegalStateException("A " + this + " requisition cannot be ordered");
        }
        return everythingOrdered ? ORDERED : anythingOrdered ? PARTIALLY_ORDERED : APPROVED;
    }
}
