package com.erp.org.application;

import com.erp.org.api.TaxCalculator;
import com.erp.platform.money.RoundingPolicy;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The v1 {@link TaxCalculator} (G-14):
 *
 * <ul>
 *   <li>Exclusive prices: {@code net = round(q × p × (1 − d))}; per line {@code tax = round(net × r)},
 *       per document {@code tax = round(Σ net × r)} per tax code, spread over its lines.
 *   <li>Inclusive prices: {@code gross = round(q × p × (1 − d))}; per line {@code net = round(gross / (1 + r))},
 *       per document {@code net = round(Σ gross / (1 + r))} per tax code; {@code tax = gross − net}.
 * </ul>
 *
 * Per-document tax is spread over the code's lines in proportion to their amounts, with the rounding
 * remainder on the last line, so the lines always add up to the document totals.
 */
@Component
class StandardTaxCalculator implements TaxCalculator {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final int WORK_SCALE = 20;

    @Override
    public Result calculate(Request request) {
        RoundingPolicy rounding = request.rounding();
        int count = request.lines().size();
        BigDecimal[] amount = new BigDecimal[count];
        BigDecimal[] tax = new BigDecimal[count];
        for (int i = 0; i < count; i++) {
            Line line = request.lines().get(i);
            BigDecimal factor = BigDecimal.ONE.subtract(line.discountPercent().divide(HUNDRED));
            amount[i] =
                    rounding.round(line.quantity().multiply(line.unitPrice()).multiply(factor));
            tax[i] = BigDecimal.ZERO;
        }
        Map<UUID, List<Integer>> byCode = new LinkedHashMap<>();
        Map<UUID, BigDecimal> rates = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            Rate rate = request.lines().get(i).tax();
            if (rate != null) {
                byCode.computeIfAbsent(rate.taxCodeId(), k -> new ArrayList<>()).add(i);
                rates.put(rate.taxCodeId(), rate.ratePercent());
            }
        }
        for (var entry : byCode.entrySet()) {
            BigDecimal rate = rates.get(entry.getKey());
            List<Integer> lines = entry.getValue();
            if (request.taxRounding() == TaxRounding.PER_LINE) {
                for (int i : lines) {
                    tax[i] = taxOf(amount[i], rate, request.pricesIncludeTax(), rounding);
                }
            } else {
                BigDecimal sum = lines.stream().map(i -> amount[i]).reduce(BigDecimal.ZERO, BigDecimal::add);
                spread(taxOf(sum, rate, request.pricesIncludeTax(), rounding), sum, lines, amount, tax, rounding);
            }
        }
        List<LineResult> results = new ArrayList<>(count);
        BigDecimal subtotal = BigDecimal.ZERO;
        BigDecimal taxTotal = BigDecimal.ZERO;
        for (int i = 0; i < count; i++) {
            BigDecimal net = request.pricesIncludeTax() ? amount[i].subtract(tax[i]) : amount[i];
            results.add(new LineResult(net, tax[i], net.add(tax[i])));
            subtotal = subtotal.add(net);
            taxTotal = taxTotal.add(tax[i]);
        }
        List<TaxTotal> taxes = new ArrayList<>();
        for (var entry : byCode.entrySet()) {
            BigDecimal taxable = BigDecimal.ZERO;
            BigDecimal codeTax = BigDecimal.ZERO;
            for (int i : entry.getValue()) {
                taxable = taxable.add(results.get(i).net());
                codeTax = codeTax.add(results.get(i).tax());
            }
            taxes.add(new TaxTotal(entry.getKey(), rates.get(entry.getKey()), taxable, codeTax));
        }
        return new Result(List.copyOf(results), List.copyOf(taxes), subtotal, taxTotal, subtotal.add(taxTotal));
    }

    /** Tax of an exclusive net amount, or the tax contained in an inclusive gross amount. */
    private static BigDecimal taxOf(
            BigDecimal amount, BigDecimal ratePercent, boolean inclusive, RoundingPolicy rounding) {
        BigDecimal rate = ratePercent.divide(HUNDRED);
        if (!inclusive) {
            return rounding.round(amount.multiply(rate));
        }
        BigDecimal net = rounding.round(amount.divide(BigDecimal.ONE.add(rate), WORK_SCALE, RoundingMode.HALF_EVEN));
        return amount.subtract(net);
    }

    private static void spread(
            BigDecimal total,
            BigDecimal base,
            List<Integer> lines,
            BigDecimal[] amount,
            BigDecimal[] tax,
            RoundingPolicy rounding) {
        BigDecimal remaining = total;
        for (int n = 0; n < lines.size(); n++) {
            int i = lines.get(n);
            BigDecimal share = n == lines.size() - 1 || base.signum() == 0
                    ? remaining
                    : rounding.round(total.multiply(amount[i]).divide(base, WORK_SCALE, RoundingMode.HALF_EVEN));
            tax[i] = share;
            remaining = remaining.subtract(share);
        }
    }
}
