package com.erp.payroll.domain;

import com.erp.payroll.domain.statutory.StatutoryRule;
import com.erp.payroll.domain.statutory.StatutoryRules;
import com.erp.platform.money.RoundingPolicy;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The payroll calculation of one employee for one period (PRODUCT_SPEC.md §11.2). It is a pure
 * function of its {@link Request}: no clock, no database, no ordering left to chance — the same
 * request always gives the same payslip.
 *
 * <ul>
 *   <li><b>PAY-1 proration:</b> the period is covered by segments, one per compensation in effect
 *       (clipped to employment and assignments); each contributes {@code days ÷ periodDays} of its
 *       base and of its FIXED and PERCENT_OF_BASE earnings. Days are calendar or working days, as the
 *       caller counted them.
 *   <li><b>PAY-2 order:</b> earnings by sequence, then gross and taxable gross, then deductions by
 *       sequence (PERCENT_OF_GROSS and STATUTORY see the gross), then employer contributions; net =
 *       gross − deductions. A negative net is reported, not paid.
 *   <li><b>PAY-3 rounding:</b> each line is rounded to the currency once; totals are sums of lines.
 *   <li>FIXED deductions and contributions are not prorated and come from the latest segment; INPUT
 *       components use the period's inputs (amount, or quantity × rate).
 *   <li>Off-cycle runs pay inputs only: FIXED and PERCENT_OF_BASE components are left out.
 * </ul>
 */
public final class PayrollCalculator {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final int SCALE = 10;

    /** A pay component as the engine needs it. */
    public record Component(
            UUID id,
            String code,
            String name,
            ComponentKind kind,
            Calculation calculation,
            boolean taxable,
            @Nullable String statutoryRuleCode,
            int sequence) {}

    /** A component's resolved rate (percentage, or unit rate for INPUT) and amount. */
    public record Value(@Nullable BigDecimal rate, @Nullable BigDecimal amount) {}

    /** One compensation's share of the period: its days and its base and component values. */
    public record Segment(int days, BigDecimal baseAmount, Map<UUID, Value> values) {}

    /** A period input of the employee. */
    public record Input(
            UUID componentId,
            @Nullable BigDecimal quantity,
            @Nullable BigDecimal amount) {}

    /**
     * @param components every component that may produce a line (structure components and INPUT
     *     components with inputs); {@code segments} hold their values
     * @param inputRates the resolved unit rates of INPUT components (for quantity inputs)
     */
    public record Request(
            UUID employeeId,
            LocalDate periodStart,
            LocalDate periodEnd,
            int periodDays,
            List<Segment> segments,
            List<Component> components,
            List<Input> inputs,
            Map<UUID, BigDecimal> inputRates,
            boolean offCycle,
            String currencyCode,
            String countryCode,
            RoundingPolicy rounding) {}

    /** A payslip line. */
    public record Line(
            Component component,
            @Nullable BigDecimal quantity,
            @Nullable BigDecimal rate,
            BigDecimal amount) {}

    /** The calculated payslip. */
    public record Result(
            List<Line> lines,
            int daysPaid,
            BigDecimal base,
            BigDecimal taxableGross,
            BigDecimal gross,
            BigDecimal deductions,
            BigDecimal contributions,
            BigDecimal net) {

        public boolean negativeNet() {
            return net.signum() < 0;
        }
    }

    private final StatutoryRules rules;

    public PayrollCalculator(StatutoryRules rules) {
        this.rules = rules;
    }

    public Result calculate(Request r) {
        if (r.periodDays() <= 0) {
            throw new IllegalArgumentException("The period has no days");
        }
        int daysPaid = r.segments().stream().mapToInt(Segment::days).sum();
        if (daysPaid > r.periodDays()) {
            throw new IllegalArgumentException("Segments cover more days than the period has");
        }
        BigDecimal factor = ratio(daysPaid, r.periodDays());
        BigDecimal proratedBase = BigDecimal.ZERO;
        for (Segment s : r.segments()) {
            proratedBase = proratedBase.add(s.baseAmount().multiply(ratio(s.days(), r.periodDays())));
        }
        Segment latest = r.segments().isEmpty() ? null : r.segments().getLast();

        List<Component> ordered = new ArrayList<>(r.components());
        ordered.sort(Comparator.comparing((Component c) -> c.kind().ordinal())
                .thenComparingInt(Component::sequence)
                .thenComparing(Component::code));

        List<Line> lines = new ArrayList<>();
        BigDecimal gross = BigDecimal.ZERO;
        BigDecimal taxableGross = BigDecimal.ZERO;
        for (Component c : ordered) {
            if (c.kind() != ComponentKind.EARNING || skipped(c, r.offCycle())) {
                continue;
            }
            Line line = earning(c, r);
            if (line != null) {
                lines.add(line);
                gross = gross.add(line.amount());
                if (c.taxable()) {
                    taxableGross = taxableGross.add(line.amount());
                }
            }
        }
        BigDecimal deductions = BigDecimal.ZERO;
        BigDecimal contributions = BigDecimal.ZERO;
        for (Component c : ordered) {
            if (c.kind() == ComponentKind.EARNING || skipped(c, r.offCycle())) {
                continue;
            }
            Line line = charge(c, r, latest, gross, taxableGross, proratedBase, factor);
            if (line != null) {
                lines.add(line);
                if (c.kind() == ComponentKind.DEDUCTION) {
                    deductions = deductions.add(line.amount());
                } else {
                    contributions = contributions.add(line.amount());
                }
            }
        }
        return new Result(
                List.copyOf(lines),
                daysPaid,
                r.rounding().round(proratedBase),
                taxableGross,
                gross,
                deductions,
                contributions,
                gross.subtract(deductions));
    }

