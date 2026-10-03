package com.erp.inventory.application;

import com.erp.inventory.domain.UomConversion;
import com.erp.inventory.persistence.AttributeRepository;
import com.erp.inventory.persistence.CategoryRepository;
import com.erp.inventory.persistence.ProductRepository;
import com.erp.inventory.persistence.UomRepository;
import com.erp.inventory.persistence.VariantRepository;
import com.erp.org.api.OrgFacade;
import com.erp.org.api.TaxCodeSummary;
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
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * Products, their variants (SKUs) and product-specific unit conversions (PRODUCT_SPEC.md §6.1):
 *
 * <ul>
 *   <li>a product without variants gets exactly one default variant; a product with variants gets
 *       variants defined by unique combinations of attribute values;
 *   <li>the purchase and sales units must convert to the base unit; the base unit is locked once
 *       stock moved (service check and trigger);
 *   <li>tax codes must be active and fit their use (sales or purchase);
 *   <li>archiving takes the product off new documents and needs it to hold no stock.
 * </ul>
 */
@Service
public class ProductService {

    static final Set<String> PATCHABLE = Set.of(
            "name",
            "description",
            "categoryId",
            "baseUomId",
            "purchaseUomId",
            "salesUomId",
            "isPurchasable",
            "isSellable",
            "salesTaxCodeId",
            "purchaseTaxCodeId");
    static final Set<String> VARIANT_PATCHABLE = Set.of("sku", "barcode", "name", "weightKg");

    /** The default variant's identifiers, for products without variants. */
    public record DefaultVariant(
            @Nullable String sku, @Nullable String barcode) {}

    /** A product with its variants. */
    public record ProductDetail(InventoryViews.Product product, List<InventoryViews.Variant> variants) {}

    private final ProductRepository products;
    private final VariantRepository variants;
    private final CategoryRepository categories;
    private final AttributeRepository attributes;
    private final UomRepository uoms;
    private final OrgFacade org;
    private final AuditPort audit;

