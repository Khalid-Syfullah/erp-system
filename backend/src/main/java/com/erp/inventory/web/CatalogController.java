package com.erp.inventory.web;

import com.erp.inventory.InventoryPermissions;
import com.erp.inventory.application.CatalogService;
import com.erp.inventory.application.InventoryCommands;
import com.erp.inventory.application.InventoryListings;
import com.erp.inventory.application.InventoryReferenceService;
import com.erp.inventory.application.InventoryViews;
import com.erp.platform.security.AuthenticatedEndpoint;
import com.erp.platform.security.RequiresPermission;
import com.erp.platform.web.ApiPaths;
import com.erp.platform.web.EntityTags;
import com.erp.platform.web.paging.ListQueryParser;
import com.erp.platform.web.paging.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Units of measure (reference data), product categories and attributes (API.md §17.3, §17.5). */
@RestController
class CatalogController {

    private static final String C = ApiPaths.V1 + "/companies/{companyId}";

    private final CatalogService catalog;
    private final InventoryReferenceService reference;
    private final ListQueryParser parser;

    CatalogController(CatalogService catalog, InventoryReferenceService reference, ListQueryParser parser) {
        this.catalog = catalog;
        this.reference = reference;
        this.parser = parser;
    }

    record CategoryRequest(
            @NotBlank @Pattern(regexp = "^[A-Z0-9_-]{1,20}$") String code,

            @NotBlank @Size(max = 100) String name,
            @Nullable UUID parentId) {}

    record ValueRequest(
            @NotBlank @Pattern(regexp = "^[A-Z0-9_-]{1,20}$") String code,

            @NotBlank @Size(max = 100) String name,
            @Min(0) @Max(100_000) @Nullable Integer sortOrder) {}

    record AttributeRequest(
            @NotBlank @Pattern(regexp = "^[A-Z0-9_-]{1,20}$") String code,

            @NotBlank @Size(max = 100) String name,
            @Size(max = 200) @Nullable List<@Valid ValueRequest> values) {}

    // ------------------------------------------------------------------------------- units

    @AuthenticatedEndpoint
    @GetMapping(ApiPaths.V1 + "/reference/uoms")
    InventoryResponses.ListResponse<InventoryViews.Uom> uoms() {
        return new InventoryResponses.ListResponse<>(reference.uoms());
    }

    @AuthenticatedEndpoint
    @GetMapping(ApiPaths.V1 + "/reference/uom-categories")
    InventoryResponses.ListResponse<InventoryViews.UomCategory> uomCategories() {
        return new InventoryResponses.ListResponse<>(reference.uomCategories());
    }

    // -------------------------------------------------------------------------- categories

    @RequiresPermission(InventoryPermissions.PRODUCT_READ)
    @GetMapping(C + "/product-categories")
    PageResponse<InventoryResponses.Category> categories(
            @PathVariable UUID companyId, @RequestParam MultiValueMap<String, String> parameters) {
        return catalog.categories(parser.parse(parameters, InventoryListings.CATEGORIES))
                .map(InventoryResponses.Category::from);
    }

    @RequiresPermission(InventoryPermissions.PRODUCT_READ)
    @GetMapping(C + "/product-categories/{categoryId}")
    ResponseEntity<InventoryResponses.Category> category(@PathVariable UUID companyId, @PathVariable UUID categoryId) {
        return category(catalog.category(categoryId));
    }

    @RequiresPermission(InventoryPermissions.PRODUCT_MANAGE)
    @PostMapping(C + "/product-categories")
    ResponseEntity<InventoryResponses.Category> createCategory(
            @PathVariable UUID companyId, @Valid @RequestBody CategoryRequest request) {
        InventoryViews.Category created = catalog.createCategory(
                new InventoryCommands.Category(request.code(), request.name().strip(), request.parentId(), true));
        return ResponseEntity.created(
                        URI.create(ApiPaths.V1 + "/companies/" + companyId + "/product-categories/" + created.id()))
                .eTag(EntityTags.forVersion(created.version()))
                .body(InventoryResponses.Category.from(created));
    }

