package com.erp.org.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.erp.org.api.TaxCalculator;
import com.erp.org.api.TaxCalculator.Line;
import com.erp.org.api.TaxCalculator.Rate;
import com.erp.org.api.TaxCalculator.Request;
import com.erp.org.api.TaxCalculator.TaxRounding;
import com.erp.platform.money.RoundingPolicy;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** G-14 line and tax arithmetic. */
class StandardTaxCalculatorTest {

    private static final RoundingPolicy CENTS = new RoundingPolicy(2, RoundingMode.HALF_UP);
    private static final UUID VAT = UUID.randomUUID();
    private final TaxCalculator calculator = new StandardTaxCalculator();

    private static Line line(String qty, String price, String discount, String rate) {
        return new Line(
                new BigDecimal(qty),
                new BigDecimal(price),
                new BigDecimal(discount),
                rate == null ? null : new Rate(VAT, new BigDecimal(rate)));
    }

    @Test
    void exclusivePerLine() {
        var result = calculator.calculate(new Request(
                List.of(line("3", "10", "10", "10"), line("1", "0.333", "0", null)),
                false,
                CENTS,
                TaxRounding.PER_LINE));
        assertThat(result.lines().get(0).net()).isEqualByComparingTo("27.00");
        assertThat(result.lines().get(0).tax()).isEqualByComparingTo("2.70");
        assertThat(result.lines().get(1).net()).isEqualByComparingTo("0.33");
        assertThat(result.lines().get(1).tax()).isEqualByComparingTo("0");
        assertThat(result.total()).isEqualByComparingTo("30.03");
        assertThat(result.taxes()).singleElement().satisfies(t -> {
            assertThat(t.taxable()).isEqualByComparingTo("27.00");
            assertThat(t.tax()).isEqualByComparingTo("2.70");
        });
    }

    @Test
    void perDocumentRoundingTaxesTheSumAndSpreadsIt() {
        // Three lines of 0.05 at 10 %: per line 3 × round(0.005) = 0.03; per document round(0.015) = 0.02.
        List<Line> lines =
                List.of(line("1", "0.05", "0", "10"), line("1", "0.05", "0", "10"), line("1", "0.05", "0", "10"));
        assertThat(calculator
                        .calculate(new Request(lines, false, CENTS, TaxRounding.PER_LINE))
                        .taxTotal())
                .isEqualByComparingTo("0.03");
        var perDocument = calculator.calculate(new Request(lines, false, CENTS, TaxRounding.PER_DOCUMENT));
        assertThat(perDocument.taxTotal()).isEqualByComparingTo("0.02");
        assertThat(perDocument.lines().stream()
                        .map(TaxCalculator.LineResult::tax)
                        .reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("0.02");
    }

    @Test
    void inclusivePricesContainTheirTax() {
        var perLine = calculator.calculate(
                new Request(List.of(line("1", "110", "0", "10")), true, CENTS, TaxRounding.PER_LINE));
        assertThat(perLine.lines().getFirst().net()).isEqualByComparingTo("100.00");
        assertThat(perLine.lines().getFirst().tax()).isEqualByComparingTo("10.00");
        assertThat(perLine.total()).isEqualByComparingTo("110.00");
        var perDocument = calculator.calculate(new Request(
                List.of(line("1", "10", "0", "19"), line("1", "10", "0", "19")),
                true,
                CENTS,
                TaxRounding.PER_DOCUMENT));
        // 20.00 gross: net round(20 / 1.19) = 16.81, tax 3.19 spread over both lines.
        assertThat(perDocument.subtotal()).isEqualByComparingTo("16.81");
        assertThat(perDocument.taxTotal()).isEqualByComparingTo("3.19");
        assertThat(perDocument.total()).isEqualByComparingTo("20.00");
    }

    @Test
    void zeroDecimalCurrencies() {
        var result = calculator.calculate(new Request(
                List.of(line("3", "333.5", "0", "8")),
                false,
                new RoundingPolicy(0, RoundingMode.HALF_UP),
                TaxRounding.PER_LINE));
        assertThat(result.lines().getFirst().net()).isEqualByComparingTo("1001");
        assertThat(result.lines().getFirst().tax()).isEqualByComparingTo("80");
    }
}
