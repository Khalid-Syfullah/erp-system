package com.erp.hr.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class EffectivePeriodTest {

    private static LocalDate d(int month, int day) {
        return LocalDate.of(2026, month, day);
    }

    @Test
    void boundsAreInclusiveAndOpenEndedPeriodsNeverEnd() {
        EffectivePeriod q1 = new EffectivePeriod(d(1, 1), d(3, 31));
        EffectivePeriod fromApril = new EffectivePeriod(d(4, 1), null);

        assertThat(q1.contains(d(1, 1))).isTrue();
        assertThat(q1.contains(d(3, 31))).isTrue();
        assertThat(q1.contains(d(4, 1))).isFalse();
        assertThat(fromApril.contains(LocalDate.of(2099, 1, 1))).isTrue();
        assertThat(q1.overlaps(fromApril)).isFalse();
        assertThat(q1.overlaps(new EffectivePeriod(d(3, 31), null))).isTrue();
        assertThat(q1.reachesOrPasses(d(3, 31))).isTrue();
        assertThat(q1.reachesOrPasses(d(4, 1))).isFalse();
        assertThat(fromApril.reachesOrPasses(d(1, 1))).isTrue();
    }

    @Test
    void intersectionsKeepTheCommonPart() {
        EffectivePeriod q1 = new EffectivePeriod(d(1, 1), d(3, 31));

        assertThat(q1.intersect(new EffectivePeriod(d(2, 1), null))).contains(new EffectivePeriod(d(2, 1), d(3, 31)));
        assertThat(new EffectivePeriod(d(2, 1), null).intersect(new EffectivePeriod(d(1, 1), null)))
                .contains(new EffectivePeriod(d(2, 1), null));
        assertThat(q1.intersect(new EffectivePeriod(d(5, 1), d(6, 1)))).isEmpty();
    }

    @Test
    void invalidPeriodsAreRejected() {
        assertThatThrownBy(() -> new EffectivePeriod(d(2, 1), d(1, 1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EffectivePeriod(null, null)).isInstanceOf(IllegalArgumentException.class);
    }
}
