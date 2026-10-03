package com.erp.sales.application;

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
import com.erp.sales.SalesPermissions;
import com.erp.sales.domain.PriceSelection;
import com.erp.sales.persistence.PriceListRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Validates and prices quotation, order and invoice lines (SAL-1, G-14, G-15): the product must be
 * active and sellable, the quantity converts to its base unit, the tax code must be an active sales
 * code valid on the document date. A line without a price takes the price list's (the tier with the
 * highest minimum quantity not above the line's); a manual price that differs from the list price
 * needs {@code sales.order.override_price}, a discount above the company threshold
 * {@code sales.order.discount_high}. Lines carried over unchanged from a stored document keep their
 * approval (a quotation accepted into an order, a draft edited elsewhere).
 */
@Component
class SalesPricing {

    static final int MAX_LINES = 500;
    static final int UNIT_PRICE_SCALE = 6;

    /** A priced line; {@code listPrice} is the price list's price for it (null: none). */
    record Line(
            int index,
            SalesCommands.PricedLine input,
            VariantInfo variant,
            String description,
            BigDecimal quantityBase,
            BigDecimal unitPrice,
            @Nullable BigDecimal listPrice,
            @Nullable UUID taxCodeId,
            @Nullable BigDecimal ratePercent,
            BigDecimal net,
            BigDecimal tax,
            BigDecimal total) {

        boolean stockable() {
            return "STOCKABLE".equals(variant.productType());
        }
    }

    record Priced(List<Line> lines, TaxCalculator.Result result) {}

    /** What a document's lines are priced against. */
    record Terms(
            String currencyCode,
            SalesViews.@Nullable PriceList priceList,
            boolean pricesIncludeTax,
            LocalDate date,
            @Nullable UUID customerTaxCodeId) {}

    private final InventoryFacade inventory;
    private final OrgFacade org;
    private final TaxCalculator taxes;
    private final PriceListRepository priceLists;
    private final SalesSettingsService settings;
    private final SalesContext context;

    SalesPricing(
            InventoryFacade inventory,
            OrgFacade org,
            TaxCalculator taxes,
            PriceListRepository priceLists,
            SalesSettingsService settings,
            SalesContext context) {
        this.inventory = inventory;
        this.org = org;
        this.taxes = taxes;
        this.priceLists = priceLists;
        this.settings = settings;
        this.context = context;
    }

    /**
     * SAL-1 list selection: the explicit list (which must be active, valid on the date and in the
     * currency), else the customer group's active list in the currency, else the currency's default.
     */
    SalesViews.@Nullable PriceList priceList(
            @Nullable UUID explicit,
            String currencyCode,
            @Nullable UUID customerGroupId,
            LocalDate date,
            List<FieldViolation> violations) {
        UUID companyId = CurrentContext.requireCompany();
        if (explicit != null) {
            SalesViews.PriceList list = priceLists.find(companyId, explicit).orElse(null);
            if (list == null || !list.validOn(date) || !list.currencyCode().equals(currencyCode)) {
                violations.add(FieldViolation.atPointer(
                        "/priceListId",
                        "INVALID_VALUE",
                        "must be an active price list in " + currencyCode + " valid on " + date));
                return null;
            }
            return list;
        }
        List<SalesViews.PriceList> candidates = priceLists.candidates(companyId, currencyCode, customerGroupId).stream()
                .filter(l -> l.validOn(date))
                .toList();
        Optional<SalesViews.PriceList> group = customerGroupId == null
                ? Optional.empty()
                : candidates.stream()
                        .filter(l -> customerGroupId.equals(l.customerGroupId()))
                        .findFirst();
        return group.orElseGet(() -> candidates.stream()
                .filter(SalesViews.PriceList::isDefault)
                .findFirst()
                .orElse(null));
    }

    @Nullable VariantInfo variant(String pointer, UUID variantId, List<FieldViolation> violations) {
        VariantInfo variant = inventory.variantInfo(variantId).orElse(null);
        if (variant == null) {
            violations.add(
                    FieldViolation.atPointer(pointer, "UNKNOWN_VARIANT", "is not a product variant of the company"));
            return null;
        }
        if (!"ACTIVE".equals(variant.status()) || !variant.sellable()) {
            violations.add(FieldViolation.atPointer(pointer, "NOT_SELLABLE", "must be an active, sellable product"));
            return null;
        }
        return variant;
    }

    @Nullable BigDecimal quantityBase(
            String pointer, UUID variantId, BigDecimal quantity, UUID uomId, List<FieldViolation> violations) {
        if (quantity.signum() <= 0) {
            violations.add(FieldViolation.atPointer(pointer + "/quantity", "POSITIVE", "must be greater than 0"));
            return null;
        }
        try {
            return inventory.convertQuantity(variantId, quantity, uomId);
        } catch (ApiException e) {
            boolean unit = "UOM_NOT_CONVERTIBLE".equals(e.errorCode().code())
                    || !e.violations().isEmpty();
            violations.add(FieldViolation.atPointer(
                    pointer + (unit ? "/uomId" : "/quantity"),
                    unit ? "UOM_NOT_CONVERTIBLE" : "INVALID_QUANTITY",
                    e.getMessage()));
            return null;
        }
    }

