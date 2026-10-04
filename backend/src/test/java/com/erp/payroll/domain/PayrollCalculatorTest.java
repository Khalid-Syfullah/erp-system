package com.erp.payroll.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.payroll.domain.PayrollCalculator.Component;
import com.erp.payroll.domain.PayrollCalculator.Input;
import com.erp.payroll.domain.PayrollCalculator.Line;
import com.erp.payroll.domain.PayrollCalculator.Request;
import com.erp.payroll.domain.PayrollCalculator.Result;
import com.erp.payroll.domain.PayrollCalculator.Segment;
import com.erp.payroll.domain.PayrollCalculator.Value;
import com.erp.payroll.domain.statutory.FlatPercentageRule;
import com.erp.payroll.domain.statutory.NoStatutoryRule;
import com.erp.payroll.domain.statutory.StatutoryRule;
import com.erp.payroll.domain.statutory.StatutoryRules;
import com.erp.platform.money.RoundingPolicy;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.RepetitionInfo;
import org.junit.jupiter.api.Test;

/** The payroll engine (PAY-1 to PAY-3) on fixed and on seeded random inputs. */
class PayrollCalculatorTest {

    private static final RoundingPolicy CENTS = new RoundingPolicy(2, RoundingMode.HALF_UP);
    private static final LocalDate START = LocalDate.of(2027, 1, 1);
    private static final LocalDate END = LocalDate.of(2027, 1, 31);

    private static final Component BASIC =
            component("BASIC", ComponentKind.EARNING, Calculation.PERCENT_OF_BASE, true, null, 10);
    private static final Component HOUSING =
            component("HOUSING", ComponentKind.EARNING, Calculation.FIXED, false, null, 20);
    private static final Component OVERTIME =
            component("OVERTIME", ComponentKind.EARNING, Calculation.INPUT, true, null, 30);
    private static final Component TAX =
            component("TAX", ComponentKind.DEDUCTION, Calculation.STATUTORY, true, "FLAT_PERCENT", 10);
    private static final Component PENSION =
            component("PENSION", ComponentKind.DEDUCTION, Calculation.PERCENT_OF_GROSS, true, null, 20);
    private static final Component UNION =
            component("UNION", ComponentKind.DEDUCTION, Calculation.FIXED, true, null, 30);
    private static final Component LEVY =
            component("LEVY", ComponentKind.EMPLOYER_CONTRIBUTION, Calculation.STATUTORY, true, "NONE", 10);
    private static final Component PENSION_ER =
            component("PENSION_ER", ComponentKind.EMPLOYER_CONTRIBUTION, Calculation.PERCENT_OF_GROSS, true, null, 20);

    private final PayrollCalculator calculator =
            new PayrollCalculator(new StatutoryRules(List.of(new NoStatutoryRule(), new FlatPercentageRule())));

    @Test
    void aFullMonthFollowsTheEvaluationOrder() {
        Result r = calculator.calculate(request(List.of(segment(31, "3000")), List.of(), false));
        assertThat(amount(r, "BASIC")).isEqualByComparingTo("3000");
        assertThat(amount(r, "HOUSING")).isEqualByComparingTo("500");
        assertThat(r.gross()).isEqualByComparingTo("3500");
        assertThat(r.taxableGross()).isEqualByComparingTo("3000");
        assertThat(amount(r, "TAX")).isEqualByComparingTo("300"); // 10 % of the taxable gross
        assertThat(amount(r, "PENSION")).isEqualByComparingTo("175"); // 5 % of the gross
        assertThat(amount(r, "UNION")).isEqualByComparingTo("15"); // fixed, not prorated
        assertThat(r.deductions()).isEqualByComparingTo("490");
        assertThat(r.net()).isEqualByComparingTo("3010");
        assertThat(amount(r, "PENSION_ER")).isEqualByComparingTo("280");
        assertThat(r.contributions()).isEqualByComparingTo("280");
        assertThat(lines(r).stream().map(l -> l.component().code()))
                .doesNotContain("LEVY", "OVERTIME"); // zero lines and unused inputs are left out
        assertThat(lines(r).stream().map(l -> l.component().code()).toList())
                .containsExactly("BASIC", "HOUSING", "TAX", "PENSION", "UNION", "PENSION_ER");
    }

    @Test
    void partialPeriodsAndRaisesAreProratedPerSegment() {
        // Hired on the 16th: 16 of 31 days.
        Result hired = calculator.calculate(request(List.of(segment(16, "3100")), List.of(), false));
        assertThat(hired.daysPaid()).isEqualTo(16);
        assertThat(amount(hired, "BASIC")).isEqualByComparingTo("1600");
        assertThat(amount(hired, "HOUSING")).isEqualByComparingTo("258.06");
        assertThat(amount(hired, "UNION")).isEqualByComparingTo("15");
        assertThat(hired.base()).isEqualByComparingTo("1600");
        // A raise after 10 days: 2000 × 10/31 + 3100 × 21/31.
        Result raised =
                calculator.calculate(request(List.of(segment(10, "2000"), segment(21, "3100")), List.of(), false));
        assertThat(amount(raised, "BASIC")).isEqualByComparingTo("2745.16");
        assertThat(amount(raised, "HOUSING")).isEqualByComparingTo("500");
    }

