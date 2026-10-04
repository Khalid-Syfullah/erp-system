package com.erp.hr.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Optional;

/**
 * Leave accrual arithmetic (PRODUCT_SPEC.md §10.1, Q-19). The leave year is the calendar year.
 *
 * <ul>
 *   <li>ANNUAL: the year's entitlement at the year start; an employee hired during the year gets the
 *       share of the months from the hire month on, rounded to half days.
 *   <li>MONTHLY: one twelfth per month the employee is employed on its first day or hired in.
 *   <li>Carry forward: at most {@code maxCarryForward} of a positive balance moves into the new year;
 *       the rest expires.
 * </ul>
 */
public final class LeaveEntitlement {

    private static final BigDecimal TWO = BigDecimal.valueOf(2);
    private static final BigDecimal TWELVE = BigDecimal.valueOf(12);

    /** What moves into the new year and what expires in the old one (both ≥ 0). */
    public record CarryForward(BigDecimal carried, BigDecimal expired) {}

    private LeaveEntitlement() {}

    /** The annual grant of the year for an employee hired on {@code hireDate}; empty when nothing is due. */
    public static Optional<BigDecimal> annualGrant(BigDecimal entitlement, LocalDate hireDate, int year) {
        if (entitlement.signum() <= 0 || hireDate.getYear() > year) {
            return Optional.empty();
        }
        int months = hireDate.getYear() < year ? 12 : 12 - hireDate.getMonthValue() + 1;
        BigDecimal grant =
                halfDays(entitlement.multiply(BigDecimal.valueOf(months)).divide(TWELVE, 6, RoundingMode.HALF_UP));
        return grant.signum() > 0 ? Optional.of(grant) : Optional.empty();
    }

    /** The grant of one month (one twelfth, to hundredths). */
    public static BigDecimal monthlyGrant(BigDecimal entitlement) {
        return entitlement.divide(TWELVE, 2, RoundingMode.HALF_UP);
    }

    /** Whether the month's monthly grant is due for an employee hired on {@code hireDate}. */
    public static boolean monthDue(LocalDate hireDate, int year, int month) {
        LocalDate first = LocalDate.of(year, month, 1);
        return !hireDate.isAfter(first.plusMonths(1).minusDays(1));
    }

    public static CarryForward carryForward(BigDecimal previousBalance, BigDecimal maxCarryForward) {
        if (previousBalance.signum() <= 0) {
            return new CarryForward(BigDecimal.ZERO, BigDecimal.ZERO);
        }
        BigDecimal carried = previousBalance.min(maxCarryForward.max(BigDecimal.ZERO));
        return new CarryForward(carried, previousBalance.subtract(carried));
    }

    /** Rounds to the nearest half day (halves round up). */
    static BigDecimal halfDays(BigDecimal days) {
        return days.multiply(TWO).setScale(0, RoundingMode.HALF_UP).divide(TWO, 2, RoundingMode.UNNECESSARY);
    }
}
