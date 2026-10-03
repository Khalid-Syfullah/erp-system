package com.erp.sales.application;

import com.erp.inventory.api.InventoryFacade;
import com.erp.org.api.CompanyProfile;
import com.erp.partners.api.PartnersFacade;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditPort;
import com.erp.platform.context.CurrentContext;
import com.erp.platform.web.ApiException;
import com.erp.platform.web.EntityTags;
import com.erp.platform.web.FieldViolation;
import com.erp.platform.web.MergePatch;
import com.erp.platform.web.PlatformErrorCode;
import com.erp.platform.web.paging.ListQuery;
import com.erp.platform.web.paging.PageResponse;
import com.erp.sales.persistence.PriceListRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * Price lists and their quantity tiers (SAL-1), and the price quote that applies them without
 * storing anything. One default list per currency: making a list the default clears the flag on the
 * currency's previous default.
 */
@Service
public class PriceListService {

    static final Set<String> LIST_PATCHABLE =
            Set.of("name", "pricesIncludeTax", "customerGroupId", "isDefault", "validFrom", "validTo", "isActive");
    static final Set<String> ITEM_PATCHABLE = Set.of("minQuantity", "unitPrice", "validFrom", "validTo");
    private static final BigDecimal MAX_QUANTITY = new BigDecimal("999999999999.999999");
    private static final BigDecimal MAX_PRICE = new BigDecimal("9999999999999.999999");

    private final PriceListRepository lists;
    private final SalesPricing pricing;
    private final PartnersFacade partners;
    private final InventoryFacade inventory;
    private final SalesContext context;
    private final AuditPort audit;

