package com.erp.inventory.web;

import com.erp.inventory.InventoryPermissions;
import com.erp.inventory.application.InventoryCommands;
import com.erp.inventory.application.InventoryListings;
import com.erp.inventory.application.InventoryViews;
import com.erp.inventory.application.ProductService;
import com.erp.platform.security.RequiresPermission;
import com.erp.platform.web.ApiPaths;
import com.erp.platform.web.EntityTags;
import com.erp.platform.web.paging.ListQueryParser;
import com.erp.platform.web.paging.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.net.URI;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Products, variants (SKUs) and product unit conversions (API.md §17.5). */
@RestController
class ProductController {

    private static final String C = ApiPaths.V1 + "/companies/{companyId}";

    private final ProductService products;
    private final ListQueryParser parser;

    ProductController(ProductService products, ListQueryParser parser) {
        this.products = products;
        this.parser = parser;
    }

    record ProductRequest(
            @NotBlank @Pattern(regexp = "^[A-Z0-9][A-Z0-9._/-]{0,39}$") String code,

            @NotBlank @Size(max = 200) String name,
            @Size(max = 4000) @Nullable String description,
            @NotNull UUID categoryId,

            @NotBlank @Pattern(regexp = "^(STOCKABLE|CONSUMABLE|SERVICE)$")
            String productType,

            @NotNull UUID baseUomId,
            @Nullable UUID purchaseUomId,
            @Nullable UUID salesUomId,
            @Nullable Boolean isPurchasable,
            @Nullable Boolean isSellable,
            @Nullable UUID salesTaxCodeId,
            @Nullable UUID purchaseTaxCodeId,
            @Nullable Boolean hasVariants,
            @Size(max = 40) @Nullable String sku,
            @Size(max = 48) @Nullable String barcode) {}

    record VariantRequest(
            @NotBlank @Size(max = 40) String sku,
            @Size(max = 48) @Nullable String barcode,
            @Size(max = 300) @Nullable String name,

            @DecimalMin("0") @DecimalMax("99999999.9999") @Nullable BigDecimal weightKg,

            @NotNull @Size(min = 1, max = 20) Map<UUID, UUID> attributes) {}

    record ConversionRequest(
            @NotNull UUID uomId,

            @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal factorToBase) {}

    // ---------------------------------------------------------------------------- products

    @RequiresPermission(InventoryPermissions.PRODUCT_READ)
    @GetMapping(C + "/products")
    PageResponse<InventoryResponses.Product> list(
            @PathVariable UUID companyId, @RequestParam MultiValueMap<String, String> parameters) {
        return products.list(parser.parse(parameters, InventoryListings.PRODUCTS))
                .map(p -> InventoryResponses.Product.from(p, null));
    }

    @RequiresPermission(InventoryPermissions.PRODUCT_READ)
    @GetMapping(C + "/products/{productId}")
    ResponseEntity<InventoryResponses.Product> get(@PathVariable UUID companyId, @PathVariable UUID productId) {
        return product(products.get(productId));
    }

    @RequiresPermission(InventoryPermissions.PRODUCT_MANAGE)
    @PostMapping(C + "/products")
    ResponseEntity<InventoryResponses.Product> create(
            @PathVariable UUID companyId, @Valid @RequestBody ProductRequest request) {
        ProductService.ProductDetail created = products.create(
                new InventoryCommands.Product(
                        request.code(),
                        request.name().strip(),
                        request.description(),
                        request.categoryId(),
                        request.productType(),
                        request.baseUomId(),
                        request.purchaseUomId(),
                        request.salesUomId(),
                        !Boolean.FALSE.equals(request.isPurchasable()),
                        !Boolean.FALSE.equals(request.isSellable()),
                        request.salesTaxCodeId(),
                        request.purchaseTaxCodeId(),
                        Boolean.TRUE.equals(request.hasVariants())),
                new ProductService.DefaultVariant(request.sku(), request.barcode()));
        return ResponseEntity.created(URI.create(ApiPaths.V1 + "/companies/" + companyId + "/products/"
                        + created.product().id()))
                .eTag(EntityTags.forVersion(created.product().version()))
                .body(InventoryResponses.Product.from(created.product(), created.variants()));
    }

    @RequiresPermission(InventoryPermissions.PRODUCT_MANAGE)
    @PatchMapping(path = C + "/products/{productId}", consumes = InventoryResponses.MERGE_PATCH)
    ResponseEntity<InventoryResponses.Product> patch(
            @PathVariable UUID companyId,
            @PathVariable UUID productId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch,
            @RequestBody JsonNode patch) {
        return product(products.patch(productId, ifMatch, patch));
    }

    @RequiresPermission(InventoryPermissions.PRODUCT_MANAGE)
    @PostMapping(C + "/products/{productId}/archive")
    ResponseEntity<InventoryResponses.Product> archive(
            @PathVariable UUID companyId,
            @PathVariable UUID productId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch) {
        return product(products.setArchived(productId, ifMatch, true));
    }