    private static boolean skipped(Component c, boolean offCycle) {
        return offCycle && (c.calculation() == Calculation.FIXED || c.calculation() == Calculation.PERCENT_OF_BASE);
    }

    /** FIXED and PERCENT_OF_BASE earnings prorated per segment; INPUT earnings from the inputs. */
    private @Nullable Line earning(Component c, Request r) {
        return switch (c.calculation()) {
            case FIXED -> {
                BigDecimal sum = BigDecimal.ZERO;
                boolean any = false;
                for (Segment s : r.segments()) {
                    Value v = s.values().get(c.id());
                    if (v != null && v.amount() != null) {
                        sum = sum.add(v.amount().multiply(ratio(s.days(), r.periodDays())));
                        any = true;
                    }
                }
                yield any ? line(c, null, null, sum, r.rounding()) : null;
            }
            case PERCENT_OF_BASE -> {
                BigDecimal sum = BigDecimal.ZERO;
                BigDecimal rate = null;
                for (Segment s : r.segments()) {
                    Value v = s.values().get(c.id());
                    if (v != null && v.rate() != null) {
                        sum = sum.add(percent(s.baseAmount(), v.rate()).multiply(ratio(s.days(), r.periodDays())));
                        rate = v.rate();
                    }
                }
                yield rate == null ? null : line(c, null, rate, sum, r.rounding());
            }
            case INPUT -> input(c, r);
            case PERCENT_OF_GROSS, STATUTORY ->
                throw new IllegalStateException("An earning cannot be " + c.calculation());
        };
    }

    private @Nullable Line charge(
            Component c,
            Request r,
            @Nullable Segment latest,
            BigDecimal gross,
            BigDecimal taxableGross,
            BigDecimal proratedBase,
            BigDecimal factor) {
        Value v = latest == null ? null : latest.values().get(c.id());
        BigDecimal rate = v == null ? null : v.rate();
        BigDecimal amount = v == null ? null : v.amount();
        return switch (c.calculation()) {
            case FIXED -> amount == null ? null : line(c, null, null, amount, r.rounding());
            case PERCENT_OF_BASE ->
                rate == null ? null : line(c, null, rate, percent(proratedBase, rate), r.rounding());
            case PERCENT_OF_GROSS -> rate == null ? null : line(c, null, rate, percent(gross, rate), r.rounding());
            case INPUT -> input(c, r);
            case STATUTORY -> {
                StatutoryRule rule = rules.find(c.statutoryRuleCode())
                        .orElseThrow(
                                () -> new IllegalStateException("Unknown statutory rule " + c.statutoryRuleCode()));
                BigDecimal value = rule.calculate(new StatutoryRule.Context(
                        r.employeeId(),
                        c.code(),
                        r.periodStart(),
                        r.periodEnd(),
                        r.currencyCode(),
                        r.countryCode(),
                        gross,
                        taxableGross,
                        proratedBase,
                        rate,
                        amount,
                        factor));
                if (value.signum() < 0) {
                    throw new IllegalStateException("Statutory rule " + rule.code() + " returned a negative amount");
                }
                yield line(c, null, rate, value, r.rounding());
            }
        };
    }

    private @Nullable Line input(Component c, Request r) {
        BigDecimal quantity = null;
        BigDecimal total = BigDecimal.ZERO;
        boolean any = false;
        BigDecimal unitRate = r.inputRates().get(c.id());
        for (Input in : r.inputs()) {
            if (!in.componentId().equals(c.id())) {
                continue;
            }
            any = true;
            if (in.amount() != null) {
                total = total.add(in.amount());
            } else if (in.quantity() != null) {
                quantity = quantity == null ? in.quantity() : quantity.add(in.quantity());
                total = total.add(in.quantity().multiply(unitRate == null ? BigDecimal.ZERO : unitRate));
            }
        }
        return any ? line(c, quantity, quantity == null ? null : unitRate, total, r.rounding()) : null;
    }

    private static @Nullable Line line(
            Component c,
            @Nullable BigDecimal quantity,
            @Nullable BigDecimal rate,
            BigDecimal unrounded,
            RoundingPolicy rounding) {
        BigDecimal amount = rounding.round(unrounded);
        return amount.signum() == 0 ? null : new Line(c, quantity, rate, amount);
    }

    private static BigDecimal percent(BigDecimal value, BigDecimal rate) {
        return value.multiply(rate).divide(HUNDRED, SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal ratio(int days, int periodDays) {
        return BigDecimal.valueOf(days).divide(BigDecimal.valueOf(periodDays), SCALE, RoundingMode.HALF_UP);
    }
}
