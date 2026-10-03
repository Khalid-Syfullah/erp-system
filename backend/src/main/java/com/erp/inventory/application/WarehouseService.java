package com.erp.inventory.application;

import com.erp.inventory.domain.LocationType;
import com.erp.inventory.persistence.WarehouseRepository;
import com.erp.org.api.BranchSummary;
import com.erp.org.api.OrgFacade;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditPort;
import com.erp.platform.context.CurrentContext;
import com.erp.platform.context.RequestContext;
import com.erp.platform.web.ApiException;
import com.erp.platform.web.EntityTags;
import com.erp.platform.web.FieldViolation;
import com.erp.platform.web.MergePatch;
import com.erp.platform.web.PlatformErrorCode;
import com.erp.platform.web.paging.ListQuery;
import com.erp.platform.web.paging.PageResponse;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * Warehouses (owned by a branch) and their location trees (PRODUCT_SPEC.md §6.2). Every warehouse is
 * created with the locations RECEIVING, SHIPPING, STOCK (internal) and TRANSIT; STOCK is the default
 * location of documents and TRANSIT receives two-step transfers. Warehouses and locations holding
 * stock cannot be deactivated. Branch-restricted users see only their branches' warehouses.
 */
@Service
public class WarehouseService {

    public static final String STOCK_LOCATION = "STOCK";
    public static final String TRANSIT_LOCATION = "TRANSIT";
    static final Set<String> PROTECTED_LOCATIONS = Set.of(STOCK_LOCATION, TRANSIT_LOCATION);
    static final Set<String> PATCHABLE =
            Set.of("name", "addressLine1", "addressLine2", "city", "region", "postalCode", "countryCode");
    static final Set<String> LOCATION_PATCHABLE = Set.of("name", "parentId");

    private final WarehouseRepository warehouses;
    private final OrgFacade org;
    private final AuditPort audit;