    ProductService(
            ProductRepository products,
            VariantRepository variants,
            CategoryRepository categories,
            AttributeRepository attributes,
            UomRepository uoms,
            OrgFacade org,
            AuditPort audit) {
        this.products = products;
        this.variants = variants;
        this.categories = categories;
        this.attributes = attributes;
        this.uoms = uoms;
        this.org = org;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public PageResponse<InventoryViews.Product> list(ListQuery query) {
        return products.list(CurrentContext.requireCompany(), query);
    }

    @Transactional(readOnly = true)
    public ProductDetail get(UUID id) {
        UUID companyId = CurrentContext.requireCompany();
        InventoryViews.Product product = products.find(companyId, id).orElseThrow(ApiException::notFound);
        return new ProductDetail(product, variants.forProduct(companyId, id));
    }

    @Transactional
    public ProductDetail create(InventoryCommands.Product command, DefaultVariant defaultVariant) {
        UUID companyId = CurrentContext.requireCompany();
        List<FieldViolation> violations = new ArrayList<>();
        validateCategory(companyId, command.categoryId(), violations);
        validateUnits(companyId, null, command.baseUomId(), command.purchaseUomId(), command.salesUomId(), violations);
        validateTaxCodes(companyId, command.salesTaxCodeId(), command.purchaseTaxCodeId(), violations);
        if (command.hasVariants() && (defaultVariant.sku() != null || defaultVariant.barcode() != null)) {
            violations.add(FieldViolation.atPointer(
                    "/sku", "NOT_ALLOWED", "products with variants get their SKUs from their variants"));
        }
        if (!violations.isEmpty()) {
            throw ApiException.validationFailed("The product is invalid.", violations);
        }
        UUID actor = CurrentContext.requireActor().userId();
        UUID id = products.insert(companyId, command, actor);
        if (!command.hasVariants()) {
            variants.insert(
                    companyId,
                    id,
                    normalizeSku(defaultVariant.sku() != null ? defaultVariant.sku() : command.code()),
                    blankToNull(defaultVariant.barcode()),
                    command.name(),
                    true,
                    null,
                    Map.of(),
                    actor);
        }
        audit.record(AuditEvent.builder("CREATE", "inventory")
                .entity("product", id, command.code())
                .detail("name", command.name())
                .detail("productType", command.productType())
                .detail("categoryId", command.categoryId())
                .detail("baseUomId", command.baseUomId())
                .detail("hasVariants", command.hasVariants())
                .build());
        return get(id);
    }

    @Transactional
    public ProductDetail patch(UUID id, @Nullable String ifMatch, JsonNode document) {
        UUID companyId = CurrentContext.requireCompany();
        InventoryViews.Product current = lock(companyId, id);
        EntityTags.requireMatch(ifMatch, current.version());
        MergePatch patch = MergePatch.of(document, PATCHABLE);
        var name = patch.text("name", true, 200);
        var description = patch.text("description", false, 4000);
        var category = patch.uuid("categoryId", true);
        var base = patch.uuid("baseUomId", true);
        var purchaseUom = patch.uuid("purchaseUomId", false);
        var salesUom = patch.uuid("salesUomId", false);
        var purchasable = patch.bool("isPurchasable");
        var sellable = patch.bool("isSellable");
        var salesTax = patch.uuid("salesTaxCodeId", false);
        var purchaseTax = patch.uuid("purchaseTaxCodeId", false);
        patch.throwIfInvalid();
        InventoryCommands.Product next = new InventoryCommands.Product(
                current.code(),
                name.orElse(current.name()),
                description.orElse(current.description()),
                category.orElse(current.categoryId()),
                current.productType(),
                base.orElse(current.baseUomId()),
                purchaseUom.orElse(current.purchaseUomId()),
                salesUom.orElse(current.salesUomId()),
                Boolean.TRUE.equals(purchasable.orElse(current.purchasable())),
                Boolean.TRUE.equals(sellable.orElse(current.sellable())),
                salesTax.orElse(current.salesTaxCodeId()),
                purchaseTax.orElse(current.purchaseTaxCodeId()),
                current.hasVariants());
        List<FieldViolation> violations = new ArrayList<>();
        if (!next.categoryId().equals(current.categoryId())) {
            validateCategory(companyId, next.categoryId(), violations);
        }
        if (!next.baseUomId().equals(current.baseUomId()) && products.hasTransactions(companyId, id)) {
            violations.add(FieldViolation.atPointer(
                    "/baseUomId", "LOCKED", "cannot change once stock has moved (PRODUCT_SPEC.md §6.1)"));
        }
        validateUnits(companyId, id, next.baseUomId(), next.purchaseUomId(), next.salesUomId(), violations);
        validateTaxCodes(
                companyId,
                Objects.equals(next.salesTaxCodeId(), current.salesTaxCodeId()) ? null : next.salesTaxCodeId(),
                Objects.equals(next.purchaseTaxCodeId(), current.purchaseTaxCodeId()) ? null : next.purchaseTaxCodeId(),
                violations);
        if (!violations.isEmpty()) {
            throw ApiException.validationFailed("The product is invalid.", violations);
        }
        if (!products.update(
                companyId, id, current.version(), CurrentContext.requireActor().userId(), next, current.status())) {
            throw new ApiException(PlatformErrorCode.VERSION_CONFLICT, "The product was modified concurrently.");
        }
        InventoryViews.Product after = get(id).product();
        audit.record(AuditEvent.builder("UPDATE", "inventory")
                .entity("product", id, current.code())
                .change("name", current.name(), after.name())
                .change("description", current.description(), after.description())
                .change("categoryId", current.categoryId(), after.categoryId())
                .change("baseUomId", current.baseUomId(), after.baseUomId())
                .change("purchaseUomId", current.purchaseUomId(), after.purchaseUomId())
                .change("salesUomId", current.salesUomId(), after.salesUomId())
                .change("isPurchasable", current.purchasable(), after.purchasable())
                .change("isSellable", current.sellable(), after.sellable())
                .change("salesTaxCodeId", current.salesTaxCodeId(), after.salesTaxCodeId())
                .change("purchaseTaxCodeId", current.purchaseTaxCodeId(), after.purchaseTaxCodeId())
                .build());
        return get(id);
    }

    /** Archives (takes off new documents) or reactivates a product and its variants. */
    @Transactional
    public ProductDetail setArchived(UUID id, @Nullable String ifMatch, boolean archived) {
        UUID companyId = CurrentContext.requireCompany();
        InventoryViews.Product current = lock(companyId, id);
        EntityTags.requireMatch(ifMatch, current.version());
        String target = archived ? "ARCHIVED" : "ACTIVE";
        if (current.status().equals(target)) {
            throw new ApiException(
                    PlatformErrorCode.INVALID_STATE, "The product is already " + target.toLowerCase(Locale.ROOT) + ".");
        }
        if (archived && products.hasStock(companyId, id)) {
            throw new ApiException(
                    PlatformErrorCode.RESOURCE_IN_USE, "The product still has stock; move or adjust it out first.");
        }
        if (!archived
                && !categories
                        .find(companyId, current.categoryId())
                        .map(InventoryViews.Category::active)
                        .orElse(false)) {
            throw new ApiException(PlatformErrorCode.INVALID_STATE, "Activate the product's category first.");
        }
        UUID actor = CurrentContext.requireActor().userId();
        if (!products.update(companyId, id, current.version(), actor, asCommand(current), target)) {
            throw new ApiException(PlatformErrorCode.VERSION_CONFLICT, "The product was modified concurrently.");
        }
        variants.setStatusForProduct(companyId, id, target, actor);
        audit.record(AuditEvent.builder("STATE_CHANGE", "inventory")
                .entity("product", id, current.code())
                .transition(current.status(), target)
                .build());
        return get(id);
    }

    // ----------------------------------------------------------------------------- variants

    @Transactional(readOnly = true)
    public PageResponse<InventoryViews.Variant> variants(@Nullable UUID productId, ListQuery query) {
        UUID companyId = CurrentContext.requireCompany();
        if (productId != null) {
            products.find(companyId, productId).orElseThrow(ApiException::notFound);
        }
        return variants.list(companyId, productId, query);
    }

    @Transactional(readOnly = true)
    public InventoryViews.Variant variant(UUID id) {
        return variants.find(CurrentContext.requireCompany(), id).orElseThrow(ApiException::notFound);
    }

    @Transactional
    public InventoryViews.Variant createVariant(UUID productId, InventoryCommands.Variant command) {
        UUID companyId = CurrentContext.requireCompany();
        InventoryViews.Product product = lock(companyId, productId);
        if (!product.hasVariants()) {
            throw new ApiException(
                    PlatformErrorCode.INVALID_STATE,
                    "The product has a single default variant; it was created without variants.");
        }
        if (!product.active()) {
            throw new ApiException(PlatformErrorCode.INVALID_STATE, "The product is archived.");
        }
        Map<UUID, InventoryViews.AttributeValue> values = attributes.valuesById(
                companyId, new HashSet<>(command.attributeValues().values()));
        List<FieldViolation> violations = new ArrayList<>();
        if (command.attributeValues().isEmpty()) {
            violations.add(
                    FieldViolation.atPointer("/attributes", "REQUIRED", "a variant is defined by attribute values"));
        }
        command.attributeValues().forEach((attribute, value) -> {
            InventoryViews.AttributeValue v = values.get(value);
            if (v == null || !v.attributeId().equals(attribute)) {
                violations.add(FieldViolation.atPointer(
                        "/attributes/" + attribute, "UNKNOWN_ATTRIBUTE_VALUE", "is not a value of this attribute"));
            }
        });
        if (!violations.isEmpty()) {
            throw ApiException.validationFailed("The variant is invalid.", violations);
        }
        String name = command.name() != null
                ? command.name()
                : product.name() + " "
                        + command.attributeValues().values().stream()
                                .map(values::get)
                                .sorted(Comparator.comparing(InventoryViews.AttributeValue::sortOrder))
                                .map(InventoryViews.AttributeValue::name)
                                .collect(Collectors.joining(" / "));
        UUID id = variants.insert(
                companyId,
                productId,
                normalizeSku(command.sku()),
                blankToNull(command.barcode()),
                name,
                false,
                command.weightKg(),
                command.attributeValues(),
                CurrentContext.requireActor().userId());
        audit.record(AuditEvent.builder("CREATE", "inventory")
                .entity("product_variant", id, normalizeSku(command.sku()))
                .detail("productId", productId)
                .detail("name", name)
                .build());
        return variant(id);
    }

    @Transactional
    public InventoryViews.Variant patchVariant(UUID id, @Nullable String ifMatch, JsonNode document) {
        UUID companyId = CurrentContext.requireCompany();
        InventoryViews.Variant current = variants.lockForChange(companyId, id).orElseThrow(ApiException::notFound);
        EntityTags.requireMatch(ifMatch, current.version());
        MergePatch patch = MergePatch.of(document, VARIANT_PATCHABLE);
        var sku = patch.text("sku", true, 40);
        var barcode = patch.text("barcode", false, 48);
        var name = patch.text("name", true, 300);
        var weight = patch.decimal("weightKg", BigDecimal.ZERO, new BigDecimal("99999999.9999"), 4);
        patch.throwIfInvalid();
        String newSku = sku.present() ? normalizeSku(sku.value()) : current.sku();
        if (!variants.update(
                companyId,
                id,
                current.version(),
                CurrentContext.requireActor().userId(),
                newSku,
                barcode.orElse(current.barcode()),
                name.orElse(current.name()),
                weight.orElse(current.weightKg()),
                current.status())) {
            throw new ApiException(PlatformErrorCode.VERSION_CONFLICT, "The variant was modified concurrently.");
        }
        InventoryViews.Variant after = variant(id);
        audit.record(AuditEvent.builder("UPDATE", "inventory")
                .entity("product_variant", id, after.sku())
                .change("sku", current.sku(), after.sku())
                .change("barcode", current.barcode(), after.barcode())
                .change("name", current.name(), after.name())
                .change("weightKg", current.weightKg(), after.weightKg())
                .build());
        return after;
    }

    // --------------------------------------------------------------------- unit conversions

    @Transactional(readOnly = true)
    public List<InventoryViews.UomConversion> conversions(UUID productId) {
        UUID companyId = CurrentContext.requireCompany();
        products.find(companyId, productId).orElseThrow(ApiException::notFound);
        return products.conversions(companyId, productId);
    }

    /** Adds {@code 1 uom = factor × base unit} for a unit of another category (e.g. 1 BOX = 12 EA). */
    @Transactional
    public InventoryViews.UomConversion addConversion(UUID productId, UUID uomId, BigDecimal factor) {
        UUID companyId = CurrentContext.requireCompany();
        InventoryViews.Product product = lock(companyId, productId);
        Map<UUID, UomConversion.Unit> units = uoms.units(List.of(uomId, product.baseUomId()));
        UomConversion.Unit unit = units.get(uomId);
        List<FieldViolation> violations = new ArrayList<>();
        if (unit == null) {
            violations.add(FieldViolation.atPointer("/uomId", "UNKNOWN_UOM", "is not a unit of measure"));
        } else if (unit.categoryId().equals(units.get(product.baseUomId()).categoryId())) {
            violations.add(FieldViolation.atPointer(
                    "/uomId", "SAME_CATEGORY", "converts to the base unit already through its category"));
        }
        if (factor.signum() <= 0 || factor.stripTrailingZeros().scale() > 12) {
            violations.add(FieldViolation.atPointer(
                    "/factorToBase", "INVALID_VALUE", "must be positive with at most 12 decimal places"));
        }
        if (!violations.isEmpty()) {
            throw ApiException.validationFailed("The conversion is invalid.", violations);
        }
        UUID id = products.insertConversion(
                companyId,
                productId,
                uomId,
                factor,
                CurrentContext.requireActor().userId());
        audit.record(AuditEvent.builder("CREATE", "inventory")
                .entity("product_uom_conversion", id, product.code() + ":" + unit.code())
                .detail("factorToBase", factor.toPlainString())
                .build());
        return products.findConversion(companyId, productId, id).orElseThrow();
    }

    @Transactional
    public void removeConversion(UUID productId, UUID conversionId) {
        UUID companyId = CurrentContext.requireCompany();
        InventoryViews.Product product = lock(companyId, productId);
        InventoryViews.UomConversion conversion =
                products.findConversion(companyId, productId, conversionId).orElseThrow(ApiException::notFound);
        if (conversion.uomId().equals(product.purchaseUomId())
                || conversion.uomId().equals(product.salesUomId())) {
            throw new ApiException(
                    PlatformErrorCode.RESOURCE_IN_USE,
                    "The unit is the product's purchase or sales unit; change that first.");
        }
        products.deleteConversion(companyId, conversionId);
        audit.record(AuditEvent.builder("DELETE", "inventory")
                .entity("product_uom_conversion", conversionId, product.code())
                .detail("factorToBase", conversion.factorToBase().toPlainString())
                .build());
    }

    // ---------------------------------------------------------------------------- helpers

    private void validateCategory(UUID companyId, UUID categoryId, List<FieldViolation> violations) {
        var category = categories.lockForUse(companyId, categoryId);
        if (category.isEmpty()) {
            violations.add(
                    FieldViolation.atPointer("/categoryId", "UNKNOWN_CATEGORY", "is not a category of the company"));
        } else if (!category.get().active()) {
            violations.add(FieldViolation.atPointer("/categoryId", "INACTIVE", "must be an active category"));
        }
    }

    private void validateUnits(
            UUID companyId,
            @Nullable UUID productId,
            UUID baseUomId,
            @Nullable UUID purchaseUomId,
            @Nullable UUID salesUomId,
            List<FieldViolation> violations) {
        Set<UUID> ids = new HashSet<>();
        ids.add(baseUomId);
        if (purchaseUomId != null) {
            ids.add(purchaseUomId);
        }
        if (salesUomId != null) {
            ids.add(salesUomId);
        }
        Map<UUID, UomConversion.Unit> units = uoms.units(ids);
        UomConversion.Unit base = units.get(baseUomId);
        if (base == null
                || !uoms.find(baseUomId).map(InventoryViews.Uom::active).orElse(false)) {
            violations.add(FieldViolation.atPointer("/baseUomId", "UNKNOWN_UOM", "is not an active unit of measure"));
            return;
        }
        Map<UUID, BigDecimal> factors = productId == null ? Map.of() : products.conversionFactors(companyId, productId);
        checkConvertible("/purchaseUomId", purchaseUomId, units, base, factors, violations);
        checkConvertible("/salesUomId", salesUomId, units, base, factors, violations);
    }

    private static void checkConvertible(
            String pointer,
            @Nullable UUID uomId,
            Map<UUID, UomConversion.Unit> units,
            UomConversion.Unit base,
            Map<UUID, BigDecimal> factors,
            List<FieldViolation> violations) {
        if (uomId == null) {
            return;
        }
        UomConversion.Unit unit = units.get(uomId);
        if (unit == null) {
            violations.add(FieldViolation.atPointer(pointer, "UNKNOWN_UOM", "is not a unit of measure"));
        } else if (UomConversion.factor(unit, base, factors).isEmpty()) {
            violations.add(FieldViolation.atPointer(
                    pointer,
                    "UOM_NOT_CONVERTIBLE",
                    "cannot be converted to the base unit; add a product conversion first"));
        }
    }

    private void validateTaxCodes(
            UUID companyId,
            @Nullable UUID salesTaxCodeId,
            @Nullable UUID purchaseTaxCodeId,
            List<FieldViolation> violations) {
        if (salesTaxCodeId != null) {
            TaxCodeSummary tax = org.taxCode(companyId, salesTaxCodeId).orElse(null);
            if (tax == null || !tax.active() || !tax.appliesToSales()) {
                violations.add(FieldViolation.atPointer(
                        "/salesTaxCodeId", "INVALID_TAX_CODE", "must be an active tax code for sales"));
            }
        }
        if (purchaseTaxCodeId != null) {
            TaxCodeSummary tax = org.taxCode(companyId, purchaseTaxCodeId).orElse(null);
            if (tax == null || !tax.active() || !tax.appliesToPurchases()) {
                violations.add(FieldViolation.atPointer(
                        "/purchaseTaxCodeId", "INVALID_TAX_CODE", "must be an active tax code for purchases"));
            }
        }
    }

    private InventoryViews.Product lock(UUID companyId, UUID id) {
        return products.lockForChange(companyId, id).orElseThrow(ApiException::notFound);
    }

    static String normalizeSku(String sku) {
        return sku.strip().toUpperCase(Locale.ROOT);
    }

    private static @Nullable String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static InventoryCommands.Product asCommand(InventoryViews.Product p) {
        return new InventoryCommands.Product(
                p.code(),
                p.name(),
                p.description(),
                p.categoryId(),
                p.productType(),
                p.baseUomId(),
                p.purchaseUomId(),
                p.salesUomId(),
                p.purchasable(),
                p.sellable(),
                p.salesTaxCodeId(),
                p.purchaseTaxCodeId(),
                p.hasVariants());
    }
}