    @RequiresPermission(InventoryPermissions.PRODUCT_MANAGE)
    @PostMapping(C + "/products/{productId}/unarchive")
    ResponseEntity<InventoryResponses.Product> unarchive(
            @PathVariable UUID companyId,
            @PathVariable UUID productId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch) {
        return product(products.setArchived(productId, ifMatch, false));
    }

    // ---------------------------------------------------------------------------- variants

    @RequiresPermission(InventoryPermissions.PRODUCT_READ)
    @GetMapping(C + "/products/{productId}/variants")
    PageResponse<InventoryResponses.Variant> productVariants(
            @PathVariable UUID companyId,
            @PathVariable UUID productId,
            @RequestParam MultiValueMap<String, String> parameters) {
        return products.variants(productId, parser.parse(parameters, InventoryListings.VARIANTS))
                .map(InventoryResponses.Variant::from);
    }

    /** SKU, barcode or name lookup across products ({@code q}). */
    @RequiresPermission(InventoryPermissions.PRODUCT_READ)
    @GetMapping(C + "/variants")
    PageResponse<InventoryResponses.Variant> variants(
            @PathVariable UUID companyId, @RequestParam MultiValueMap<String, String> parameters) {
        return products.variants(null, parser.parse(parameters, InventoryListings.VARIANTS))
                .map(InventoryResponses.Variant::from);
    }

    @RequiresPermission(InventoryPermissions.PRODUCT_READ)
    @GetMapping(C + "/variants/{variantId}")
    ResponseEntity<InventoryResponses.Variant> variant(@PathVariable UUID companyId, @PathVariable UUID variantId) {
        return variant(products.variant(variantId));
    }

    @RequiresPermission(InventoryPermissions.PRODUCT_MANAGE)
    @PostMapping(C + "/products/{productId}/variants")
    ResponseEntity<InventoryResponses.Variant> createVariant(
            @PathVariable UUID companyId, @PathVariable UUID productId, @Valid @RequestBody VariantRequest request) {
        InventoryViews.Variant created = products.createVariant(
                productId,
                new InventoryCommands.Variant(
                        request.sku(), request.barcode(), request.name(), request.weightKg(), request.attributes()));
        return ResponseEntity.created(URI.create(ApiPaths.V1 + "/companies/" + companyId + "/variants/" + created.id()))
                .eTag(EntityTags.forVersion(created.version()))
                .body(InventoryResponses.Variant.from(created));
    }

    @RequiresPermission(InventoryPermissions.PRODUCT_MANAGE)
    @PatchMapping(path = C + "/variants/{variantId}", consumes = InventoryResponses.MERGE_PATCH)
    ResponseEntity<InventoryResponses.Variant> patchVariant(
            @PathVariable UUID companyId,
            @PathVariable UUID variantId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch,
            @RequestBody JsonNode patch) {
        return variant(products.patchVariant(variantId, ifMatch, patch));
    }

    // -------------------------------------------------------------------- unit conversions

    @RequiresPermission(InventoryPermissions.PRODUCT_READ)
    @GetMapping(C + "/products/{productId}/uom-conversions")
    InventoryResponses.ListResponse<InventoryResponses.Conversion> conversions(
            @PathVariable UUID companyId, @PathVariable UUID productId) {
        return new InventoryResponses.ListResponse<>(products.conversions(productId).stream()
                .map(InventoryResponses.Conversion::from)
                .toList());
    }

    @RequiresPermission(InventoryPermissions.PRODUCT_MANAGE)
    @PostMapping(C + "/products/{productId}/uom-conversions")
    ResponseEntity<InventoryResponses.Conversion> addConversion(
            @PathVariable UUID companyId, @PathVariable UUID productId, @Valid @RequestBody ConversionRequest request) {
        return ResponseEntity.status(201)
                .body(InventoryResponses.Conversion.from(
                        products.addConversion(productId, request.uomId(), request.factorToBase())));
    }

    @RequiresPermission(InventoryPermissions.PRODUCT_MANAGE)
    @DeleteMapping(C + "/products/{productId}/uom-conversions/{conversionId}")
    ResponseEntity<Void> removeConversion(
            @PathVariable UUID companyId, @PathVariable UUID productId, @PathVariable UUID conversionId) {
        products.removeConversion(productId, conversionId);
        return ResponseEntity.noContent().build();
    }

    private static ResponseEntity<InventoryResponses.Product> product(ProductService.ProductDetail detail) {
        return ResponseEntity.ok()
                .eTag(EntityTags.forVersion(detail.product().version()))
                .body(InventoryResponses.Product.from(detail.product(), detail.variants()));
    }

    private static ResponseEntity<InventoryResponses.Variant> variant(InventoryViews.Variant variant) {
        return ResponseEntity.ok()
                .eTag(EntityTags.forVersion(variant.version()))
                .body(InventoryResponses.Variant.from(variant));
    }
}
