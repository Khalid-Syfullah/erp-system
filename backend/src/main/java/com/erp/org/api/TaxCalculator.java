package com.erp.org.api;

import com.erp.platform.money.RoundingPolicy;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Document line and tax arithmetic (PRODUCT_SPEC.md G-14, ADR-020): one rate per tax code, tax-exclusive
 * or tax-inclusive prices, tax rounded per line or per document. Amounts are in the document currency
 * and rounded to its minor units with the company rounding mode. Isolated here so that jurisdictions
 * with other rules can replace it.
 */
public interface TaxCalculator {

    /** Company setting {@code tax_rounding}. */
    enum TaxRounding {
        PER_LINE,
        PER_DOCUMENT
    }

    /** A tax code's rate on the document (exempt codes have rate 0). */
    record Rate(UUID taxCodeId, BigDecimal ratePercent) {}

    /** {@code net = round(quantity × unitPrice × (1 − discountPercent / 100))} before tax. */
    record Line(
            BigDecimal quantity,
            BigDecimal unitPrice,
            BigDecimal discountPercent,
            @Nullable Rate tax) {}

    record Request(List<Line> lines, boolean pricesIncludeTax, RoundingPolicy rounding, TaxRounding taxRounding) {}

    record LineResult(BigDecimal net, BigDecimal tax, BigDecimal total) {}

    /** Per tax code: the taxable (net) amount and the tax. */
    record TaxTotal(UUID taxCodeId, BigDecimal ratePercent, BigDecimal taxable, BigDecimal tax) {}

    /** {@code total = subtotal + taxTotal}; lines are in request order. */
    record Result(
            List<LineResult> lines, List<TaxTotal> taxes, BigDecimal subtotal, BigDecimal taxTotal, BigDecimal total) {}

    Result calculate(Request request);
}
