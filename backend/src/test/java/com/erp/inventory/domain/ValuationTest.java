package com.erp.inventory.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.platform.money.RoundingPolicy;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Random;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.RepetitionInfo;
import org.junit.jupiter.api.Test;

class ValuationTest {

    private static final RoundingPolicy CENTS = new RoundingPolicy(2, RoundingMode.HALF_UP);

    @Test
    void receivingAddsTheRoundedValue() {
        Valuation.Movement in = Valuation.EMPTY.receive(new BigDecimal("3"), new BigDecimal("0.3333335"), CENTS);
        assertThat(in.value()).isEqualByComparingTo("1.00");
        assertThat(in.after().quantity()).isEqualByComparingTo("3");
        assertThat(in.after().averageCost()).isEqualByComparingTo("0.333333");
    }

    @Test
    void issuingTakesTheAverageAndEverythingTakesTheRest() {
        Valuation v = new Valuation(new BigDecimal("3"), new BigDecimal("5.00"));
        Valuation.Movement one = v.issue(BigDecimal.ONE, CENTS);
        assertThat(one.value()).isEqualByComparingTo("1.67");
        Valuation.Movement rest = one.after().issue(new BigDecimal("2"), CENTS);
        assertThat(rest.value()).isEqualByComparingTo("3.33");
        assertThat(rest.after().quantity()).isZero();
        assertThat(rest.after().value()).isZero();
        assertThat(rest.after().averageCost()).isNull();
    }

    @Test
    void cannotValueMoreThanThereIsOrNothing() {
        Valuation v = new Valuation(BigDecimal.ONE, BigDecimal.TEN);
        assertThatThrownBy(() -> v.valueOf(new BigDecimal("2"), CENTS)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> v.valueOf(BigDecimal.ZERO, CENTS)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validityRules() {
        assertThat(Valuation.EMPTY.isValid()).isTrue();
        assertThat(new Valuation(BigDecimal.ZERO, BigDecimal.ONE).isValid())
                .as("residual value")
                .isFalse();
        assertThat(new Valuation(BigDecimal.ONE.negate(), BigDecimal.ZERO).isValid())
                .isFalse();
        assertThat(new Valuation(BigDecimal.ONE, BigDecimal.ONE.negate()).isValid())
                .isFalse();
        assertThat(new Valuation(BigDecimal.ONE, BigDecimal.ZERO).plus(BigDecimal.ONE, BigDecimal.TEN))
                .isEqualTo(new Valuation(new BigDecimal("2"), BigDecimal.TEN));
    }

    @Test
    void unitCostOfAMovedValue() {
        assertThat(Valuation.unitCost(new BigDecimal("-10.00"), new BigDecimal("-3")))
                .isEqualByComparingTo("3.333333");
    }

    /**
     * INV-4/INV-5 as a property: for any sequence of receipts and issues, the valuation stays valid,
     * its value equals the sum of the moved values, and issuing the rest leaves nothing behind.
     */
    @RepeatedTest(200)
    void anySequenceKeepsTheLedgerAndValuationInStep(RepetitionInfo repetition) {
        Random random = new Random(repetition.getCurrentRepetition());
        RoundingPolicy rounding = new RoundingPolicy(
                random.nextInt(4), random.nextBoolean() ? RoundingMode.HALF_UP : RoundingMode.HALF_EVEN);
        Valuation v = Valuation.EMPTY;
        BigDecimal ledger = BigDecimal.ZERO;
        for (int step = 0; step < 40; step++) {
            boolean receive = v.quantity().signum() == 0 || random.nextInt(3) > 0;
            if (receive) {
                BigDecimal qty = BigDecimal.valueOf(1 + random.nextInt(1_000_000), random.nextInt(4));
                BigDecimal cost = BigDecimal.valueOf(random.nextInt(10_000_000), 6);
                Valuation.Movement m = v.receive(qty, cost, rounding);
                ledger = ledger.add(m.value());
                v = m.after();
            } else {
                BigDecimal qty = v.quantity()
                        .multiply(BigDecimal.valueOf(1 + random.nextInt(100)))
                        .divide(BigDecimal.valueOf(100), 6, RoundingMode.DOWN);
                if (qty.signum() == 0) {
                    continue;
                }
                Valuation.Movement m = v.issue(qty, rounding);
                ledger = ledger.subtract(m.value());
                v = m.after();
            }
            assertThat(v.isValid()).as("valid after step %d: %s", step, v).isTrue();
            assertThat(v.value()).isEqualByComparingTo(ledger);
            assertThat(v.value().scale()).isLessThanOrEqualTo(Math.max(rounding.minorUnits(), 0));
        }
        if (v.quantity().signum() > 0) {
            Valuation.Movement last = v.issue(v.quantity(), rounding);
            ledger = ledger.subtract(last.value());
            assertThat(last.after())
                    .isEqualTo(new Valuation(
                            BigDecimal.ZERO.setScale(v.quantity().scale()),
                            last.after().value()));
            assertThat(last.after().value()).isZero();
        }
        assertThat(ledger).isZero();
    }
}
