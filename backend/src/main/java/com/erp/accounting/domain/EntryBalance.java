package com.erp.accounting.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The journal rules that hold for every entry (PRODUCT_SPEC.md §8.3): each line has exactly one
 * non-zero, non-negative side (ACC-2); an entry has at least two lines and Σ debit = Σ credit in
 * base currency (ACC-1). System entries whose sides differ by no more than the company's rounding
 * tolerance are balanced with one ROUNDING_DIFFERENCE line.
 */
public final class EntryBalance {

    /** One side of a line: {@code debit} or {@code credit}, the other zero. */
    public record Amounts(BigDecimal debit, BigDecimal credit) {

        public static Amounts debit(BigDecimal amount) {
            return new Amounts(amount, BigDecimal.ZERO);
        }

        public static Amounts credit(BigDecimal amount) {
            return new Amounts(BigDecimal.ZERO, amount);
        }

        /** A signed amount: positive debits, negative credits. */
        public static Amounts signed(BigDecimal amount) {
            return amount.signum() >= 0 ? debit(amount) : credit(amount.negate());
        }

        public BigDecimal signed() {
            return debit.subtract(credit);
        }
    }

    /** Totals of an entry and its imbalance (debit − credit). */
    public record Totals(BigDecimal debit, BigDecimal credit) {

        public BigDecimal difference() {
            return debit.subtract(credit);
        }

        public boolean balanced() {
            return debit.compareTo(credit) == 0;
        }
    }

    private EntryBalance() {}

    /** Problems of the entry's lines and totals, described for an error response; empty when valid. */
    public static List<String> problems(List<Amounts> lines) {
        List<String> problems = new ArrayList<>();
        if (lines.size() < 2) {
            problems.add("An entry needs at least two lines.");
        }
        for (int i = 0; i < lines.size(); i++) {
            Amounts a = lines.get(i);
            boolean oneSide = (a.debit().signum() > 0 && a.credit().signum() == 0)
                    || (a.credit().signum() > 0 && a.debit().signum() == 0);
            if (!oneSide) {
                problems.add("Line " + (i + 1) + " must have exactly one positive side (debit or credit).");
            }
        }
        Totals totals = totals(lines);
        if (!totals.balanced()) {
            problems.add("Debits (" + totals.debit().toPlainString() + ") and credits ("
                    + totals.credit().toPlainString() + ") differ.");
        }
        return problems;
    }

    public static Totals totals(List<Amounts> lines) {
        BigDecimal debit = BigDecimal.ZERO;
        BigDecimal credit = BigDecimal.ZERO;
        for (Amounts a : lines) {
            debit = debit.add(a.debit());
            credit = credit.add(a.credit());
        }
        return new Totals(debit, credit);
    }

    /**
     * The line that balances a system entry off by at most {@code tolerance}: a credit when debits
     * exceed credits, a debit otherwise; empty when the entry is balanced.
     *
     * @throws IllegalArgumentException when the difference exceeds the tolerance
     */
    public static Optional<Amounts> roundingLine(Totals totals, BigDecimal tolerance) {
        BigDecimal difference = totals.difference();
        if (difference.signum() == 0) {
            return Optional.empty();
        }
        if (difference.abs().compareTo(tolerance) > 0) {
            throw new IllegalArgumentException("The entry is off by " + difference.toPlainString()
                    + ", more than the rounding tolerance " + tolerance.toPlainString());
        }
        return Optional.of(Amounts.signed(difference.negate()));
    }
}
