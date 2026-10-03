package com.erp.sales.domain;

import java.util.Map;
import java.util.Set;

/**
 * Quotation lifecycle (PRODUCT_SPEC.md §9.2): {@code DRAFT → SENT → ACCEPTED | REJECTED | EXPIRED}
 * (EXPIRED by the daily job after {@code valid_until}); {@code DRAFT | SENT → CANCELLED}. Accepting
 * creates a draft sales order.
 */
public enum QuotationStatus {
    DRAFT,
    SENT,
    ACCEPTED,
    REJECTED,
    EXPIRED,
    CANCELLED;

    /** Actions on a quotation. */
    public enum Action {
        EDIT,
        DELETE,
        SEND,
        ACCEPT,
        REJECT,
        EXPIRE,
        CANCEL
    }

    private static final Map<Action, Set<QuotationStatus>> FROM = Map.of(
            Action.EDIT, Set.of(DRAFT),
            Action.DELETE, Set.of(DRAFT),
            Action.SEND, Set.of(DRAFT),
            Action.ACCEPT, Set.of(SENT),
            Action.REJECT, Set.of(SENT),
            Action.EXPIRE, Set.of(SENT),
            Action.CANCEL, Set.of(DRAFT, SENT));

    public boolean allows(Action action) {
        return FROM.get(action).contains(this);
    }

    public QuotationStatus apply(Action action) {
        if (!allows(action)) {
            throw new IllegalStateException(action + " is not allowed for a " + this + " quotation");
        }
        return switch (action) {
            case SEND -> SENT;
            case ACCEPT -> ACCEPTED;
            case REJECT -> REJECTED;
            case EXPIRE -> EXPIRED;
            case CANCEL -> CANCELLED;
            case EDIT, DELETE -> this;
        };
    }
}
