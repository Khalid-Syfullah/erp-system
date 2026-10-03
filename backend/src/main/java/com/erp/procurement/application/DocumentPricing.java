package com.erp.procurement.application;

import com.erp.inventory.api.InventoryFacade;
import com.erp.inventory.api.InventoryFacade.VariantInfo;
import com.erp.org.api.CompanyProfile;
import com.erp.org.api.OrgFacade;
import com.erp.org.api.TaxCalculator;
import com.erp.org.api.TaxCodeSummary;
import com.erp.platform.context.CurrentContext;
import com.erp.platform.money.RoundingPolicy;
import com.erp.platform.web.ApiException;
import com.erp.platform.web.FieldViolation;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Validates and prices document lines (orders, bills): the product must be active and purchasable,
 * the quantity converts to the product's base unit (G-15), the tax code must be an active purchase
 * code valid on the document date, and amounts follow G-14 through Org's {@link TaxCalculator}.
 */
@Component
class DocumentPricing {

    static final int MAX_LINES = 500;
    static final int UNIT_PRICE_SCALE = 6;

    /** A line to price; {@code index} is its position for error pointers. */
    record Input(
            int index,
            UUID variantId,
            @Nullable String description,
            BigDecimal quantity,
            UUID uomId,
            BigDecimal unitPrice,
            BigDecimal discountPercent,
            @Nullable UUID taxCodeId) {}

    record Line(
            Input input,
            VariantInfo variant,
            String description,
            BigDecimal quantityBase,
            @Nullable BigDecimal ratePercent,
            BigDecimal net,
            BigDecimal tax,
            BigDecimal total) {}

    record Priced(List<Line> lines, TaxCalculator.Result result) {}

    private final InventoryFacade inventory;
    private final OrgFacade org;
    private final TaxCalculator taxes;

    DocumentPricing(InventoryFacade inventory, OrgFacade org, TaxCalculator taxes) {
        this.inventory = inventory;
        this.org = org;
        this.taxes = taxes;
    }

    /** Variant checks and base quantities; problems are added to {@code violations}. */
    @Nullable VariantInfo variant(int index, UUID variantId, List<FieldViolation> violations) {
        VariantInfo variant = inventory.variantInfo(variantId).orElse(null);
        if (variant == null) {
            violations.add(FieldViolation.atPointer(
                    "/lines/" + index + "/variantId", "UNKNOWN_VARIANT", "is not a product variant of the company"));
            return null;
        }
        if (!"ACTIVE".equals(variant.status()) || !variant.purchasable()) {
            violations.add(FieldViolation.atPointer(
                    "/lines/" + index + "/variantId", "NOT_PURCHASABLE", "must be an active, purchasable product"));
            return null;
        }
        return variant;
    }

    @Nullable BigDecimal quantityBase(
            int index, UUID variantId, BigDecimal quantity, UUID uomId, List<FieldViolation> violations) {
        if (quantity.signum() <= 0) {
            violations.add(
                    FieldViolation.atPointer("/lines/" + index + "/quantity", "POSITIVE", "must be greater than 0"));
            return null;
        }
        try {
            return inventory.convertQuantity(variantId, quantity, uomId);
        } catch (ApiException e) {
            boolean unit = "UOM_NOT_CONVERTIBLE".equals(e.errorCode().code())
                    || !e.violations().isEmpty();
            violations.add(FieldViolation.atPointer(
                    "/lines/" + index + (unit ? "/uomId" : "/quantity"),
                    unit ? "UOM_NOT_CONVERTIBLE" : "INVALID_QUANTITY",
                    e.getMessage()));
            return null;
        }
    }

    /** The tax code's rate if it is usable on purchases on the date (exempt codes rate 0). */
    @Nullable BigDecimal taxRate(int index, @Nullable UUID taxCodeId, LocalDate date, List<FieldViolation> violations) {
        if (taxCodeId == null) {
            return null;
        }
        TaxCodeSummary code =
                org.taxCode(CurrentContext.requireCompany(), taxCodeId).orElse(null);
        if (code == null || !code.appliesToPurchases() || !code.usableOn(date)) {
            violations.add(FieldViolation.atPointer(
                    "/lines/" + index + "/taxCodeId",
                    "INVALID_TAX_CODE",
                    "must be a purchase tax code of the company that is active and valid on " + date));
            return null;
        }
        return code.exempt() ? BigDecimal.ZERO : code.ratePercent();
    }

