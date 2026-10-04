package com.erp.payroll.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.payroll.domain.statutory.FlatPercentageRule;
import com.erp.payroll.domain.statutory.NoStatutoryRule;
import com.erp.payroll.domain.statutory.StatutoryRule;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PayrollDomainTest {

    @Test
    void periodsCoverTheYearForEveryFrequency() {
        List<PayPeriods.Period> monthly = PayPeriods.forYear("MONTHLY", null, 2028, 0);
        assertThat(monthly).hasSize(12);
        assertThat(monthly.get(1).end()).isEqualTo(LocalDate.of(2028, 2, 29));
        List<PayPeriods.Period> semi = PayPeriods.forYear("SEMI_MONTHLY", null, 2027, 3);
        assertThat(semi).hasSize(24);
        assertThat(semi.get(0).end()).isEqualTo(LocalDate.of(2027, 1, 15));
        assertThat(semi.get(1).start()).isEqualTo(LocalDate.of(2027, 1, 16));
        assertThat(semi.get(1).payDate()).isEqualTo(LocalDate.of(2027, 2, 3));
        List<PayPeriods.Period> biweekly = PayPeriods.forYear("BIWEEKLY", LocalDate.of(2026, 12, 28), 2027, 0);
        assertThat(biweekly.getFirst().start()).isEqualTo(LocalDate.of(2027, 1, 11));
        assertThat(biweekly)
                .allSatisfy(p -> assertThat(p.end()).isEqualTo(p.start().plusDays(13)));
        for (int i = 1; i < biweekly.size(); i++) {
            assertThat(biweekly.get(i).start())
                    .isEqualTo(biweekly.get(i - 1).end().plusDays(1));
        }
        assertThat(PayPeriods.forYear("WEEKLY", LocalDate.of(2027, 1, 4), 2027, 0))
                .hasSize(52);
        assertThatThrownBy(() -> PayPeriods.forYear("WEEKLY", null, 2027, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void runsFollowTheirLifecycle() {
        assertThat(RunStatus.DRAFT.apply(RunStatus.Action.CALCULATE)).isEqualTo(RunStatus.CALCULATING);
        assertThat(RunStatus.CALCULATED.apply(RunStatus.Action.CALCULATE)).isEqualTo(RunStatus.CALCULATING);
        assertThat(RunStatus.CALCULATING.apply(RunStatus.Action.FAIL)).isEqualTo(RunStatus.DRAFT);
        assertThat(RunStatus.CALCULATED.apply(RunStatus.Action.APPROVE)).isEqualTo(RunStatus.APPROVED);
        assertThat(RunStatus.APPROVED.apply(RunStatus.Action.UNAPPROVE)).isEqualTo(RunStatus.CALCULATED);
        assertThat(RunStatus.APPROVED.apply(RunStatus.Action.POST)).isEqualTo(RunStatus.POSTED);
        assertThat(RunStatus.POSTED.apply(RunStatus.Action.PAY)).isEqualTo(RunStatus.PAID);
        assertThat(RunStatus.POSTED.allows(RunStatus.Action.CANCEL)).isFalse();
        assertThat(RunStatus.APPROVED.allows(RunStatus.Action.CANCEL)).isFalse();
        assertThat(RunStatus.POSTED.released()).isTrue();
        assertThat(RunStatus.APPROVED.released()).isFalse();
        assertThatThrownBy(() -> RunStatus.PAID.apply(RunStatus.Action.POST)).isInstanceOf(IllegalStateException.class);
        assertThat(Calculation.PERCENT_OF_GROSS.allowedFor(ComponentKind.EARNING))
                .isFalse();
        assertThat(Calculation.STATUTORY.allowedFor(ComponentKind.DEDUCTION)).isTrue();
    }

    @Test
    void theBuiltInStatutoryRules() {
        StatutoryRule.Context context = new StatutoryRule.Context(
                UUID.randomUUID(),
                "TAX",
                LocalDate.of(2027, 1, 1),
                LocalDate.of(2027, 1, 31),
                "USD",
                "US",
                new BigDecimal("3500"),
                new BigDecimal("3000"),
                new BigDecimal("3000"),
                new BigDecimal("12.5"),
                null,
                BigDecimal.ONE);
        assertThat(new FlatPercentageRule().calculate(context)).isEqualByComparingTo("375");
        assertThat(new NoStatutoryRule().calculate(context)).isEqualByComparingTo("0");
        StatutoryRule.Context noRate = new StatutoryRule.Context(
                UUID.randomUUID(),
                "TAX",
                LocalDate.of(2027, 1, 1),
                LocalDate.of(2027, 1, 31),
                "USD",
                "US",
                new BigDecimal("3500"),
                new BigDecimal("3000"),
                new BigDecimal("3000"),
                null,
                null,
                BigDecimal.ONE);
        assertThat(new FlatPercentageRule().calculate(noRate)).isEqualByComparingTo("0");
    }
}