    PriceListService(
            PriceListRepository lists,
            SalesPricing pricing,
            PartnersFacade partners,
            InventoryFacade inventory,
            SalesContext context,
            AuditPort audit) {
        this.lists = lists;
        this.pricing = pricing;
        this.partners = partners;
        this.inventory = inventory;
        this.context = context;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public PageResponse<SalesViews.PriceList> list(ListQuery query) {
        return lists.list(CurrentContext.requireCompany(), query);
    }

    @Transactional(readOnly = true)
    public SalesViews.PriceList get(UUID id) {
        return lists.find(CurrentContext.requireCompany(), id).orElseThrow(ApiException::notFound);
    }

    @Transactional
    public SalesViews.PriceList create(SalesCommands.PriceList command) {
        UUID companyId = CurrentContext.requireCompany();
        List<FieldViolation> violations = new ArrayList<>();
        if (!context.currencyUsable(command.currencyCode())) {
            violations.add(FieldViolation.atPointer("/currencyCode", "UNKNOWN_CURRENCY", "is not an active currency"));
        }
        checkList(command, violations);
        if (!violations.isEmpty()) {
            throw ApiException.validationFailed("The price list is invalid.", violations);
        }
        if (command.isDefault()) {
            clearDefault(companyId, command.currencyCode(), null);
        }
        UUID id = lists.insert(companyId, values(command), context.actor());
        audit.record(AuditEvent.builder("CREATE", "sales")
                .entity("price_list", id, command.code())
                .detail("currencyCode", command.currencyCode())
                .detail("customerGroupId", command.customerGroupId())
                .detail("isDefault", command.isDefault())
                .build());
        return get(id);
    }

    @Transactional
    public SalesViews.PriceList patch(UUID id, @Nullable String ifMatch, JsonNode document) {
        UUID companyId = CurrentContext.requireCompany();
        SalesViews.PriceList current = lists.lock(companyId, id).orElseThrow(ApiException::notFound);
        EntityTags.requireMatch(ifMatch, current.version());
        MergePatch patch = MergePatch.of(document, LIST_PATCHABLE);
        var name = patch.text("name", true, 100);
        var inclusive = patch.bool("pricesIncludeTax");
        var group = patch.uuid("customerGroupId", false);
        var isDefault = patch.bool("isDefault");
        var validFrom = patch.date("validFrom", false);
        var validTo = patch.date("validTo", false);
        var active = patch.bool("isActive");
        patch.throwIfInvalid();
        SalesCommands.PriceList next = new SalesCommands.PriceList(
                current.code(),
                Objects.requireNonNull(name.orElse(current.name())),
                current.currencyCode(),
                Boolean.TRUE.equals(inclusive.orElse(current.pricesIncludeTax())),
                group.orElse(current.customerGroupId()),
                Boolean.TRUE.equals(isDefault.orElse(current.isDefault())),
                validFrom.orElse(current.validFrom()),
                validTo.orElse(current.validTo()),
                Boolean.TRUE.equals(active.orElse(current.active())));
        List<FieldViolation> violations = new ArrayList<>();
        if (!Objects.equals(next.customerGroupId(), current.customerGroupId())) {
            checkList(next, violations);
        } else {
            checkDates(next, violations);
        }
        if (!violations.isEmpty()) {
            throw ApiException.validationFailed("The price list is invalid.", violations);
        }
        if (next.isDefault() && !current.isDefault()) {
            clearDefault(companyId, current.currencyCode(), id);
        }
        if (!lists.update(companyId, id, current.version(), values(next), context.actor())) {
            throw new ApiException(PlatformErrorCode.VERSION_CONFLICT, "The price list was modified concurrently.");
        }
        SalesViews.PriceList after = get(id);
        audit.record(AuditEvent.builder("UPDATE", "sales")
                .entity("price_list", id, current.code())
                .change("name", current.name(), after.name())
                .change("pricesIncludeTax", current.pricesIncludeTax(), after.pricesIncludeTax())
                .change("customerGroupId", current.customerGroupId(), after.customerGroupId())
                .change("isDefault", current.isDefault(), after.isDefault())
                .change("validFrom", current.validFrom(), after.validFrom())
                .change("validTo", current.validTo(), after.validTo())
                .change("isActive", current.active(), after.active())
                .build());
        return after;
    }

    // ---------------------------------------------------------------------------------- items

    @Transactional(readOnly = true)
    public PageResponse<SalesViews.PriceListItem> items(UUID listId, ListQuery query) {
        UUID companyId = CurrentContext.requireCompany();
        lists.find(companyId, listId).orElseThrow(ApiException::notFound);
        return lists.items(companyId, listId, query);
    }

    @Transactional(readOnly = true)
    public SalesViews.PriceListItem item(UUID listId, UUID itemId) {
        return lists.findItem(CurrentContext.requireCompany(), listId, itemId).orElseThrow(ApiException::notFound);
    }

    @Transactional
    public SalesViews.PriceListItem createItem(UUID listId, SalesCommands.PriceListItem command) {
        UUID companyId = CurrentContext.requireCompany();
        SalesViews.PriceList list = lists.lock(companyId, listId).orElseThrow(ApiException::notFound);
        List<FieldViolation> violations = new ArrayList<>();
        if (pricing.variant("/variantId", command.variantId(), violations) != null) {
            try {
                inventory.convertQuantity(command.variantId(), BigDecimal.ONE, command.uomId());
            } catch (ApiException e) {
                violations.add(FieldViolation.atPointer("/uomId", "UOM_NOT_CONVERTIBLE", e.getMessage()));
            }
        }
        checkItem(command.minQuantity(), command.unitPrice(), command.validFrom(), command.validTo(), violations);
        if (!violations.isEmpty()) {
            throw ApiException.validationFailed("The price list item is invalid.", violations);
        }
        UUID id = lists.insertItem(
                companyId,
                listId,
                new PriceListRepository.ItemValues(
                        command.variantId(),
                        command.uomId(),
                        command.minQuantity(),
                        command.unitPrice(),
                        command.validFrom(),
                        command.validTo()),
                context.actor());
        audit.record(AuditEvent.builder("CREATE", "sales")
                .entity("price_list_item", id, list.code())
                .detail("priceListId", listId)
                .detail("variantId", command.variantId())
                .detail("uomId", command.uomId())
                .detail("minQuantity", command.minQuantity().toPlainString())
                .detail("unitPrice", command.unitPrice().toPlainString())
                .build());
        return item(listId, id);
    }

    @Transactional
    public SalesViews.PriceListItem patchItem(UUID listId, UUID itemId, @Nullable String ifMatch, JsonNode document) {
        UUID companyId = CurrentContext.requireCompany();
        SalesViews.PriceList list = lists.lock(companyId, listId).orElseThrow(ApiException::notFound);
        SalesViews.PriceListItem current = item(listId, itemId);
        EntityTags.requireMatch(ifMatch, current.version());
        MergePatch patch = MergePatch.of(document, ITEM_PATCHABLE);
        var minQuantity = patch.decimal("minQuantity", BigDecimal.ZERO, MAX_QUANTITY, 6);
        var unitPrice = patch.decimal("unitPrice", BigDecimal.ZERO, MAX_PRICE, 6);
        var validFrom = patch.date("validFrom", false);
        var validTo = patch.date("validTo", false);
        patch.throwIfInvalid();
        PriceListRepository.ItemValues next = new PriceListRepository.ItemValues(
                current.variantId(),
                current.uomId(),
                Objects.requireNonNull(minQuantity.orElse(current.minQuantity())),
                Objects.requireNonNull(unitPrice.orElse(current.unitPrice())),
                validFrom.orElse(current.validFrom()),
                validTo.orElse(current.validTo()));
        List<FieldViolation> violations = new ArrayList<>();
        checkItem(next.minQuantity(), next.unitPrice(), next.validFrom(), next.validTo(), violations);
        if (!violations.isEmpty()) {
            throw ApiException.validationFailed("The price list item is invalid.", violations);
        }
        if (!lists.updateItem(companyId, listId, itemId, current.version(), next, context.actor())) {
            throw new ApiException(PlatformErrorCode.VERSION_CONFLICT, "The item was modified concurrently.");
        }
        SalesViews.PriceListItem after = item(listId, itemId);
        audit.record(AuditEvent.builder("UPDATE", "sales")
                .entity("price_list_item", itemId, list.code())
                .change(
                        "minQuantity",
                        current.minQuantity().toPlainString(),
                        after.minQuantity().toPlainString())
                .change(
                        "unitPrice",
                        current.unitPrice().toPlainString(),
                        after.unitPrice().toPlainString())
                .change("validFrom", current.validFrom(), after.validFrom())
                .change("validTo", current.validTo(), after.validTo())
                .build());
        return after;
    }

    @Transactional
    public void deleteItem(UUID listId, UUID itemId, @Nullable String ifMatch) {
        UUID companyId = CurrentContext.requireCompany();
        SalesViews.PriceList list = lists.lock(companyId, listId).orElseThrow(ApiException::notFound);
        SalesViews.PriceListItem current = item(listId, itemId);
        EntityTags.requireMatch(ifMatch, current.version());
        if (!lists.deleteItem(companyId, listId, itemId)) {
            throw ApiException.notFound();
        }
        audit.record(AuditEvent.builder("DELETE", "sales")
                .entity("price_list_item", itemId, list.code())
                .detail("variantId", current.variantId())
                .detail("unitPrice", current.unitPrice().toPlainString())
                .build());
    }

    // ---------------------------------------------------------------------------------- quote

    /** Prices lines for a customer as an order would (SAL-1, G-14) without storing anything. */
    @Transactional(readOnly = true)
    public SalesViews.PriceQuote quote(SalesCommands.Quote command) {
        var customer = partners.customer(command.customerId())
                .orElseThrow(() -> ApiException.validationFailed(
                        "The customer is unknown.",
                        List.of(FieldViolation.atPointer(
                                "/customerId", "UNKNOWN_CUSTOMER", "is not a customer of the company"))));
        CompanyProfile profile = context.profile();
        String currency = command.currencyCode() != null ? command.currencyCode() : customer.currencyCode();
        LocalDate date = command.date() != null ? command.date() : context.today(profile);
        List<FieldViolation> violations = new ArrayList<>();
        if (!context.currencyUsable(currency)) {
            violations.add(FieldViolation.atPointer("/currencyCode", "UNKNOWN_CURRENCY", "is not an active currency"));
        }
        SalesViews.PriceList list =
                pricing.priceList(command.priceListId(), currency, customer.customerGroupId(), date, violations);
        boolean inclusive = list != null && list.pricesIncludeTax();
        SalesPricing.Priced priced = pricing.price(
                command.lines(),
                new SalesPricing.Terms(currency, list, inclusive, date, customer.defaultTaxCodeId()),
                profile,
                context.rounding(currency, profile),
                violations,
                List.of());
        return new SalesViews.PriceQuote(
                currency,
                list == null ? null : list.id(),
                inclusive,
                priced.lines().stream()
                        .map(l -> new SalesViews.QuotedLine(
                                l.variant().variantId(),
                                l.input().quantity(),
                                l.input().uomId(),
                                l.unitPrice(),
                                l.listPrice(),
                                l.taxCodeId(),
                                l.net(),
                                l.tax(),
                                l.total()))
                        .toList(),
                priced.result().subtotal(),
                priced.result().taxTotal(),
                priced.result().total());
    }

    // ------------------------------------------------------------------------------ helpers

    /** The previous default of the currency stops being the default (audited). */
    private void clearDefault(UUID companyId, String currencyCode, @Nullable UUID except) {
        for (SalesViews.PriceList cleared : lists.clearDefault(companyId, currencyCode, except, context.actor())) {
            audit.record(AuditEvent.builder("UPDATE", "sales")
                    .entity("price_list", cleared.id(), cleared.code())
                    .change("isDefault", true, false)
                    .build());
        }
    }

    private void checkList(SalesCommands.PriceList command, List<FieldViolation> violations) {
        if (command.customerGroupId() != null && !partners.customerGroupUsable(command.customerGroupId())) {
            violations.add(FieldViolation.atPointer(
                    "/customerGroupId", "INVALID_VALUE", "must be an active customer group of the company"));
        }
        checkDates(command, violations);
    }

    private static void checkDates(SalesCommands.PriceList command, List<FieldViolation> violations) {
        if (command.validFrom() != null
                && command.validTo() != null
                && command.validTo().isBefore(command.validFrom())) {
            violations.add(FieldViolation.atPointer("/validTo", "BEFORE_VALID_FROM", "must not be before validFrom"));
        }
    }

    private static void checkItem(
            BigDecimal minQuantity,
            BigDecimal unitPrice,
            @Nullable LocalDate validFrom,
            @Nullable LocalDate validTo,
            List<FieldViolation> violations) {
        if (minQuantity.signum() < 0 || minQuantity.stripTrailingZeros().scale() > 6) {
            violations.add(FieldViolation.atPointer(
                    "/minQuantity", "INVALID_VALUE", "must be ≥ 0 with at most 6 decimal places"));
        }
        if (unitPrice.signum() < 0 || unitPrice.stripTrailingZeros().scale() > 6) {
            violations.add(FieldViolation.atPointer(
                    "/unitPrice", "INVALID_VALUE", "must be ≥ 0 with at most 6 decimal places"));
        }
        if (validFrom != null && validTo != null && validTo.isBefore(validFrom)) {
            violations.add(FieldViolation.atPointer("/validTo", "BEFORE_VALID_FROM", "must not be before validFrom"));
        }
    }

    private static PriceListRepository.ListValues values(SalesCommands.PriceList c) {
        return new PriceListRepository.ListValues(
                c.code(),
                c.name(),
                c.currencyCode(),
                c.pricesIncludeTax(),
                c.customerGroupId(),
                c.isDefault(),
                c.validFrom(),
                c.validTo(),
                c.active());
    }
}
