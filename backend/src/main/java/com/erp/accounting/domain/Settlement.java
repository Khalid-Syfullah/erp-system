package com.erp.accounting.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Allocation arithmetic (PRODUCT_SPEC.md §8.7, ADR-022): an allocation reduces the open amount of a
 * positive item (invoice, bill) and of a negative item settling it (payment, credit or debit note,
 * on-account remainder) by the same amount in their common currency. Each item's base amount falls
 * by the allocated amount at its own rate — by exactly what is left when the item is settled in
 * full — so the control account always equals Σ open items in base currency. The difference of the
 * two base reductions is the realized FX difference.
 */
public final class Settlement {

    /** An item's remaining amounts, as absolute values, and its rate. */
    public record Open(BigDecimal amount, BigDecimal amountBase, BigDecimal rate) {}

    /**
     * The result of an allocation.
     *
     * @param targetBase base reduction of the positive item
     * @param counterBase base reduction of the negative item
     * @param fxDifferenceBase realized difference from the control account's view: a loss (+) or a
     *     gain (−) in base currency
     */
    public record Result(BigDecimal targetBase, BigDecimal counterBase, BigDecimal fxDifferenceBase) {}

    private Settlement() {}

    /**
     * @param receivable whether the items are receivables (AR; else payables, AP)
     * @param scale base currency minor units
     */
    public static Result allocate(
            Open target, Open counter, BigDecimal amount, boolean receivable, int scale, RoundingMode rounding) {
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("The allocated amount must be positive");
        }
        if (amount.compareTo(target.amount()) > 0 || amount.compareTo(counter.amount()) > 0) {
            throw new IllegalArgumentException("The allocated amount exceeds what is open");
        }
        BigDecimal targetBase = baseReduction(target, amount, scale, rounding);
        BigDecimal counterBase = baseReduction(counter, amount, scale, rounding);
        // AR (debit-normal): the control account falls by counterBase − targetBase too much → gain when > 0.
        // AP (credit-normal): the same difference is a loss when > 0.
        BigDecimal delta = counterBase.subtract(targetBase);
        return new Result(targetBase, counterBase, receivable ? delta.negate() : delta);
    }

    /** The base amount by which an item falls: proportional, or all that is left when it is settled. */
    public static BigDecimal baseReduction(Open item, BigDecimal amount, int scale, RoundingMode rounding) {
        if (amount.compareTo(item.amount()) == 0) {
            return item.amountBase();
        }
        BigDecimal base = amount.multiply(item.rate()).setScale(scale, rounding);
        return base.min(item.amountBase());
    }
}