    @RequiresPermission(InventoryPermissions.PRODUCT_MANAGE)
    @PatchMapping(path = C + "/product-categories/{categoryId}", consumes = InventoryResponses.MERGE_PATCH)
    ResponseEntity<InventoryResponses.Category> patchCategory(
            @PathVariable UUID companyId,
            @PathVariable UUID categoryId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch,
            @RequestBody JsonNode patch) {
        return category(catalog.patchCategory(categoryId, ifMatch, patch));
    }

    @RequiresPermission(InventoryPermissions.PRODUCT_MANAGE)
    @PostMapping(C + "/product-categories/{categoryId}/deactivate")
    ResponseEntity<InventoryResponses.Category> deactivateCategory(
            @PathVariable UUID companyId,
            @PathVariable UUID categoryId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch) {
        return category(catalog.setCategoryActive(categoryId, ifMatch, false));
    }

    @RequiresPermission(InventoryPermissions.PRODUCT_MANAGE)
    @PostMapping(C + "/product-categories/{categoryId}/activate")
    ResponseEntity<InventoryResponses.Category> activateCategory(
            @PathVariable UUID companyId,
            @PathVariable UUID categoryId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch) {
        return category(catalog.setCategoryActive(categoryId, ifMatch, true));
    }

    // -------------------------------------------------------------------------- attributes

    @RequiresPermission(InventoryPermissions.PRODUCT_READ)
    @GetMapping(C + "/product-attributes")
    PageResponse<InventoryResponses.Attribute> attributes(
            @PathVariable UUID companyId, @RequestParam MultiValueMap<String, String> parameters) {
        return catalog.attributes(parser.parse(parameters, InventoryListings.ATTRIBUTES))
                .map(InventoryResponses.Attribute::from);
    }

    @RequiresPermission(InventoryPermissions.PRODUCT_READ)
    @GetMapping(C + "/product-attributes/{attributeId}")
    InventoryResponses.Attribute attribute(@PathVariable UUID companyId, @PathVariable UUID attributeId) {
        return InventoryResponses.Attribute.from(catalog.attribute(attributeId));
    }

    @RequiresPermission(InventoryPermissions.PRODUCT_MANAGE)
    @PostMapping(C + "/product-attributes")
    ResponseEntity<InventoryResponses.Attribute> createAttribute(
            @PathVariable UUID companyId, @Valid @RequestBody AttributeRequest request) {
        InventoryViews.Attribute created = catalog.createAttribute(
                request.code(),
                request.name().strip(),
                request.values() == null
                        ? List.of()
                        : request.values().stream()
                                .map(v -> new CatalogService.ValueInput(
                                        v.code(), v.name().strip(), v.sortOrder() == null ? 0 : v.sortOrder()))
                                .toList());
        return ResponseEntity.created(
                        URI.create(ApiPaths.V1 + "/companies/" + companyId + "/product-attributes/" + created.id()))
                .body(InventoryResponses.Attribute.from(created));
    }

    @RequiresPermission(InventoryPermissions.PRODUCT_MANAGE)
    @PostMapping(C + "/product-attributes/{attributeId}/values")
    ResponseEntity<InventoryResponses.Attribute> addValue(
            @PathVariable UUID companyId, @PathVariable UUID attributeId, @Valid @RequestBody ValueRequest request) {
        return ResponseEntity.status(201)
                .body(InventoryResponses.Attribute.from(catalog.addValue(
                        attributeId,
                        new CatalogService.ValueInput(
                                request.code(),
                                request.name().strip(),
                                request.sortOrder() == null ? 0 : request.sortOrder()))));
    }

    private static ResponseEntity<InventoryResponses.Category> category(InventoryViews.Category category) {
        return ResponseEntity.ok()
                .eTag(EntityTags.forVersion(category.version()))
                .body(InventoryResponses.Category.from(category));
    }
}
