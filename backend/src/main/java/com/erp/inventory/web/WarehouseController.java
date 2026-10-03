package com.erp.inventory.web;

import com.erp.inventory.InventoryPermissions;
import com.erp.inventory.application.InventoryCommands;
import com.erp.inventory.application.InventoryListings;
import com.erp.inventory.application.InventoryViews;
import com.erp.inventory.application.WarehouseService;
import com.erp.inventory.domain.LocationType;
import com.erp.platform.security.RequiresPermission;
import com.erp.platform.web.ApiPaths;
import com.erp.platform.web.EntityTags;
import com.erp.platform.web.paging.ListQueryParser;
import com.erp.platform.web.paging.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
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

/** Warehouses and locations (API.md §17.5). */
@RestController
class WarehouseController {

    private static final String C = ApiPaths.V1 + "/companies/{companyId}";

    private final WarehouseService warehouses;
    private final ListQueryParser parser;

    WarehouseController(WarehouseService warehouses, ListQueryParser parser) {
        this.warehouses = warehouses;
        this.parser = parser;
    }

    record WarehouseRequest(
            @NotNull UUID branchId,

            @NotBlank @Pattern(regexp = "^[A-Z0-9_-]{1,20}$") String code,

            @NotBlank @Size(max = 100) String name,
            @Size(max = 200) @Nullable String addressLine1,
            @Size(max = 200) @Nullable String addressLine2,
            @Size(max = 100) @Nullable String city,
            @Size(max = 100) @Nullable String region,
            @Size(max = 20) @Nullable String postalCode,
            @Pattern(regexp = "^[A-Z]{2}$") @Nullable String countryCode) {}

    record LocationRequest(
            @NotBlank @Pattern(regexp = "^[A-Z0-9_-]{1,20}$") String code,

            @NotBlank @Size(max = 100) String name,
            @Nullable UUID parentId,

            @NotBlank @Pattern(regexp = "^(INTERNAL|RECEIVING|SHIPPING|QUARANTINE|TRANSIT)$")
            String locationType) {}

    @RequiresPermission(InventoryPermissions.WAREHOUSE_READ)
    @GetMapping(C + "/warehouses")
    PageResponse<InventoryResponses.Warehouse> list(
            @PathVariable UUID companyId, @RequestParam MultiValueMap<String, String> parameters) {
        return warehouses
                .list(parser.parse(parameters, InventoryListings.WAREHOUSES))
                .map(InventoryResponses.Warehouse::from);
    }

    @RequiresPermission(InventoryPermissions.WAREHOUSE_READ)
    @GetMapping(C + "/warehouses/{warehouseId}")
    ResponseEntity<InventoryResponses.Warehouse> get(@PathVariable UUID companyId, @PathVariable UUID warehouseId) {
        return warehouse(warehouses.get(warehouseId));
    }

    @RequiresPermission(InventoryPermissions.WAREHOUSE_MANAGE)
    @PostMapping(C + "/warehouses")
    ResponseEntity<InventoryResponses.Warehouse> create(
            @PathVariable UUID companyId, @Valid @RequestBody WarehouseRequest request) {
        InventoryViews.Warehouse created = warehouses.create(new InventoryCommands.Warehouse(
                request.branchId(),
                request.code(),
                request.name().strip(),
                request.addressLine1(),
                request.addressLine2(),
                request.city(),
                request.region(),
                request.postalCode(),
                request.countryCode()));
        return ResponseEntity.created(
                        URI.create(ApiPaths.V1 + "/companies/" + companyId + "/warehouses/" + created.id()))
                .eTag(EntityTags.forVersion(created.version()))
                .body(InventoryResponses.Warehouse.from(created));
    }

    @RequiresPermission(InventoryPermissions.WAREHOUSE_MANAGE)
    @PatchMapping(path = C + "/warehouses/{warehouseId}", consumes = InventoryResponses.MERGE_PATCH)
    ResponseEntity<InventoryResponses.Warehouse> patch(
            @PathVariable UUID companyId,
            @PathVariable UUID warehouseId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch,
            @RequestBody JsonNode patch) {
        return warehouse(warehouses.patch(warehouseId, ifMatch, patch));
    }

