package com.erp.hr.domain;

import java.util.Map;
import java.util.Set;

/**
 * Employee lifecycle (PRODUCT_SPEC.md §10.1): {@code ONBOARDING → ACTIVE ⇄ ON_LEAVE → TERMINATED}.
 * Termination is possible from every non-terminal state and is final.
 */
public enum EmployeeStatus {
    ONBOARDING,
    ACTIVE,
    ON_LEAVE,
    TERMINATED;

    private static final Map<EmployeeStatus, Set<EmployeeStatus>> TRANSITIONS = Map.of(
            ONBOARDING, Set.of(ACTIVE, TERMINATED),
            ACTIVE, Set.of(ON_LEAVE, TERMINATED),
            ON_LEAVE, Set.of(ACTIVE, TERMINATED),
            TERMINATED, Set.of());

    public boolean canTransitionTo(EmployeeStatus target) {
        return TRANSITIONS.get(this).contains(target);
    }

    public boolean isTerminal() {
        return this == TERMINATED;
    }
}
