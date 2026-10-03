package com.erp.platform.numbering;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.platform.money.RoundingPolicy;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Number formats, fiscal-year labels and rounding policies. */
class NumberFormatTest {

    @Test
    void rendersPrefixFiscalYearAndPaddedCounter() {
        NumberFormat format = new NumberFormat("SM-{FY}-", 6);
        assertThat(format.render("2026", 42)).isEqualTo("SM-2026-000042");
        assertThat(format.render("2026", 1_234_567)).isEqualTo("SM-2026-1234567");
        assertThat(new NumberFormat("CNT/", 1).render("2026", 7)).isEqualTo("CNT/7");
        assertThat(format.prefixFor("2027")).isEqualTo("SM-2027-");
    }

    @ParameterizedTest
    @ValueSource(strings = {"sm-", "SM {FY}", "{FY}{FY}", "TOO-LONG-PREFIX-{FY}-X", "SM-{YEAR}"})
    void rejectsInvalidPrefixes(String prefix) {
        assertThatThrownBy(() -> new NumberFormat(prefix, 6)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsInvalidPadding() {
        assertThatThrownBy(() -> new NumberFormat("SM-", 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NumberFormat("SM-", 13)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @CsvSource({"2026-01-01,1,2026", "2026-12-31,1,2026", "2026-03-31,4,2025", "2026-04-01,4,2026", "2027-03-31,4,2026"
    })
    void fiscalYearsAreNamedAfterTheirStartYear(LocalDate date, int startMonth, String label) {
        assertThat(FiscalYears.label(date, startMonth)).isEqualTo(label);
    }

    @Test
    void fiscalYearStartMonthIsValidated() {
        assertThatThrownBy(() -> FiscalYears.label(LocalDate.now(), 13)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FiscalYears.label(LocalDate.now(), 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void roundingPolicies() {
        assertThat(RoundingPolicy.of(2, "HALF_EVEN").round(new BigDecimal("2.345")))
                .isEqualByComparingTo("2.34");
        assertThat(RoundingPolicy.of(2, "HALF_UP").round(new BigDecimal("2.345")))
                .isEqualByComparingTo("2.35");
        assertThat(RoundingPolicy.of(0, "HALF_UP").round(new BigDecimal("2.5"))).isEqualByComparingTo("3");
        assertThatThrownBy(() -> new RoundingPolicy(2, RoundingMode.CEILING))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RoundingPolicy(5, RoundingMode.HALF_UP))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