    @RequiresPermission(InventoryPermissions.WAREHOUSE_MANAGE)
    @PostMapping(C + "/warehouses/{warehouseId}/deactivate")
    ResponseEntity<InventoryResponses.Warehouse> deactivate(
            @PathVariable UUID companyId,
            @PathVariable UUID warehouseId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch) {
        return warehouse(warehouses.setActive(warehouseId, ifMatch, false));
    }

    @RequiresPermission(InventoryPermissions.WAREHOUSE_MANAGE)
    @PostMapping(C + "/warehouses/{warehouseId}/activate")
    ResponseEntity<InventoryResponses.Warehouse> activate(
            @PathVariable UUID companyId,
            @PathVariable UUID warehouseId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch) {
        return warehouse(warehouses.setActive(warehouseId, ifMatch, true));
    }

    @RequiresPermission(InventoryPermissions.WAREHOUSE_READ)
    @GetMapping(C + "/warehouses/{warehouseId}/locations")
    PageResponse<InventoryResponses.Location> locations(
            @PathVariable UUID companyId,
            @PathVariable UUID warehouseId,
            @RequestParam MultiValueMap<String, String> parameters) {
        return warehouses
                .locations(warehouseId, parser.parse(parameters, InventoryListings.LOCATIONS))
                .map(InventoryResponses.Location::from);
    }

    @RequiresPermission(InventoryPermissions.WAREHOUSE_MANAGE)
    @PostMapping(C + "/warehouses/{warehouseId}/locations")
    ResponseEntity<InventoryResponses.Location> createLocation(
            @PathVariable UUID companyId, @PathVariable UUID warehouseId, @Valid @RequestBody LocationRequest request) {
        InventoryViews.Location created = warehouses.createLocation(
                warehouseId,
                new InventoryCommands.Location(
                        request.code(),
                        request.name().strip(),
                        request.parentId(),
                        LocationType.valueOf(request.locationType())));
        return ResponseEntity.created(
                        URI.create(ApiPaths.V1 + "/companies/" + companyId + "/locations/" + created.id()))
                .eTag(EntityTags.forVersion(created.version()))
                .body(InventoryResponses.Location.from(created));
    }

    @RequiresPermission(InventoryPermissions.WAREHOUSE_READ)
    @GetMapping(C + "/locations/{locationId}")
    ResponseEntity<InventoryResponses.Location> location(@PathVariable UUID companyId, @PathVariable UUID locationId) {
        return location(warehouses.location(locationId));
    }

    @RequiresPermission(InventoryPermissions.WAREHOUSE_MANAGE)
    @PatchMapping(path = C + "/locations/{locationId}", consumes = InventoryResponses.MERGE_PATCH)
    ResponseEntity<InventoryResponses.Location> patchLocation(
            @PathVariable UUID companyId,
            @PathVariable UUID locationId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch,
            @RequestBody JsonNode patch) {
        return location(warehouses.patchLocation(locationId, ifMatch, patch));
    }

    @RequiresPermission(InventoryPermissions.WAREHOUSE_MANAGE)
    @PostMapping(C + "/locations/{locationId}/deactivate")
    ResponseEntity<InventoryResponses.Location> deactivateLocation(
            @PathVariable UUID companyId,
            @PathVariable UUID locationId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch) {
        return location(warehouses.setLocationActive(locationId, ifMatch, false));
    }

    @RequiresPermission(InventoryPermissions.WAREHOUSE_MANAGE)
    @PostMapping(C + "/locations/{locationId}/activate")
    ResponseEntity<InventoryResponses.Location> activateLocation(
            @PathVariable UUID companyId,
            @PathVariable UUID locationId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch) {
        return location(warehouses.setLocationActive(locationId, ifMatch, true));
    }

    private static ResponseEntity<InventoryResponses.Warehouse> warehouse(InventoryViews.Warehouse w) {
        return ResponseEntity.ok().eTag(EntityTags.forVersion(w.version())).body(InventoryResponses.Warehouse.from(w));
    }

    private static ResponseEntity<InventoryResponses.Location> location(InventoryViews.Location l) {
        return ResponseEntity.ok().eTag(EntityTags.forVersion(l.version())).body(InventoryResponses.Location.from(l));
    }
}