    /**
     * Prices the lines in the document currency. Throws {@code 422} with every problem at once;
     * {@code extra} are problems the caller found before.
     */
    Priced price(
            List<Input> inputs,
            boolean pricesIncludeTax,
            String currencyCode,
            LocalDate date,
            CompanyProfile profile,
            RoundingPolicy rounding,
            List<FieldViolation> extra) {
        List<FieldViolation> violations = new ArrayList<>(extra);
        if (inputs.isEmpty() || inputs.size() > MAX_LINES) {
            violations.add(
                    FieldViolation.atPointer("/lines", "SIZE", "must contain between 1 and " + MAX_LINES + " lines"));
        }
        Map<Integer, VariantInfo> variants = new HashMap<>();
        Map<Integer, BigDecimal> bases = new HashMap<>();
        Map<Integer, BigDecimal> rates = new HashMap<>();
        for (Input in : inputs) {
            String at = "/lines/" + in.index();
            if (in.unitPrice().signum() < 0
                    || in.unitPrice().stripTrailingZeros().scale() > UNIT_PRICE_SCALE) {
                violations.add(FieldViolation.atPointer(
                        at + "/unitPrice", "INVALID_VALUE", "must be ≥ 0 with at most 6 decimal places"));
            }
            if (in.discountPercent().signum() < 0
                    || in.discountPercent().compareTo(BigDecimal.valueOf(100)) > 0
                    || in.discountPercent().stripTrailingZeros().scale() > 4) {
                violations.add(FieldViolation.atPointer(
                        at + "/discountPercent", "INVALID_VALUE", "must be between 0 and 100 with at most 4 decimals"));
            }
            VariantInfo variant = variant(in.index(), in.variantId(), violations);
            if (variant != null) {
                variants.put(in.index(), variant);
                BigDecimal base = quantityBase(in.index(), in.variantId(), in.quantity(), in.uomId(), violations);
                if (base != null) {
                    bases.put(in.index(), base);
                }
            }
            BigDecimal rate = taxRate(in.index(), in.taxCodeId(), date, violations);
            if (rate != null) {
                rates.put(in.index(), rate);
            }
        }
        if (!violations.isEmpty()) {
            throw ApiException.validationFailed("The document is invalid.", violations);
        }
        List<TaxCalculator.Line> taxLines = inputs.stream()
                .map(in -> new TaxCalculator.Line(
                        in.quantity(),
                        in.unitPrice(),
                        in.discountPercent(),
                        in.taxCodeId() == null ? null : new TaxCalculator.Rate(in.taxCodeId(), rates.get(in.index()))))
                .toList();
        TaxCalculator.Result result = taxes.calculate(new TaxCalculator.Request(
                taxLines, pricesIncludeTax, rounding, TaxCalculator.TaxRounding.valueOf(profile.taxRounding())));
        List<Line> lines = new ArrayList<>();
        for (int i = 0; i < inputs.size(); i++) {
            Input in = inputs.get(i);
            VariantInfo variant = variants.get(in.index());
            TaxCalculator.LineResult r = result.lines().get(i);
            lines.add(new Line(
                    in,
                    variant,
                    in.description() != null ? in.description() : variant.name(),
                    bases.get(in.index()),
                    rates.get(in.index()),
                    r.net(),
                    r.tax(),
                    r.total()));
        }
        return new Priced(List.copyOf(lines), result);
    }

    /** Net unit price per base unit (G-14 net ÷ base quantity), 6 decimals. */
    static BigDecimal netUnitPrice(BigDecimal net, BigDecimal quantityBase) {
        return net.divide(quantityBase, UNIT_PRICE_SCALE, RoundingMode.HALF_UP);
    }
}
