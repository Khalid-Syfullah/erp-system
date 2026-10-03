package com.erp.hr.domain;

import java.util.List;
import java.util.UUID;

/**
 * Detects reporting-line cycles over time. A new line "employee reports to manager during P" closes a
 * cycle if, somewhere within P, the manager's own chain of managers reaches the employee. The chain is
 * followed interval by interval: each assignment of the current manager that overlaps the remaining
 * period contributes its manager for the overlapping part only.
 */
public final class ReportingLines {

    /** Maximum chain length that is followed; deeper organizations are treated as invalid. */
    public static final int MAX_DEPTH = 50;

    /** A period during which an employee reports to {@code managerId}. */
    public record ManagerSpan(UUID managerId, EffectivePeriod period) {}

    /** Reporting lines of one employee that overlap the given period. */
    @FunctionalInterface
    public interface ManagerLookup {
        List<ManagerSpan> managersOf(UUID employeeId, EffectivePeriod period);
    }

    /** Outcome of the check. */
    public enum Result {
        OK,
        CYCLE,
        TOO_DEEP
    }

    private ReportingLines() {}

    public static Result check(UUID employeeId, UUID managerId, EffectivePeriod period, ManagerLookup lookup) {
        return follow(managerId, period, employeeId, lookup, 0);
    }

    private static Result follow(UUID current, EffectivePeriod period, UUID target, ManagerLookup lookup, int depth) {
        if (current.equals(target)) {
            return Result.CYCLE;
        }
        if (depth >= MAX_DEPTH) {
            return Result.TOO_DEEP;
        }
        for (ManagerSpan span : lookup.managersOf(current, period)) {
            var overlap = span.period().intersect(period);
            if (overlap.isEmpty()) {
                continue;
            }
            Result result = follow(span.managerId(), overlap.get(), target, lookup, depth + 1);
            if (result != Result.OK) {
                return result;
            }
        }
        return Result.OK;
    }
}
