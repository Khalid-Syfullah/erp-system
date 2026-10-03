package com.erp.sales.web;

import com.erp.platform.security.RequiresPermission;
import com.erp.platform.web.ApiPaths;
import com.erp.platform.web.EntityTags;
import com.erp.platform.web.paging.ListQueryParser;
import com.erp.platform.web.paging.PageResponse;
import com.erp.sales.SalesPermissions;
import com.erp.sales.application.PriceListService;
import com.erp.sales.application.SalesCommands;
import com.erp.sales.application.SalesListings;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Price lists and their items (API.md §17.7, SAL-1). */
@RestController
@RequestMapping(ApiPaths.V1 + "/companies/{companyId}/price-lists")
class PriceListController {

    private final PriceListService lists;
    private final ListQueryParser parser;

    PriceListController(PriceListService lists, ListQueryParser parser) {
        this.lists = lists;
        this.parser = parser;
    }

    record PriceListRequest(
            @NotNull @Pattern(regexp = "^[A-Z0-9_-]{1,20}$") String code,
            @NotBlank @Size(max = 100) String name,
            @NotNull @Pattern(regexp = "^[A-Z]{3}$") String currencyCode,
            @Nullable Boolean pricesIncludeTax,
            @Nullable UUID customerGroupId,
            @Nullable Boolean isDefault,
            @Nullable LocalDate validFrom,
            @Nullable LocalDate validTo,
            @Nullable Boolean isActive) {}

    record ItemRequest(
            @NotNull UUID variantId,
            @NotNull UUID uomId,

            @DecimalMin("0") @Digits(integer = 12, fraction = 6) @Nullable BigDecimal minQuantity,

            @NotNull @DecimalMin("0") @Digits(integer = 13, fraction = 6) BigDecimal unitPrice,

            @Nullable LocalDate validFrom,
            @Nullable LocalDate validTo) {}

    @RequiresPermission(SalesPermissions.PRICE_LIST_READ)
    @GetMapping
    PageResponse<SalesResponses.PriceList> list(
            @PathVariable UUID companyId, @RequestParam MultiValueMap<String, String> parameters) {
        return lists.list(parser.parse(parameters, SalesListings.PRICE_LISTS)).map(SalesResponses.PriceList::from);
    }

    @RequiresPermission(SalesPermissions.PRICE_LIST_READ)
    @GetMapping("/{listId}")
    ResponseEntity<SalesResponses.PriceList> get(@PathVariable UUID companyId, @PathVariable UUID listId) {
        return SalesResponses.PriceList.entity(lists.get(listId));
    }

    @RequiresPermission(SalesPermissions.PRICE_LIST_MANAGE)
    @PostMapping
    ResponseEntity<SalesResponses.PriceList> create(
            @PathVariable UUID companyId, @Valid @RequestBody PriceListRequest request) {
        var created = lists.create(new SalesCommands.PriceList(
                request.code(),
                request.name().strip(),
                request.currencyCode(),
                Boolean.TRUE.equals(request.pricesIncludeTax()),
                request.customerGroupId(),
                Boolean.TRUE.equals(request.isDefault()),
                request.validFrom(),
                request.validTo(),
                !Boolean.FALSE.equals(request.isActive())));
        return ResponseEntity.created(
                        URI.create(ApiPaths.V1 + "/companies/" + companyId + "/price-lists/" + created.id()))
                .eTag(EntityTags.forVersion(created.version()))
                .body(SalesResponses.PriceList.from(created));
    }

    @RequiresPermission(SalesPermissions.PRICE_LIST_MANAGE)
    @PatchMapping(path = "/{listId}", consumes = SalesResponses.MERGE_PATCH)
    ResponseEntity<SalesResponses.PriceList> patch(
            @PathVariable UUID companyId,
            @PathVariable UUID listId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch,
            @RequestBody JsonNode patch) {
        return SalesResponses.PriceList.entity(lists.patch(listId, ifMatch, patch));
    }

    @RequiresPermission(SalesPermissions.PRICE_LIST_READ)
    @GetMapping("/{listId}/items")
    PageResponse<SalesResponses.PriceListItem> items(
            @PathVariable UUID companyId,
            @PathVariable UUID listId,
            @RequestParam MultiValueMap<String, String> parameters) {
        return lists.items(listId, parser.parse(parameters, SalesListings.PRICE_LIST_ITEMS))
                .map(SalesResponses.PriceListItem::from);
    }

    @RequiresPermission(SalesPermissions.PRICE_LIST_READ)
    @GetMapping("/{listId}/items/{itemId}")
    ResponseEntity<SalesResponses.PriceListItem> item(
            @PathVariable UUID companyId, @PathVariable UUID listId, @PathVariable UUID itemId) {
        return SalesResponses.PriceListItem.entity(lists.item(listId, itemId));
    }

    @RequiresPermission(SalesPermissions.PRICE_LIST_MANAGE)
    @PostMapping("/{listId}/items")
    ResponseEntity<SalesResponses.PriceListItem> createItem(
            @PathVariable UUID companyId, @PathVariable UUID listId, @Valid @RequestBody ItemRequest request) {
        var created = lists.createItem(
                listId,
                new SalesCommands.PriceListItem(
                        request.variantId(),
                        request.uomId(),
                        request.minQuantity() == null ? BigDecimal.ZERO : request.minQuantity(),
                        request.unitPrice(),
                        request.validFrom(),
                        request.validTo()));
        return ResponseEntity.created(URI.create(
                        ApiPaths.V1 + "/companies/" + companyId + "/price-lists/" + listId + "/items/" + created.id()))
                .eTag(EntityTags.forVersion(created.version()))
                .body(SalesResponses.PriceListItem.from(created));
    }

    @RequiresPermission(SalesPermissions.PRICE_LIST_MANAGE)
    @PatchMapping(path = "/{listId}/items/{itemId}", consumes = SalesResponses.MERGE_PATCH)
    ResponseEntity<SalesResponses.PriceListItem> patchItem(
            @PathVariable UUID companyId,
            @PathVariable UUID listId,
            @PathVariable UUID itemId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch,
            @RequestBody JsonNode patch) {
        return SalesResponses.PriceListItem.entity(lists.patchItem(listId, itemId, ifMatch, patch));
    }

    @RequiresPermission(SalesPermissions.PRICE_LIST_MANAGE)
    @DeleteMapping("/{listId}/items/{itemId}")
    ResponseEntity<Void> deleteItem(
            @PathVariable UUID companyId,
            @PathVariable UUID listId,
            @PathVariable UUID itemId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch) {
        lists.deleteItem(listId, itemId, ifMatch);
        return ResponseEntity.noContent().build();
    }
}
