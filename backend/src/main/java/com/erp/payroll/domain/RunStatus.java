package com.erp.payroll.domain;

import java.util.Map;
import java.util.Set;

/**
 * Payroll run lifecycle (PRODUCT_SPEC.md §11.1): {@code DRAFT → CALCULATING → CALCULATED → APPROVED →
 * POSTED → PAID}; a failed calculation returns to DRAFT; a calculated run is recalculated, and an
 * approved one unapproved before posting; drafts and calculated runs can be cancelled.
 */
public enum RunStatus {
    DRAFT,
    CALCULATING,
    CALCULATED,
    APPROVED,
    POSTED,
    PAID,
    CANCELLED;

    /** Actions on a run. */
    public enum Action {
        CALCULATE,
        COMPLETE,
        FAIL,
        RESET,
        APPROVE,
        UNAPPROVE,
        POST,
        PAY,
        CANCEL
    }

    private static final Map<Action, Set<RunStatus>> FROM = Map.of(
            Action.CALCULATE, Set.of(DRAFT, CALCULATED),
            Action.COMPLETE, Set.of(CALCULATING),
            Action.FAIL, Set.of(CALCULATING),
            Action.RESET, Set.of(CALCULATED),
            Action.APPROVE, Set.of(CALCULATED),
            Action.UNAPPROVE, Set.of(APPROVED),
            Action.POST, Set.of(APPROVED),
            Action.PAY, Set.of(POSTED),
            Action.CANCEL, Set.of(DRAFT, CALCULATED));

    public boolean allows(Action action) {
        return FROM.get(action).contains(this);
    }

    public RunStatus apply(Action action) {
        if (!allows(action)) {
            throw new IllegalStateException(action + " is not allowed for a " + this + " payroll run");
        }
        return switch (action) {
            case CALCULATE -> CALCULATING;
            case COMPLETE -> CALCULATED;
            case FAIL, RESET -> DRAFT;
            case APPROVE -> APPROVED;
            case UNAPPROVE -> CALCULATED;
            case POST -> POSTED;
            case PAY -> PAID;
            case CANCEL -> CANCELLED;
        };
    }

    /** Payslips of runs in these states are final and visible in self-service. */
    public boolean released() {
        return this == POSTED || this == PAID;
    }
}
