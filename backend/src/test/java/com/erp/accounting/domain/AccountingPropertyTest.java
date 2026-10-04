package com.erp.accounting.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.erp.accounting.domain.EntryBalance.Amounts;
import com.erp.accounting.domain.Settlement.Open;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.RepetitionInfo;

/**
 * Property checks over seeded random inputs (reproducible: the seed is the repetition number):
 * balancing is exact in decimal arithmetic, a one-unit change unbalances any entry, a rounding line
 * restores balance, and settling an item in any number of partial allocations reduces its base by
 * exactly its base amount — no residue in the control account — with the FX difference equal to the
 * difference of the two base reductions.
 */
class AccountingPropertyTest {

    private static final BigDecimal UNIT = new BigDecimal("0.0001");

    @RepeatedTest(200)
    void balancedEntriesStayBalancedAndAnyUnitUnbalancesThem(RepetitionInfo repetition) {
        Random random = new Random(repetition.getCurrentRepetition());
        List<Amounts> lines = new ArrayList<>();
        BigDecimal debit = BigDecimal.ZERO;
        int debits = 1 + random.nextInt(6);
        for (int i = 0; i < debits; i++) {
            BigDecimal amount = amount(random);
            lines.add(Amounts.debit(amount));
            debit = debit.add(amount);
        }
        // Credits split the same total in random parts.
        BigDecimal left = debit;
        int credits = 1 + random.nextInt(6);
        for (int i = 0; i < credits - 1 && left.compareTo(UNIT) > 0; i++) {
            BigDecimal part = left.multiply(BigDecimal.valueOf(random.nextInt(90) + 5))
                    .divide(BigDecimal.valueOf(100), 4, RoundingMode.DOWN)
                    .max(UNIT);
            if (part.compareTo(left) >= 0) {
                break;
            }
            lines.add(Amounts.credit(part));
            left = left.subtract(part);
        }
        lines.add(Amounts.credit(left));

        assertThat(EntryBalance.problems(lines)).isEmpty();
        EntryBalance.Totals totals = EntryBalance.totals(lines);
        assertThat(totals.debit()).isEqualByComparingTo(totals.credit());

        int victim = random.nextInt(lines.size());
        Amounts changed = lines.get(victim);
        List<Amounts> tampered = new ArrayList<>(lines);
        tampered.set(
                victim,
                changed.debit().signum() > 0
                        ? Amounts.debit(changed.debit().add(UNIT))
                        : Amounts.credit(changed.credit().add(UNIT)));
        assertThat(EntryBalance.problems(tampered)).singleElement().asString().contains("differ");

        EntryBalance.Totals off = EntryBalance.totals(tampered);
        Amounts rounding =
                EntryBalance.roundingLine(off, new BigDecimal("0.01")).orElseThrow();
        tampered.add(rounding);
        assertThat(EntryBalance.problems(tampered)).isEmpty();
    }

    @RepeatedTest(200)
    void partialAllocationsSettleAnItemWithoutResidue(RepetitionInfo repetition) {
        Random random = new Random(1_000L + repetition.getCurrentRepetition());
        boolean receivable = random.nextBoolean();
        BigDecimal amount = amount(random).setScale(2, RoundingMode.DOWN).max(new BigDecimal("0.01"));
        BigDecimal invoiceRate = rate(random);
        Open invoice = new Open(amount, amount.multiply(invoiceRate).setScale(2, RoundingMode.HALF_UP), invoiceRate);
        BigDecimal targetReduced = BigDecimal.ZERO;
        BigDecimal counterReduced = BigDecimal.ZERO;
        BigDecimal difference = BigDecimal.ZERO;

        while (invoice.amount().signum() > 0) {
            // Each payment has its own rate and pays a random part (the rest at the end).
            BigDecimal part = invoice.amount()
                    .multiply(BigDecimal.valueOf(random.nextInt(100) + 1))
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.UP)
                    .min(invoice.amount());
            BigDecimal paymentRate = rate(random);
            Open payment = new Open(part, part.multiply(paymentRate).setScale(2, RoundingMode.HALF_UP), paymentRate);

            Settlement.Result result = Settlement.allocate(invoice, payment, part, receivable, 2, RoundingMode.HALF_UP);
            assertThat(result.targetBase().scale()).isLessThanOrEqualTo(2);
            assertThat(result.targetBase()).isBetween(BigDecimal.ZERO, invoice.amountBase());
            // A payment allocated in full is reduced by its whole base amount.
            assertThat(result.counterBase()).isEqualByComparingTo(payment.amountBase());
            BigDecimal delta = result.counterBase().subtract(result.targetBase());
            assertThat(result.fxDifferenceBase()).isEqualByComparingTo(receivable ? delta.negate() : delta);

            targetReduced = targetReduced.add(result.targetBase());
            counterReduced = counterReduced.add(result.counterBase());
            difference = difference.add(result.fxDifferenceBase());
            invoice = new Open(
                    invoice.amount().subtract(part), invoice.amountBase().subtract(result.targetBase()), invoiceRate);
        }

        BigDecimal originalBase = amount.multiply(invoiceRate).setScale(2, RoundingMode.HALF_UP);
        assertThat(invoice.amountBase()).isEqualByComparingTo("0");
        assertThat(targetReduced).isEqualByComparingTo(originalBase);
        // What the control account received from payments minus what the invoice put there is the
        // realized difference.
        BigDecimal expected = counterReduced.subtract(originalBase);
        assertThat(difference).isEqualByComparingTo(receivable ? expected.negate() : expected);
    }

    /** A positive amount with up to four decimals, between 0.0001 and 1 000 000. */
    private static BigDecimal amount(Random random) {
        long units = random.nextLong(1, 10_000_000_001L);
        return BigDecimal.valueOf(units, 4);
    }

    /** An exchange rate between 0.5 and 2 with six decimals. */
    private static BigDecimal rate(Random random) {
        return BigDecimal.valueOf(500_000 + random.nextInt(1_500_001), 6);
    }
}