    /** The code's rate if it is usable on sales on the date (exempt codes rate 0). */
    @Nullable BigDecimal taxRate(String pointer, UUID taxCodeId, LocalDate date, List<FieldViolation> violations) {
        TaxCodeSummary code =
                org.taxCode(CurrentContext.requireCompany(), taxCodeId).orElse(null);
        if (code == null || !code.appliesToSales() || !code.usableOn(date)) {
            violations.add(FieldViolation.atPointer(
                    pointer,
                    "INVALID_TAX_CODE",
                    "must be a sales tax code of the company that is active and valid on " + date));
            return null;
        }
        return code.exempt() ? BigDecimal.ZERO : code.ratePercent();
    }

    /** The default tax code of a line: the product's sales code, else the customer's (if usable). */
    @Nullable UUID defaultTaxCode(VariantInfo variant, @Nullable UUID customerCode, LocalDate date) {
        for (UUID candidate : new UUID[] {variant.salesTaxCodeId(), customerCode}) {
            if (candidate != null
                    && org.taxCode(CurrentContext.requireCompany(), candidate)
                            .map(t -> t.appliesToSales() && t.usableOn(date))
                            .orElse(false)) {
                return candidate;
            }
        }
        return null;
    }

    /** The list price of a variant for a quantity: tiers in the line's unit, else in the base unit. */
    @Nullable BigDecimal listPrice(
            SalesViews.@Nullable PriceList list,
            VariantInfo variant,
            BigDecimal quantity,
            UUID uomId,
            BigDecimal quantityBase,
            LocalDate date) {
        if (list == null) {
            return null;
        }
        UUID companyId = CurrentContext.requireCompany();
        Optional<PriceSelection.Item> inUnit =
                PriceSelection.best(items(companyId, list.id(), variant.variantId(), uomId), quantity, date);
        if (inUnit.isPresent()) {
            return inUnit.get().unitPrice();
        }
        if (uomId.equals(variant.baseUomId())) {
            return null;
        }
        return PriceSelection.best(
                        items(companyId, list.id(), variant.variantId(), variant.baseUomId()), quantityBase, date)
                .map(i -> i.unitPrice().multiply(quantityBase).divide(quantity, UNIT_PRICE_SCALE, RoundingMode.HALF_UP))
                .orElse(null);
    }

    private List<PriceSelection.Item> items(UUID companyId, UUID listId, UUID variantId, UUID uomId) {
        return priceLists.tiers(companyId, listId, variantId, uomId).stream()
                .map(i -> new PriceSelection.Item(i.minQuantity(), i.unitPrice(), i.validFrom(), i.validTo()))
                .toList();
    }

