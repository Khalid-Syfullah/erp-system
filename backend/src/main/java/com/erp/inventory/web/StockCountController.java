package com.erp.inventory.web;

import com.erp.inventory.InventoryPermissions;
import com.erp.inventory.application.InventoryListings;
import com.erp.inventory.application.StockCountService;
import com.erp.platform.idempotency.IdempotencyExecutor;
import com.erp.platform.security.RequiresPermission;
import com.erp.platform.web.ApiPaths;
import com.erp.platform.web.EntityTags;
import com.erp.platform.web.paging.ListQueryParser;
import com.erp.platform.web.paging.PageResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Physical stock counts (API.md §17.5, INV-8). */
@RestController
@RequestMapping(ApiPaths.V1 + "/companies/{companyId}/stock-counts")
class StockCountController {

    private final StockCountService counts;
    private final IdempotencyExecutor idempotency;
    private final ListQueryParser parser;

    StockCountController(StockCountService counts, IdempotencyExecutor idempotency, ListQueryParser parser) {
        this.counts = counts;
        this.idempotency = idempotency;
        this.parser = parser;
    }

    record CreateRequest(
            @NotNull UUID warehouseId,
            @NotNull LocalDate countDate,
            @Nullable UUID reasonCodeId,
            @Size(max = 2000) @Nullable String notes) {}

    record LineRequest(
            @NotNull UUID variantId,
            @NotNull UUID locationId,

            @DecimalMin("0") @Digits(integer = 12, fraction = 6) @Nullable BigDecimal countedQuantityBase) {}

    record LinesRequest(@NotNull @Size(max = 5000) List<@Valid @NotNull LineRequest> lines) {}

    record PostRequest(@Nullable UUID reasonCodeId) {}

    @RequiresPermission(InventoryPermissions.COUNT_MANAGE)
    @GetMapping
    PageResponse<InventoryResponses.Count> list(
            @PathVariable UUID companyId, @RequestParam MultiValueMap<String, String> parameters) {
        return counts.list(parser.parse(parameters, InventoryListings.COUNTS))
                .map(c -> InventoryResponses.Count.from(c, null));
    }

    @RequiresPermission(InventoryPermissions.COUNT_MANAGE)
    @GetMapping("/{countId}")
    ResponseEntity<InventoryResponses.Count> get(@PathVariable UUID companyId, @PathVariable UUID countId) {
        return InventoryResponses.Count.entity(counts.get(countId));
    }

    @RequiresPermission(InventoryPermissions.COUNT_MANAGE)
    @PostMapping
    ResponseEntity<InventoryResponses.Count> create(
            @PathVariable UUID companyId, @Valid @RequestBody CreateRequest request) {
        var created =
                counts.create(request.warehouseId(), request.countDate(), request.reasonCodeId(), request.notes());
        return ResponseEntity.created(URI.create(ApiPaths.V1 + "/companies/" + companyId + "/stock-counts/"
                        + created.count().id()))
                .eTag(EntityTags.forVersion(created.count().version()))
                .body(InventoryResponses.Count.from(created.count(), created.lines()));
    }

    @RequiresPermission(InventoryPermissions.COUNT_MANAGE)
    @PatchMapping(path = "/{countId}", consumes = InventoryResponses.MERGE_PATCH)
    ResponseEntity<InventoryResponses.Count> patch(
            @PathVariable UUID companyId,
            @PathVariable UUID countId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch,
            @RequestBody JsonNode patch) {
        return InventoryResponses.Count.entity(counts.patch(countId, ifMatch, patch));
    }

    @RequiresPermission(InventoryPermissions.COUNT_MANAGE)
    @PostMapping("/{countId}/start")
    ResponseEntity<InventoryResponses.Count> start(
            @PathVariable UUID companyId,
            @PathVariable UUID countId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch) {
        return InventoryResponses.Count.entity(counts.start(countId, ifMatch));
    }

    /** Enters counted quantities (base units); items found elsewhere add lines. */
    @RequiresPermission(InventoryPermissions.COUNT_MANAGE)
    @PutMapping("/{countId}/lines")
    ResponseEntity<InventoryResponses.Count> lines(
            @PathVariable UUID companyId,
            @PathVariable UUID countId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch,
            @Valid @RequestBody LinesRequest request) {
        return InventoryResponses.Count.entity(counts.enter(
                countId,
                ifMatch,
                request.lines().stream()
                        .map(l -> new StockCountService.CountedLine(
                                l.variantId(), l.locationId(), l.countedQuantityBase()))
                        .toList()));
    }

    @RequiresPermission(InventoryPermissions.COUNT_MANAGE)
    @PostMapping("/{countId}/complete")
    ResponseEntity<InventoryResponses.Count> complete(
            @PathVariable UUID companyId,
            @PathVariable UUID countId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch) {
        return InventoryResponses.Count.entity(counts.complete(countId, ifMatch));
    }

    @RequiresPermission(InventoryPermissions.COUNT_POST)
    @PostMapping("/{countId}/post")
    ResponseEntity<?> post(
            @PathVariable UUID companyId,
            @PathVariable UUID countId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch,
            @RequestBody(required = false) @Nullable PostRequest request,
            HttpServletRequest http) {
        return idempotency.execute(
                http,
                request,
                true,
                () -> InventoryResponses.Count.entity(
                        counts.post(countId, ifMatch, request == null ? null : request.reasonCodeId())));
    }

    @RequiresPermission(InventoryPermissions.COUNT_MANAGE)
    @PostMapping("/{countId}/cancel")
    ResponseEntity<InventoryResponses.Count> cancel(
            @PathVariable UUID companyId,
            @PathVariable UUID countId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch) {
        return InventoryResponses.Count.entity(counts.cancel(countId, ifMatch));
    }
}