    @Test
    void inputsAndTheStatutoryCapAreApplied() {
        Request base = request(
                List.of(segment(31, "3000")), List.of(new Input(OVERTIME.id(), new BigDecimal("12.5"), null)), false);
        Result r = calculator.calculate(base);
        Line overtime = line(r, "OVERTIME");
        assertThat(overtime.quantity()).isEqualByComparingTo("12.5");
        assertThat(overtime.rate()).isEqualByComparingTo("20");
        assertThat(overtime.amount()).isEqualByComparingTo("250");
        assertThat(r.taxableGross()).isEqualByComparingTo("3250");

        // A tax ceiling of 200 per month (prorated) caps the flat 10 %.
        Map<UUID, Value> capped = new HashMap<>(segment(31, "3000").values());
        capped.put(TAX.id(), new Value(new BigDecimal("10"), new BigDecimal("200")));
        Result withCap = calculator.calculate(
                request(List.of(new Segment(31, new BigDecimal("3000"), capped)), List.of(), false));
        assertThat(amount(withCap, "TAX")).isEqualByComparingTo("200");
        Result halfCap = calculator.calculate(
                request(List.of(new Segment(15, new BigDecimal("3000"), capped)), List.of(), false));
        assertThat(amount(halfCap, "TAX")).isEqualByComparingTo("96.77"); // 200 × 15/31 caps 145.16
    }

    @Test
    void offCycleRunsPayInputsOnly() {
        Component bonus = component("BONUS", ComponentKind.EARNING, Calculation.INPUT, true, null, 40);
        Request r = new Request(
                UUID.randomUUID(),
                START,
                END,
                31,
                List.of(segment(31, "3000")),
                List.of(BASIC, HOUSING, bonus, TAX, PENSION, UNION, PENSION_ER),
                List.of(new Input(bonus.id(), null, new BigDecimal("1000"))),
                Map.of(),
                true,
                "USD",
                "US",
                CENTS);
        Result result = calculator.calculate(r);
        assertThat(lines(result).stream().map(l -> l.component().code()).toList())
                .containsExactly("BONUS", "TAX", "PENSION", "PENSION_ER");
        assertThat(result.net()).isEqualByComparingTo("850");
    }

