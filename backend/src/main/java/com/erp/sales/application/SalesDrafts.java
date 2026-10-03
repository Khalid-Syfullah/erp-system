package com.erp.sales.application;

import com.erp.inventory.api.InventoryFacade;
import com.erp.org.api.CompanyProfile;
import com.erp.org.api.OrgFacade;
import com.erp.org.api.TaxCalculator;
import com.erp.partners.api.PartnersFacade;
import com.erp.partners.api.PartnersFacade.CustomerInfo;
import com.erp.platform.context.CurrentContext;
import com.erp.platform.web.ApiException;
import com.erp.platform.web.FieldViolation;
import com.erp.platform.web.MergePatchLines;
import com.erp.sales.persistence.QuotationRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * The header and lines of a quotation or order draft: the customer (active, locked for use), the
 * warehouse and its branch (visible, active), the currency and payment terms (defaulting from the
 * customer), the price list (SAL-1) and the priced lines.
 */
@Component
class SalesDrafts {

    static final List<MergePatchLines.Member> LINE_MEMBERS = List.of(
            MergePatchLines.Member.uuid("variantId", true),
            MergePatchLines.Member.text("description", 300),
            MergePatchLines.Member.decimal("quantity", true),
            MergePatchLines.Member.uuid("uomId", true),
            MergePatchLines.Member.decimal("unitPrice", false),
            MergePatchLines.Member.decimal("discountPercent", false),
            MergePatchLines.Member.uuid("taxCodeId", false));

    /**
     * What a draft is built from; {@code currentCustomerId} is the stored document's customer (which
     * may still be used even if it is no longer active), {@code approved} its stored lines.
     */
    record Input(
            UUID customerId,
            @Nullable UUID currentCustomerId,
            UUID warehouseId,
            LocalDate date,
            @Nullable String currencyCode,
            @Nullable UUID priceListId,
            @Nullable Boolean pricesIncludeTax,
            @Nullable UUID paymentTermsId,
            List<SalesCommands.PricedLine> lines,
            List<SalesViews.Line> approved,
            List<FieldViolation> extra) {}

    record Draft(
            CustomerInfo customer,
            UUID branchId,
            String currencyCode,
            @Nullable UUID priceListId,
            boolean pricesIncludeTax,
            @Nullable UUID paymentTermsId,
            List<QuotationRepository.NewLine> lines,
            TaxCalculator.Result totals) {}

    private final PartnersFacade partners;
    private final InventoryFacade inventory;
    private final OrgFacade org;
    private final SalesPricing pricing;
    private final SalesContext context;

    SalesDrafts(
            PartnersFacade partners,
            InventoryFacade inventory,
            OrgFacade org,
            SalesPricing pricing,
            SalesContext context) {
        this.partners = partners;
        this.inventory = inventory;
        this.org = org;
        this.pricing = pricing;
        this.context = context;
    }

    Draft build(Input in, CompanyProfile profile) {
        List<FieldViolation> violations = new ArrayList<>(in.extra());
        CustomerInfo customer = in.customerId().equals(in.currentCustomerId())
                ? partners.customerForUse(in.customerId()).orElseThrow()
                : usableCustomer(in.customerId());
        UUID branchId = warehouseBranch(in.warehouseId(), violations);
        String currency = in.currencyCode() != null ? in.currencyCode() : customer.currencyCode();
        if (!context.currencyUsable(currency)) {
            violations.add(FieldViolation.atPointer("/currencyCode", "UNKNOWN_CURRENCY", "is not an active currency"));
        }
        UUID terms = in.paymentTermsId() != null ? in.paymentTermsId() : customer.paymentTermsId();
        if (terms != null
                && !org.paymentTerms(CurrentContext.requireCompany(), terms)
                        .map(t -> t.active())
                        .orElse(false)) {
            violations.add(FieldViolation.atPointer(
                    "/paymentTermsId", "INVALID_VALUE", "must be active payment terms of the company"));
        }
        SalesViews.PriceList list =
                pricing.priceList(in.priceListId(), currency, customer.customerGroupId(), in.date(), violations);
        boolean inclusive =
                in.pricesIncludeTax() != null ? in.pricesIncludeTax() : list != null && list.pricesIncludeTax();
        SalesPricing.Priced priced = pricing.price(
                in.lines(),
                new SalesPricing.Terms(currency, list, inclusive, in.date(), customer.defaultTaxCodeId()),
                profile,
                context.rounding(currency, profile),
                violations,
                in.approved());
        List<QuotationRepository.NewLine> lines = new ArrayList<>();
        for (SalesPricing.Line p : priced.lines()) {
            lines.add(new QuotationRepository.NewLine(
                    p.index() + 1,
                    p.variant().variantId(),
                    p.description(),
                    p.stockable(),
                    p.input().quantity(),
                    p.input().uomId(),
                    p.quantityBase(),
                    p.unitPrice(),
                    p.input().discountPercent(),
                    p.taxCodeId(),
                    p.net(),
                    p.tax(),
                    p.total()));
        }
        return new Draft(
                customer,
                java.util.Objects.requireNonNull(branchId),
                currency,
                list == null ? null : list.id(),
                inclusive,
                terms,
                lines,
                priced.result());
    }