    /**
     * Prices the lines in the document currency. Throws {@code 422} with every problem at once
     * ({@code PRICE_MISSING} when that is the only kind); {@code extra} are problems the caller found
     * before. {@code approved} are the stored lines whose prices and discounts need no new approval.
     */
    Priced price(
            List<SalesCommands.PricedLine> inputs,
            Terms terms,
            CompanyProfile profile,
            RoundingPolicy rounding,
            List<FieldViolation> extra,
            List<SalesViews.Line> approved) {
        List<FieldViolation> violations = new ArrayList<>(extra);
        if (inputs.isEmpty() || inputs.size() > MAX_LINES) {
            violations.add(
                    FieldViolation.atPointer("/lines", "SIZE", "must contain between 1 and " + MAX_LINES + " lines"));
        }
        List<FieldViolation> missingPrices = new ArrayList<>();
        Map<Integer, VariantInfo> variants = new HashMap<>();
        Map<Integer, BigDecimal> bases = new HashMap<>();
        Map<Integer, BigDecimal> prices = new HashMap<>();
        Map<Integer, BigDecimal> listPrices = new HashMap<>();
        Map<Integer, UUID> taxCodes = new HashMap<>();
        Map<Integer, BigDecimal> rates = new HashMap<>();
        for (int i = 0; i < inputs.size(); i++) {
            SalesCommands.PricedLine in = inputs.get(i);
            String at = "/lines/" + i;
            if (in.unitPrice() != null
                    && (in.unitPrice().signum() < 0
                            || in.unitPrice().stripTrailingZeros().scale() > UNIT_PRICE_SCALE)) {
                violations.add(FieldViolation.atPointer(
                        at + "/unitPrice", "INVALID_VALUE", "must be ≥ 0 with at most 6 decimal places"));
            }
            if (in.discountPercent().signum() < 0
                    || in.discountPercent().compareTo(BigDecimal.valueOf(100)) > 0
                    || in.discountPercent().stripTrailingZeros().scale() > 4) {
                violations.add(FieldViolation.atPointer(
                        at + "/discountPercent", "INVALID_VALUE", "must be between 0 and 100 with at most 4 decimals"));
            }
            VariantInfo variant = variant(at + "/variantId", in.variantId(), violations);
            if (variant == null) {
                continue;
            }
            variants.put(i, variant);
            BigDecimal base = quantityBase(at, in.variantId(), in.quantity(), in.uomId(), violations);
            if (base != null) {
                bases.put(i, base);
                BigDecimal list = listPrice(terms.priceList(), variant, in.quantity(), in.uomId(), base, terms.date());
                if (list != null) {
                    listPrices.put(i, list);
                }
                BigDecimal price = in.unitPrice() != null ? in.unitPrice() : list;
                if (price == null) {
                    missingPrices.add(FieldViolation.atPointer(
                            at + "/unitPrice", "PRICE_MISSING", "has no price list price; enter a unit price"));
                } else {
                    prices.put(i, price);
                }
            }
            UUID taxCode = in.taxCodeId() != null
                    ? in.taxCodeId()
                    : defaultTaxCode(variant, terms.customerTaxCodeId(), terms.date());
            if (taxCode != null) {
                BigDecimal rate = taxRate(at + "/taxCodeId", taxCode, terms.date(), violations);
                if (rate != null) {
                    taxCodes.put(i, taxCode);
                    rates.put(i, rate);
                }
            }
        }
        if (!violations.isEmpty()) {
            violations.addAll(missingPrices);
            throw ApiException.validationFailed("The document is invalid.", violations);
        }
        if (!missingPrices.isEmpty()) {
            throw new ApiException(
                    SalesErrorCode.PRICE_MISSING, "Some lines have no price on the price list.", missingPrices);
        }
        List<TaxCalculator.Line> taxLines = new ArrayList<>();
        for (int i = 0; i < inputs.size(); i++) {
            SalesCommands.PricedLine in = inputs.get(i);
            taxLines.add(new TaxCalculator.Line(
                    in.quantity(),
                    prices.get(i),
                    in.discountPercent(),
                    taxCodes.containsKey(i) ? new TaxCalculator.Rate(taxCodes.get(i), rates.get(i)) : null));
        }
        TaxCalculator.Result result = taxes.calculate(new TaxCalculator.Request(
                taxLines,
                terms.pricesIncludeTax(),
                rounding,
                TaxCalculator.TaxRounding.valueOf(profile.taxRounding())));
        List<Line> lines = new ArrayList<>();
        for (int i = 0; i < inputs.size(); i++) {
            SalesCommands.PricedLine in = inputs.get(i);
            VariantInfo variant = variants.get(i);
            TaxCalculator.LineResult r = result.lines().get(i);
            lines.add(new Line(
                    i,
                    in,
                    variant,
                    in.description() != null && !in.description().isBlank() ? in.description() : variant.name(),
                    bases.get(i),
                    prices.get(i),
                    listPrices.get(i),
                    taxCodes.get(i),
                    rates.get(i),
                    r.net(),
                    r.tax(),
                    r.total()));
        }
        checkApprovals(lines, approved);
        return new Priced(List.copyOf(lines), result);
    }

    /** SAL-1: manual prices off the list need override_price, high discounts need discount_high. */
    private void checkApprovals(List<Line> lines, List<SalesViews.Line> approved) {
        Set<String> carried = new HashSet<>();
        approved.forEach(l -> carried.add(key(l.variantId(), l.uomId(), l.unitPrice(), l.discountPercent())));
        BigDecimal threshold = settingsThreshold();
        boolean overridden = false;
        boolean discounted = false;
        for (Line line : lines) {
            if (carried.contains(key(
                    line.variant().variantId(),
                    line.input().uomId(),
                    line.unitPrice(),
                    line.input().discountPercent()))) {
                continue;
            }
            if (line.listPrice() != null && line.unitPrice().compareTo(line.listPrice()) != 0) {
                overridden = true;
            }
            if (threshold != null && line.input().discountPercent().compareTo(threshold) > 0) {
                discounted = true;
            }
        }
        if (overridden) {
            context.require(SalesPermissions.ORDER_OVERRIDE_PRICE, "A unit price that differs from the price list");
        }
        if (discounted) {
            context.require(
                    SalesPermissions.ORDER_DISCOUNT_HIGH,
                    "A discount above "
                            + Objects.requireNonNull(threshold)
                                    .stripTrailingZeros()
                                    .toPlainString() + " %");
        }
    }

    private @Nullable BigDecimal settingsThreshold() {
        return settings.current(CurrentContext.requireCompany()).discountApprovalThresholdPercent();
    }

    private static String key(UUID variantId, UUID uomId, BigDecimal unitPrice, BigDecimal discount) {
        return variantId + "|" + uomId + "|" + unitPrice.stripTrailingZeros().toPlainString() + "|"
                + discount.stripTrailingZeros().toPlainString();
    }
}
