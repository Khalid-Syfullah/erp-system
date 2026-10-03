package com.erp.sales.domain;

import java.util.Map;
import java.util.Set;

/**
 * Sales order lifecycle (PRODUCT_SPEC.md §9.2):
 *
 * <pre>
 * DRAFT → CONFIRMED (numbered, credit-checked, stock reserved)
 * CONFIRMED → PARTIALLY_DELIVERED → DELIVERED       (deliveries)
 * DELIVERED → CLOSED (fully invoiced or manually); PARTIALLY_DELIVERED → CLOSED (manually)
 * DRAFT → CANCELLED; CONFIRMED → CANCELLED (only while nothing was delivered or invoiced)
 * </pre>
 *
 * An order without stockable lines has nothing to deliver and closes from CONFIRMED. Confirmed
 * orders are never edited; cancelling or closing releases the remaining reservations.
 */
public enum SalesOrderStatus {
    DRAFT,
    CONFIRMED,
    PARTIALLY_DELIVERED,
    DELIVERED,
    CLOSED,
    CANCELLED;

    /** Actions on an order; {@code DELIVER} records delivery progress. */
    public enum Action {
        EDIT,
        DELETE,
        CONFIRM,
        CANCEL,
        CLOSE,
        RESERVE,
        DELIVER,
        INVOICE
    }

    private static final Map<Action, Set<SalesOrderStatus>> FROM = Map.of(
            Action.EDIT, Set.of(DRAFT),
            Action.DELETE, Set.of(DRAFT),
            Action.CONFIRM, Set.of(DRAFT),
            Action.CANCEL, Set.of(DRAFT, CONFIRMED),
            Action.CLOSE, Set.of(CONFIRMED, PARTIALLY_DELIVERED, DELIVERED),
            Action.RESERVE, Set.of(CONFIRMED, PARTIALLY_DELIVERED),
            Action.DELIVER, Set.of(CONFIRMED, PARTIALLY_DELIVERED),
            Action.INVOICE, Set.of(CONFIRMED, PARTIALLY_DELIVERED, DELIVERED, CLOSED));

    public boolean allows(Action action) {
        return FROM.get(action).contains(this);
    }

    public SalesOrderStatus apply(Action action) {
        if (!allows(action) || action == Action.DELIVER) {
            throw new IllegalStateException(action + " is not allowed for a " + this + " sales order");
        }
        return switch (action) {
            case CONFIRM -> CONFIRMED;
            case CANCEL -> CANCELLED;
            case CLOSE -> CLOSED;
            case EDIT, DELETE, RESERVE, DELIVER, INVOICE -> this;
        };
    }

    /** The delivery state from the stockable lines' delivered quantities. */
    public SalesOrderStatus delivered(boolean anythingDelivered, boolean everythingDelivered) {
        if (!allows(Action.DELIVER)) {
            throw new IllegalStateException("A " + this + " sales order cannot be delivered");
        }
        return everythingDelivered ? DELIVERED : anythingDelivered ? PARTIALLY_DELIVERED : CONFIRMED;
    }

    /** Invoicing progress of an order (tracked separately from its status). */
    public enum Invoicing {
        NOT_INVOICED,
        PARTIALLY_INVOICED,
        INVOICED;

        public static Invoicing of(boolean anythingInvoiced, boolean everythingInvoiced) {
            return everythingInvoiced ? INVOICED : anythingInvoiced ? PARTIALLY_INVOICED : NOT_INVOICED;
        }
    }
}