    /** The customer if it may be used on new documents ({@code 422 PARTNER_BLOCKED} otherwise). */
    CustomerInfo usableCustomer(UUID customerId) {
        CustomerInfo customer = partners.customerForUse(customerId)
                .orElseThrow(() -> ApiException.validationFailed(
                        "The customer is unknown.",
                        List.of(FieldViolation.atPointer(
                                "/customerId", "UNKNOWN_CUSTOMER", "is not a customer of the company"))));
        if (!customer.usable()) {
            throw new ApiException(
                    SalesErrorCode.PARTNER_BLOCKED,
                    "Customer " + customer.code() + " is " + customer.status()
                            + " and cannot be used on new documents.",
                    List.of(FieldViolation.atPointer("/customerId", customer.status(), "is not an active customer")));
        }
        return customer;
    }

    /** The warehouse's branch if the caller may use it; problems go to {@code violations}. */
    @Nullable UUID warehouseBranch(UUID warehouseId, List<FieldViolation> violations) {
        var warehouse = inventory
                .warehouse(warehouseId)
                .filter(w -> context.canSeeBranch(w.branchId()))
                .orElse(null);
        if (warehouse == null) {
            violations.add(
                    FieldViolation.atPointer("/warehouseId", "UNKNOWN_WAREHOUSE", "is not a warehouse you can use"));
            return null;
        }
        if (!warehouse.active()) {
            violations.add(FieldViolation.atPointer("/warehouseId", "INACTIVE", "must be an active warehouse"));
            return null;
        }
        context.checkBranch(warehouse.branchId(), "/warehouseId", violations);
        return warehouse.branchId();
    }

    /** The customer's default address of the type (else of the other type) as a snapshot (G-10). */
    Map<String, Object> address(UUID customerId, String type) {
        var address = partners.defaultAddress(customerId, type)
                .or(() -> partners.defaultAddress(customerId, "BILLING".equals(type) ? "SHIPPING" : "BILLING"));
        Map<String, Object> snapshot = new LinkedHashMap<>();
        address.ifPresent(a -> {
            snapshot.put("line1", a.line1());
            if (a.line2() != null) {
                snapshot.put("line2", a.line2());
            }
            if (a.city() != null) {
                snapshot.put("city", a.city());
            }
            if (a.region() != null) {
                snapshot.put("region", a.region());
            }
            if (a.postalCode() != null) {
                snapshot.put("postalCode", a.postalCode());
            }
            snapshot.put("countryCode", a.countryCode());
        });
        return snapshot;
    }

    static List<SalesCommands.PricedLine> readLines(JsonNode array) {
        return MergePatchLines.read(array, LINE_MEMBERS).stream()
                .map(v -> new SalesCommands.PricedLine(
                        v.uuid("variantId"),
                        v.text("description"),
                        v.decimal("quantity"),
                        v.uuid("uomId"),
                        v.decimal("unitPrice"),
                        v.decimal("discountPercent") == null ? BigDecimal.ZERO : v.decimal("discountPercent"),
                        v.uuid("taxCodeId")))
                .toList();
    }

    /** A stored line as a command that keeps its price, discount and tax code. */
    static SalesCommands.PricedLine asCommand(SalesViews.Line l) {
        return new SalesCommands.PricedLine(
                l.variantId(),
                l.description(),
                l.quantity(),
                l.uomId(),
                l.unitPrice(),
                l.discountPercent(),
                l.taxCodeId());
    }
}