    @Test
    void aNegativeNetIsReportedAndRulesMustBeKnown() {
        Component advance = component("ADVANCE", ComponentKind.DEDUCTION, Calculation.INPUT, true, null, 90);
        Request r = new Request(
                UUID.randomUUID(),
                START,
                END,
                31,
                List.of(segment(31, "100")),
                List.of(BASIC, advance),
                List.of(new Input(advance.id(), null, new BigDecimal("500"))),
                Map.of(),
                false,
                "USD",
                "US",
                CENTS);
        assertThat(calculator.calculate(r).negativeNet()).isTrue();
        Component unknown = component("X", ComponentKind.DEDUCTION, Calculation.STATUTORY, true, "MISSING", 1);
        Request withUnknown = new Request(
                UUID.randomUUID(),
                START,
                END,
                31,
                List.of(segment(31, "100")),
                List.of(BASIC, unknown),
                List.of(),
                Map.of(),
                false,
                "USD",
                "US",
                CENTS);
        assertThatThrownBy(() -> calculator.calculate(withUnknown)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> calculator.calculate(request(List.of(segment(32, "1")), List.of(), false)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aCountryRuleIsJustAnotherStatutoryRule() {
        // A progressive example: 0 % up to 1000, 20 % above.
        StatutoryRule progressive = new StatutoryRule() {
            @Override
            public String code() {
                return "EXAMPLE_PROGRESSIVE";
            }

            @Override
            public String description() {
                return "Example";
            }

            @Override
            public BigDecimal calculate(Context c) {
                return c.taxableGross()
                        .subtract(new BigDecimal("1000"))
                        .max(BigDecimal.ZERO)
                        .multiply(new BigDecimal("0.2"));
            }
        };
        PayrollCalculator withCountryRule =
                new PayrollCalculator(new StatutoryRules(List.of(new NoStatutoryRule(), progressive)));
        Component tax =
                component("ITAX", ComponentKind.DEDUCTION, Calculation.STATUTORY, true, "EXAMPLE_PROGRESSIVE", 1);
        Request r = new Request(
                UUID.randomUUID(),
                START,
                END,
                31,
                List.of(segment(31, "3000")),
                List.of(BASIC, tax),
                List.of(),
                Map.of(),
                false,
                "USD",
                "XX",
                CENTS);
        assertThat(amount(withCountryRule.calculate(r), "ITAX")).isEqualByComparingTo("400");
        assertThatThrownBy(() -> new StatutoryRules(List.of(new NoStatutoryRule(), new NoStatutoryRule())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** Seeded random payslips: deterministic, rounded per line, totals are sums, net = gross − deductions. */
    @RepeatedTest(300)
    void payslipsAreDeterministicAndConsistent(RepetitionInfo repetition) {
        Random random = new Random(repetition.getCurrentRepetition());
        int periodDays = 28 + random.nextInt(4);
        List<Segment> segments = new ArrayList<>();
        int left = periodDays;
        int parts = 1 + random.nextInt(3);
        for (int i = 0; i < parts && left > 0; i++) {
            int days = i == parts - 1 ? left - random.nextInt(Math.max(1, left / 3)) : 1 + random.nextInt(left);
            days = Math.max(1, Math.min(days, left));
            left -= days;
            segments.add(segment(
                    days,
                    BigDecimal.valueOf(100_000 + random.nextInt(900_000_000), 2).toPlainString()));
        }
        List<Input> inputs = random.nextBoolean()
                ? List.of(new Input(OVERTIME.id(), BigDecimal.valueOf(random.nextInt(4000), 2), null))
                : List.of();
        Request request = new Request(
                UUID.randomUUID(),
                START,
                START.plusDays(periodDays - 1L),
                periodDays,
                segments,
                List.of(BASIC, HOUSING, OVERTIME, TAX, PENSION, UNION, LEVY, PENSION_ER),
                inputs,
                Map.of(OVERTIME.id(), new BigDecimal("20")),
                false,
                "USD",
                "US",
                CENTS);

        Result first = calculator.calculate(request);
        Result second = calculator.calculate(request);
        assertThat(second).isEqualTo(first);

        BigDecimal gross = BigDecimal.ZERO;
        BigDecimal deductions = BigDecimal.ZERO;
        BigDecimal contributions = BigDecimal.ZERO;
        for (Line l : first.lines()) {
            assertThat(l.amount().scale()).isEqualTo(2);
            assertThat(l.amount().signum()).isPositive();
            switch (l.component().kind()) {
                case EARNING -> gross = gross.add(l.amount());
                case DEDUCTION -> deductions = deductions.add(l.amount());
                case EMPLOYER_CONTRIBUTION -> contributions = contributions.add(l.amount());
            }
        }
        assertThat(first.gross()).isEqualByComparingTo(gross);
        assertThat(first.deductions()).isEqualByComparingTo(deductions);
        assertThat(first.contributions()).isEqualByComparingTo(contributions);
        assertThat(first.net()).isEqualByComparingTo(gross.subtract(deductions));
        assertThat(first.taxableGross()).isLessThanOrEqualTo(first.gross());
        assertThat(first.daysPaid()).isLessThanOrEqualTo(periodDays);
        // Proration never pays more than a full period would.
        BigDecimal fullBasic = segments.stream()
                .map(Segment::baseAmount)
                .max(BigDecimal::compareTo)
                .orElseThrow();
        assertThat(amount(first, "BASIC")).isLessThanOrEqualTo(fullBasic.setScale(2, RoundingMode.HALF_UP));
    }

    // ------------------------------------------------------------------------------ helpers

    private static Component component(
            String code, ComponentKind kind, Calculation calculation, boolean taxable, String rule, int sequence) {
        return new Component(
                UUID.nameUUIDFromBytes(code.getBytes()), code, code, kind, calculation, taxable, rule, sequence);
    }

    private static Segment segment(int days, String base) {
        Map<UUID, Value> values = new HashMap<>();
        values.put(BASIC.id(), new Value(new BigDecimal("100"), null));
        values.put(HOUSING.id(), new Value(null, new BigDecimal("500")));
        values.put(OVERTIME.id(), new Value(new BigDecimal("20"), null));
        values.put(TAX.id(), new Value(new BigDecimal("10"), null));
        values.put(PENSION.id(), new Value(new BigDecimal("5"), null));
        values.put(UNION.id(), new Value(null, new BigDecimal("15")));
        values.put(LEVY.id(), new Value(new BigDecimal("1"), null));
        values.put(PENSION_ER.id(), new Value(new BigDecimal("8"), null));
        return new Segment(days, new BigDecimal(base), values);
    }

    private static Request request(List<Segment> segments, List<Input> inputs, boolean offCycle) {
        return new Request(
                UUID.randomUUID(),
                START,
                END,
                31,
                segments,
                List.of(BASIC, HOUSING, OVERTIME, TAX, PENSION, UNION, LEVY, PENSION_ER),
                inputs,
                Map.of(OVERTIME.id(), new BigDecimal("20")),
                offCycle,
                "USD",
                "US",
                CENTS);
    }

    private static List<Line> lines(Result r) {
        return r.lines();
    }

    private static Line line(Result r, String code) {
        return r.lines().stream()
                .filter(l -> l.component().code().equals(code))
                .findFirst()
                .orElseThrow();
    }

    private static BigDecimal amount(Result r, String code) {
        return line(r, code).amount();
    }
}
