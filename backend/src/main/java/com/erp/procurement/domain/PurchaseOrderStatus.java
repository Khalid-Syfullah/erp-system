package com.erp.procurement.domain;

import java.util.Map;
import java.util.Set;

/**
 * Purchase order lifecycle (PRODUCT_SPEC.md §7.2):
 *
 * <pre>
 * DRAFT → PENDING_APPROVAL (submit, numbered) → APPROVED (approve) | DRAFT (reject)
 * APPROVED ⇄ PARTIALLY_RECEIVED ⇄ RECEIVED     (receipts and returns move along these)
 * RECEIVED | PARTIALLY_RECEIVED → CLOSED       (manual close; RECEIVED also when fully billed)
 * DRAFT | PENDING_APPROVAL | APPROVED → CANCELLED (APPROVED only while nothing was received)
 * </pre>
 *
 * An approved order without stockable lines has nothing to receive and closes from APPROVED.
 * Approved orders are never edited.
 */
public enum PurchaseOrderStatus {
    DRAFT,
    PENDING_APPROVAL,
    APPROVED,
    PARTIALLY_RECEIVED,
    RECEIVED,
    CLOSED,
    CANCELLED;

    /** Actions on a purchase order; {@code RECEIVE} records receipt progress. */
    public enum Action {
        EDIT,
        DELETE,
        SUBMIT,
        APPROVE,
        REJECT,
        CANCEL,
        CLOSE,
        RECEIVE,
        BILL
    }

    private static final Set<PurchaseOrderStatus> OPEN_FOR_RECEIPT = Set.of(APPROVED, PARTIALLY_RECEIVED, RECEIVED);

    private static final Map<Action, Set<PurchaseOrderStatus>> FROM = Map.of(
            Action.EDIT, Set.of(DRAFT),
            Action.DELETE, Set.of(DRAFT),
            Action.SUBMIT, Set.of(DRAFT),
            Action.APPROVE, Set.of(PENDING_APPROVAL),
            Action.REJECT, Set.of(PENDING_APPROVAL),
            Action.CANCEL, Set.of(DRAFT, PENDING_APPROVAL, APPROVED),
            Action.CLOSE, Set.of(APPROVED, PARTIALLY_RECEIVED, RECEIVED),
            Action.RECEIVE, OPEN_FOR_RECEIPT,
            Action.BILL, Set.of(APPROVED, PARTIALLY_RECEIVED, RECEIVED, CLOSED));

    public boolean allows(Action action) {
        return FROM.get(action).contains(this);
    }

    public PurchaseOrderStatus apply(Action action) {
        if (!allows(action) || action == Action.RECEIVE) {
            throw new IllegalStateException(action + " is not allowed for a " + this + " purchase order");
        }
        return switch (action) {
            case SUBMIT -> PENDING_APPROVAL;
            case APPROVE -> APPROVED;
            case REJECT -> DRAFT;
            case CANCEL -> CANCELLED;
            case CLOSE -> CLOSED;
            case EDIT, DELETE, RECEIVE, BILL -> this;
        };
    }

    /** The receipt state from the stockable lines' net received quantities. */
    public PurchaseOrderStatus received(boolean anythingReceived, boolean everythingReceived) {
        if (!allows(Action.RECEIVE)) {
            throw new IllegalStateException("A " + this + " purchase order cannot receive goods");
        }
        return everythingReceived ? RECEIVED : anythingReceived ? PARTIALLY_RECEIVED : APPROVED;
    }

    /** Billing progress of an order (tracked separately from its status). */
    public enum Billing {
        NOT_BILLED,
        PARTIALLY_BILLED,
        BILLED;

        public static Billing of(boolean anythingBilled, boolean everythingBilled) {
            return everythingBilled ? BILLED : anythingBilled ? PARTIALLY_BILLED : NOT_BILLED;
        }
    }
}