    WarehouseService(WarehouseRepository warehouses, OrgFacade org, AuditPort audit) {
        this.warehouses = warehouses;
        this.org = org;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public PageResponse<InventoryViews.Warehouse> list(ListQuery query) {
        return warehouses.list(
                CurrentContext.requireCompany(), CurrentContext.require().branchScope(), query);
    }

    @Transactional(readOnly = true)
    public InventoryViews.Warehouse get(UUID id) {
        return warehouses
                .find(
                        CurrentContext.requireCompany(),
                        id,
                        CurrentContext.require().branchScope())
                .orElseThrow(ApiException::notFound);
    }

    @Transactional
    public InventoryViews.Warehouse create(InventoryCommands.Warehouse command) {
        UUID companyId = CurrentContext.requireCompany();
        RequestContext context = CurrentContext.require();
        Optional<BranchSummary> branch = context.canSeeBranch(command.branchId())
                ? org.branchForUse(companyId, command.branchId())
                : Optional.empty();
        if (branch.isEmpty()) {
            throw ApiException.validationFailed(
                    "The warehouse is invalid.",
                    List.of(FieldViolation.atPointer("/branchId", "UNKNOWN_BRANCH", "is not a branch of the company")));
        }
        if (!branch.get().active()) {
            throw ApiException.validationFailed(
                    "The warehouse is invalid.",
                    List.of(FieldViolation.atPointer("/branchId", "INACTIVE", "must be an active branch")));
        }
        UUID actor = CurrentContext.requireActor().userId();
        UUID id = warehouses.insert(companyId, command, actor);
        for (var seed : List.of(
                new InventoryCommands.Location("RECEIVING", "Receiving", null, LocationType.RECEIVING),
                new InventoryCommands.Location("SHIPPING", "Shipping", null, LocationType.SHIPPING),
                new InventoryCommands.Location(STOCK_LOCATION, "Stock", null, LocationType.INTERNAL),
                new InventoryCommands.Location(TRANSIT_LOCATION, "In transit", null, LocationType.TRANSIT))) {
            warehouses.insertLocation(companyId, id, seed, actor);
        }
        audit.record(AuditEvent.builder("CREATE", "inventory")
                .entity("warehouse", id, command.code())
                .detail("branchId", command.branchId())
                .detail("name", command.name())
                .build());
        return get(id);
    }

    @Transactional
    public InventoryViews.Warehouse patch(UUID id, @Nullable String ifMatch, JsonNode document) {
        UUID companyId = CurrentContext.requireCompany();
        InventoryViews.Warehouse current = lock(companyId, id);
        EntityTags.requireMatch(ifMatch, current.version());
        MergePatch patch = MergePatch.of(document, PATCHABLE);
        var name = patch.text("name", true, 100);
        var line1 = patch.text("addressLine1", false, 200);
        var line2 = patch.text("addressLine2", false, 200);
        var city = patch.text("city", false, 100);
        var region = patch.text("region", false, 100);
        var postal = patch.text("postalCode", false, 20);
        var country =
                patch.text("countryCode", false, 2, c -> c.matches("^[A-Z]{2}$") ? null : "must be an ISO 3166 code");
        patch.throwIfInvalid();
        InventoryCommands.Warehouse next = new InventoryCommands.Warehouse(
                current.branchId(),
                current.code(),
                name.orElse(current.name()),
                line1.orElse(current.addressLine1()),
                line2.orElse(current.addressLine2()),
                city.orElse(current.city()),
                region.orElse(current.region()),
                postal.orElse(current.postalCode()),
                country.orElse(current.countryCode()));
        update(current, next, current.active());
        InventoryViews.Warehouse after = get(id);
        audit.record(AuditEvent.builder("UPDATE", "inventory")
                .entity("warehouse", id, current.code())
                .change("name", current.name(), after.name())
                .change("addressLine1", current.addressLine1(), after.addressLine1())
                .change("addressLine2", current.addressLine2(), after.addressLine2())
                .change("city", current.city(), after.city())
                .change("region", current.region(), after.region())
                .change("postalCode", current.postalCode(), after.postalCode())
                .change("countryCode", current.countryCode(), after.countryCode())
                .build());
        return after;
    }

    @Transactional
    public InventoryViews.Warehouse setActive(UUID id, @Nullable String ifMatch, boolean active) {
        UUID companyId = CurrentContext.requireCompany();
        InventoryViews.Warehouse current = lock(companyId, id);
        EntityTags.requireMatch(ifMatch, current.version());
        if (current.active() == active) {
            throw new ApiException(
                    PlatformErrorCode.INVALID_STATE, "The warehouse is already " + (active ? "active." : "inactive."));
        }
        if (active) {
            if (!org.branchForUse(companyId, current.branchId())
                    .map(BranchSummary::active)
                    .orElse(false)) {
                throw new ApiException(PlatformErrorCode.INVALID_STATE, "Activate the warehouse's branch first.");
            }
        } else if (warehouses.hasStock(companyId, id) || warehouses.hasDraftMovements(companyId, id)) {
            throw new ApiException(
                    PlatformErrorCode.RESOURCE_IN_USE,
                    "The warehouse still holds stock or has draft movements; move the stock and finish the drafts first.");
        }
        update(current, asCommand(current), active);
        audit.record(AuditEvent.builder("STATE_CHANGE", "inventory")
                .entity("warehouse", id, current.code())
                .transition(current.active() ? "ACTIVE" : "INACTIVE", active ? "ACTIVE" : "INACTIVE")
                .build());
        return get(id);
    }

    // --------------------------------------------------------------------------- locations

    @Transactional(readOnly = true)
    public PageResponse<InventoryViews.Location> locations(UUID warehouseId, ListQuery query) {
        get(warehouseId);
        return warehouses.listLocations(CurrentContext.requireCompany(), warehouseId, query);
    }

    @Transactional(readOnly = true)
    public InventoryViews.Location location(UUID locationId) {
        InventoryViews.Location location = warehouses
                .findLocation(CurrentContext.requireCompany(), locationId)
                .orElseThrow(ApiException::notFound);
        get(location.warehouseId());
        return location;
    }

    @Transactional
    public InventoryViews.Location createLocation(UUID warehouseId, InventoryCommands.Location command) {
        UUID companyId = CurrentContext.requireCompany();
        InventoryViews.Warehouse warehouse = lock(companyId, warehouseId);
        if (!warehouse.active()) {
            throw new ApiException(PlatformErrorCode.INVALID_STATE, "The warehouse is inactive.");
        }
        if (command.type() == LocationType.TRANSIT) {
            throw ApiException.validationFailed(
                    "The location is invalid.",
                    List.of(FieldViolation.atPointer(
                            "/locationType", "NOT_ALLOWED", "every warehouse has exactly one transit location")));
        }
        if (command.parentId() != null) {
            parentViolation(companyId, warehouseId, command.parentId(), null).ifPresent(v -> {
                throw ApiException.validationFailed("The location is invalid.", List.of(v));
            });
        }
        UUID id = warehouses.insertLocation(
                companyId, warehouseId, command, CurrentContext.requireActor().userId());
        audit.record(AuditEvent.builder("CREATE", "inventory")
                .entity("location", id, warehouse.code() + "/" + command.code())
                .detail("locationType", command.type().name())
                .detail("parentId", command.parentId())
                .build());
        return location(id);
    }

    @Transactional
    public InventoryViews.Location patchLocation(UUID locationId, @Nullable String ifMatch, JsonNode document) {
        UUID companyId = CurrentContext.requireCompany();
        InventoryViews.Location current = lockLocation(companyId, locationId);
        EntityTags.requireMatch(ifMatch, current.version());
        MergePatch patch = MergePatch.of(document, LOCATION_PATCHABLE);
        var name = patch.text("name", true, 100);
        var parent = patch.uuid("parentId", false);
        patch.throwIfInvalid();
        UUID newParent = parent.orElse(current.parentId());
        if (newParent != null && !newParent.equals(current.parentId())) {
            parentViolation(companyId, current.warehouseId(), newParent, locationId)
                    .ifPresent(v -> {
                        throw ApiException.validationFailed("The location is invalid.", List.of(v));
                    });
        }
        if (!warehouses.updateLocation(
                companyId,
                locationId,
                current.version(),
                CurrentContext.requireActor().userId(),
                name.orElse(current.name()),
                newParent,
                current.active())) {
            throw new ApiException(PlatformErrorCode.VERSION_CONFLICT, "The location was modified concurrently.");
        }
        InventoryViews.Location after = location(locationId);
        audit.record(AuditEvent.builder("UPDATE", "inventory")
                .entity("location", locationId, current.code())
                .change("name", current.name(), after.name())
                .change("parentId", current.parentId(), after.parentId())
                .build());
        return after;
    }

    @Transactional
    public InventoryViews.Location setLocationActive(UUID locationId, @Nullable String ifMatch, boolean active) {
        UUID companyId = CurrentContext.requireCompany();
        InventoryViews.Location current = lockLocation(companyId, locationId);
        EntityTags.requireMatch(ifMatch, current.version());
        if (current.active() == active) {
            throw new ApiException(
                    PlatformErrorCode.INVALID_STATE, "The location is already " + (active ? "active." : "inactive."));
        }
        if (!active) {
            if (PROTECTED_LOCATIONS.contains(current.code())) {
                throw new ApiException(
                        PlatformErrorCode.INVALID_STATE,
                        "The " + current.code() + " location is required by the warehouse.");
            }
            if (warehouses.locationHasStock(companyId, locationId)
                    || warehouses.countActiveChildLocations(companyId, locationId) > 0) {
                throw new ApiException(
                        PlatformErrorCode.RESOURCE_IN_USE,
                        "The location still holds stock or has active sub-locations.");
            }
        } else if (current.parentId() != null
                && !warehouses
                        .findLocation(companyId, current.parentId())
                        .map(InventoryViews.Location::active)
                        .orElse(false)) {
            throw new ApiException(PlatformErrorCode.INVALID_STATE, "Activate the parent location first.");
        }
        if (!warehouses.updateLocation(
                companyId,
                locationId,
                current.version(),
                CurrentContext.requireActor().userId(),
                current.name(),
                current.parentId(),
                active)) {
            throw new ApiException(PlatformErrorCode.VERSION_CONFLICT, "The location was modified concurrently.");
        }
        audit.record(AuditEvent.builder("STATE_CHANGE", "inventory")
                .entity("location", locationId, current.code())
                .transition(current.active() ? "ACTIVE" : "INACTIVE", active ? "ACTIVE" : "INACTIVE")
                .build());
        return location(locationId);
    }

    private Optional<FieldViolation> parentViolation(
            UUID companyId, UUID warehouseId, UUID parentId, @Nullable UUID self) {
        var parent = warehouses.findLocation(companyId, parentId);
        if (parent.isEmpty() || !parent.get().warehouseId().equals(warehouseId)) {
            return Optional.of(
                    FieldViolation.atPointer("/parentId", "UNKNOWN_LOCATION", "is not a location of the warehouse"));
        }
        if (!parent.get().active()) {
            return Optional.of(FieldViolation.atPointer("/parentId", "INACTIVE", "must be an active location"));
        }
        if (parentId.equals(self)) {
            return Optional.of(FieldViolation.atPointer("/parentId", "CYCLE", "must not be the location itself"));
        }
        return Optional.empty();
    }

    private InventoryViews.Warehouse lock(UUID companyId, UUID id) {
        return warehouses
                .lockForChange(companyId, id, CurrentContext.require().branchScope())
                .orElseThrow(ApiException::notFound);
    }

    private InventoryViews.Location lockLocation(UUID companyId, UUID locationId) {
        InventoryViews.Location location =
                warehouses.lockLocationForChange(companyId, locationId).orElseThrow(ApiException::notFound);
        get(location.warehouseId());
        return location;
    }

    private void update(InventoryViews.Warehouse current, InventoryCommands.Warehouse next, boolean active) {
        if (!warehouses.update(
                current.companyId(),
                current.id(),
                current.version(),
                CurrentContext.requireActor().userId(),
                next,
                active)) {
            throw new ApiException(PlatformErrorCode.VERSION_CONFLICT, "The warehouse was modified concurrently.");
        }
    }

    private static InventoryCommands.Warehouse asCommand(InventoryViews.Warehouse w) {
        return new InventoryCommands.Warehouse(
                w.branchId(),
                w.code(),
                w.name(),
                w.addressLine1(),
                w.addressLine2(),
                w.city(),
                w.region(),
                w.postalCode(),
                w.countryCode());
    }
}
