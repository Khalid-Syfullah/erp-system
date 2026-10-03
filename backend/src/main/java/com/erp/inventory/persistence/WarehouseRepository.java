package com.erp.inventory.persistence;

import static com.erp.db.inventory.Tables.LOCATIONS;
import static com.erp.db.inventory.Tables.STOCK_BALANCES;
import static com.erp.db.inventory.Tables.STOCK_MOVEMENTS;
import static com.erp.db.inventory.Tables.WAREHOUSES;

import com.erp.db.inventory.tables.records.LocationsRecord;
import com.erp.db.inventory.tables.records.WarehousesRecord;
import com.erp.inventory.application.InventoryCommands;
import com.erp.inventory.application.InventoryListings;
import com.erp.inventory.application.InventoryViews;
import com.erp.inventory.domain.LocationType;
import com.erp.platform.jooq.KeysetPaginator;
import com.erp.platform.jooq.ListBinding;
import com.erp.platform.web.paging.ListQuery;
import com.erp.platform.web.paging.PageResponse;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;

/**
 * Warehouses and their locations. A restricted branch scope limits warehouses to the caller's
 * branches (SECURITY.md §4.4); locations follow their warehouse.
 */
@Repository
public class WarehouseRepository {

    private static final ListBinding WAREHOUSE_BINDING = ListBinding.builder(InventoryListings.WAREHOUSES)
            .field("code", WAREHOUSES.CODE)
            .field("name", WAREHOUSES.NAME)
            .field("createdAt", WAREHOUSES.CREATED_AT)
            .field("branchId", WAREHOUSES.BRANCH_ID)
            .field("isActive", WAREHOUSES.IS_ACTIVE)
            .tiebreaker(WAREHOUSES.ID)
            .search(List.of(WAREHOUSES.CODE, WAREHOUSES.NAME))
            .build();

    private static final ListBinding LOCATION_BINDING = ListBinding.builder(InventoryListings.LOCATIONS)
            .field("code", LOCATIONS.CODE)
            .field("name", LOCATIONS.NAME)
            .field("locationType", LOCATIONS.LOCATION_TYPE)
            .field("parentId", LOCATIONS.PARENT_ID)
            .field("isActive", LOCATIONS.IS_ACTIVE)
            .tiebreaker(LOCATIONS.ID)
            .search(List.of(LOCATIONS.CODE, LOCATIONS.NAME))
            .build();

    private final DSLContext dsl;
    private final KeysetPaginator paginator;

    public WarehouseRepository(DSLContext dsl, KeysetPaginator paginator) {
        this.dsl = dsl;
        this.paginator = paginator;
    }

    // ------------------------------------------------------------------------ warehouses

    public UUID insert(UUID companyId, InventoryCommands.Warehouse c, UUID actor) {
        return dsl.insertInto(WAREHOUSES)
                .set(WAREHOUSES.COMPANY_ID, companyId)
                .set(WAREHOUSES.BRANCH_ID, c.branchId())
                .set(WAREHOUSES.CODE, c.code())
                .set(WAREHOUSES.NAME, c.name())
                .set(WAREHOUSES.ADDRESS_LINE1, c.addressLine1())
                .set(WAREHOUSES.ADDRESS_LINE2, c.addressLine2())
                .set(WAREHOUSES.CITY, c.city())
                .set(WAREHOUSES.REGION, c.region())
                .set(WAREHOUSES.POSTAL_CODE, c.postalCode())
                .set(WAREHOUSES.COUNTRY_CODE, c.countryCode())
                .set(WAREHOUSES.CREATED_BY, actor)
                .set(WAREHOUSES.UPDATED_BY, actor)
                .returning(WAREHOUSES.ID)
                .fetchOne(WAREHOUSES.ID);
    }

    public Optional<InventoryViews.Warehouse> find(UUID companyId, UUID id, @Nullable Set<UUID> branchScope) {
        return dsl.selectFrom(WAREHOUSES)
                .where(WAREHOUSES.COMPANY_ID.eq(companyId))
                .and(WAREHOUSES.ID.eq(id))
                .and(scope(branchScope))
                .fetchOptional(WarehouseRepository::toView);
    }

    public Optional<InventoryViews.Warehouse> lockForChange(UUID companyId, UUID id, @Nullable Set<UUID> branchScope) {
        return dsl.selectFrom(WAREHOUSES)
                .where(WAREHOUSES.COMPANY_ID.eq(companyId))
                .and(WAREHOUSES.ID.eq(id))
                .and(scope(branchScope))
                .forNoKeyUpdate()
                .fetchOptional(WarehouseRepository::toView);
    }

    /** Warehouses locked {@code FOR SHARE} in ID order (posting reads their status). */
    public Map<UUID, InventoryViews.Warehouse> lockForUse(UUID companyId, Collection<UUID> ids) {
        Map<UUID, InventoryViews.Warehouse> result = new LinkedHashMap<>();
        dsl.selectFrom(WAREHOUSES)
                .where(WAREHOUSES.COMPANY_ID.eq(companyId))
                .and(WAREHOUSES.ID.in(ids))
                .orderBy(WAREHOUSES.ID)
                .forShare()
                .fetch(WarehouseRepository::toView)
                .forEach(w -> result.put(w.id(), w));
        return result;
    }

    public PageResponse<InventoryViews.Warehouse> list(
            UUID companyId, @Nullable Set<UUID> branchScope, ListQuery query) {
        return paginator.fetch(
                dsl,
                WAREHOUSES,
                WAREHOUSES.COMPANY_ID.eq(companyId).and(scope(branchScope)),
                query,
                WAREHOUSE_BINDING,
                WarehouseRepository::toView);
    }

    /** IDs of the warehouses in the branch scope ({@code null} scope: all). */
    public Set<UUID> visibleIds(UUID companyId, @Nullable Set<UUID> branchScope) {
        return Set.copyOf(dsl.select(WAREHOUSES.ID)
                .from(WAREHOUSES)
                .where(WAREHOUSES.COMPANY_ID.eq(companyId))
                .and(scope(branchScope))
                .fetch(WAREHOUSES.ID));
    }

    public boolean update(
            UUID companyId, UUID id, int version, UUID actor, InventoryCommands.Warehouse c, boolean active) {
        return dsl.update(WAREHOUSES)
                        .set(WAREHOUSES.NAME, c.name())
                        .set(WAREHOUSES.ADDRESS_LINE1, c.addressLine1())
                        .set(WAREHOUSES.ADDRESS_LINE2, c.addressLine2())
                        .set(WAREHOUSES.CITY, c.city())
                        .set(WAREHOUSES.REGION, c.region())
                        .set(WAREHOUSES.POSTAL_CODE, c.postalCode())
                        .set(WAREHOUSES.COUNTRY_CODE, c.countryCode())
                        .set(WAREHOUSES.IS_ACTIVE, active)
                        .set(WAREHOUSES.UPDATED_AT, OffsetDateTime.now())
                        .set(WAREHOUSES.UPDATED_BY, actor)
                        .set(WAREHOUSES.VERSION, version + 1)
                        .where(WAREHOUSES.COMPANY_ID.eq(companyId))
                        .and(WAREHOUSES.ID.eq(id))
                        .and(WAREHOUSES.VERSION.eq(version))
                        .execute()
                == 1;
    }

    public List<String> activeCodesInBranch(UUID companyId, UUID branchId) {
        return dsl.select(WAREHOUSES.CODE)
                .from(WAREHOUSES)
                .where(WAREHOUSES.COMPANY_ID.eq(companyId))
                .and(WAREHOUSES.BRANCH_ID.eq(branchId))
                .and(WAREHOUSES.IS_ACTIVE.isTrue())
                .orderBy(WAREHOUSES.CODE)
                .limit(20)
                .fetch(WAREHOUSES.CODE);
    }

    public boolean hasStock(UUID companyId, UUID warehouseId) {
        return dsl.fetchExists(
                STOCK_BALANCES,
                STOCK_BALANCES
                        .COMPANY_ID
                        .eq(companyId)
                        .and(STOCK_BALANCES.WAREHOUSE_ID.eq(warehouseId))
                        .and(STOCK_BALANCES.ON_HAND.gt(BigDecimal.ZERO)));
    }

    public boolean hasDraftMovements(UUID companyId, UUID warehouseId) {
        return dsl.fetchExists(
                STOCK_MOVEMENTS,
                STOCK_MOVEMENTS
                        .COMPANY_ID
                        .eq(companyId)
                        .and(STOCK_MOVEMENTS
                                .WAREHOUSE_ID
                                .eq(warehouseId)
                                .or(STOCK_MOVEMENTS.DEST_WAREHOUSE_ID.eq(warehouseId)))
                        .and(STOCK_MOVEMENTS.STATUS.eq("DRAFT")));
    }

    private static Condition scope(@Nullable Set<UUID> branchScope) {
        return branchScope == null ? DSL.noCondition() : WAREHOUSES.BRANCH_ID.in(branchScope);
    }

    static InventoryViews.Warehouse toView(WarehousesRecord r) {
        return new InventoryViews.Warehouse(
                r.getId(),
                r.getCompanyId(),
                r.getBranchId(),
                r.getCode(),
                r.getName(),
                r.getAddressLine1(),
                r.getAddressLine2(),
                r.getCity(),
                r.getRegion(),
                r.getPostalCode(),
                r.getCountryCode(),
                r.getIsActive(),
                r.getCreatedAt(),
                r.getUpdatedAt(),
                r.getVersion());
    }

    // ------------------------------------------------------------------------- locations

    public UUID insertLocation(UUID companyId, UUID warehouseId, InventoryCommands.Location c, UUID actor) {
        return dsl.insertInto(LOCATIONS)
                .set(LOCATIONS.COMPANY_ID, companyId)
                .set(LOCATIONS.WAREHOUSE_ID, warehouseId)
                .set(LOCATIONS.CODE, c.code())
                .set(LOCATIONS.NAME, c.name())
                .set(LOCATIONS.PARENT_ID, c.parentId())
                .set(LOCATIONS.LOCATION_TYPE, c.type().name())
                .set(LOCATIONS.CREATED_BY, actor)
                .set(LOCATIONS.UPDATED_BY, actor)
                .returning(LOCATIONS.ID)
                .fetchOne(LOCATIONS.ID);
    }

    public Optional<InventoryViews.Location> findLocation(UUID companyId, UUID locationId) {
        return dsl.selectFrom(LOCATIONS)
                .where(LOCATIONS.COMPANY_ID.eq(companyId))
                .and(LOCATIONS.ID.eq(locationId))
                .fetchOptional(WarehouseRepository::toLocation);
    }

    public Optional<InventoryViews.Location> lockLocationForChange(UUID companyId, UUID locationId) {
        return dsl.selectFrom(LOCATIONS)
                .where(LOCATIONS.COMPANY_ID.eq(companyId))
                .and(LOCATIONS.ID.eq(locationId))
                .forNoKeyUpdate()
                .fetchOptional(WarehouseRepository::toLocation);
    }

    /** Locations locked {@code FOR SHARE} in ID order. */
    public Map<UUID, InventoryViews.Location> lockLocationsForUse(UUID companyId, Collection<UUID> ids) {
        Map<UUID, InventoryViews.Location> result = new LinkedHashMap<>();
        dsl.selectFrom(LOCATIONS)
                .where(LOCATIONS.COMPANY_ID.eq(companyId))
                .and(LOCATIONS.ID.in(ids))
                .orderBy(LOCATIONS.ID)
                .forShare()
                .fetch(WarehouseRepository::toLocation)
                .forEach(l -> result.put(l.id(), l));
        return result;
    }

    public Map<UUID, InventoryViews.Location> locations(UUID companyId, Collection<UUID> ids) {
        Map<UUID, InventoryViews.Location> result = new LinkedHashMap<>();
        if (ids.isEmpty()) {
            return result;
        }
        dsl.selectFrom(LOCATIONS)
                .where(LOCATIONS.COMPANY_ID.eq(companyId))
                .and(LOCATIONS.ID.in(ids))
                .fetch(WarehouseRepository::toLocation)
                .forEach(l -> result.put(l.id(), l));
        return result;
    }

    public PageResponse<InventoryViews.Location> listLocations(UUID companyId, UUID warehouseId, ListQuery query) {
        return paginator.fetch(
                dsl,
                LOCATIONS,
                LOCATIONS.COMPANY_ID.eq(companyId).and(LOCATIONS.WAREHOUSE_ID.eq(warehouseId)),
                query,
                LOCATION_BINDING,
                WarehouseRepository::toLocation);
    }

    /** The warehouse's active location of the given code (e.g. the seeded {@code STOCK}). */
    public Optional<InventoryViews.Location> locationByCode(UUID companyId, UUID warehouseId, String code) {
        return dsl.selectFrom(LOCATIONS)
                .where(LOCATIONS.COMPANY_ID.eq(companyId))
                .and(LOCATIONS.WAREHOUSE_ID.eq(warehouseId))
                .and(LOCATIONS.CODE.eq(code))
                .fetchOptional(WarehouseRepository::toLocation);
    }

    public Optional<InventoryViews.Location> transitLocation(UUID companyId, UUID warehouseId) {
        return dsl.selectFrom(LOCATIONS)
                .where(LOCATIONS.COMPANY_ID.eq(companyId))
                .and(LOCATIONS.WAREHOUSE_ID.eq(warehouseId))
                .and(LOCATIONS.LOCATION_TYPE.eq(LocationType.TRANSIT.name()))
                .fetchOptional(WarehouseRepository::toLocation);
    }

    public boolean updateLocation(
            UUID companyId, UUID id, int version, UUID actor, String name, @Nullable UUID parentId, boolean active) {
        return dsl.update(LOCATIONS)
                        .set(LOCATIONS.NAME, name)
                        .set(LOCATIONS.PARENT_ID, parentId)
                        .set(LOCATIONS.IS_ACTIVE, active)
                        .set(LOCATIONS.UPDATED_AT, OffsetDateTime.now())
                        .set(LOCATIONS.UPDATED_BY, actor)
                        .set(LOCATIONS.VERSION, version + 1)
                        .where(LOCATIONS.COMPANY_ID.eq(companyId))
                        .and(LOCATIONS.ID.eq(id))
                        .and(LOCATIONS.VERSION.eq(version))
                        .execute()
                == 1;
    }

    public boolean locationHasStock(UUID companyId, UUID locationId) {
        return dsl.fetchExists(
                STOCK_BALANCES,
                STOCK_BALANCES
                        .COMPANY_ID
                        .eq(companyId)
                        .and(STOCK_BALANCES.LOCATION_ID.eq(locationId))
                        .and(STOCK_BALANCES.ON_HAND.gt(BigDecimal.ZERO)));
    }

    public int countActiveChildLocations(UUID companyId, UUID locationId) {
        return dsl.fetchCount(
                LOCATIONS,
                LOCATIONS
                        .COMPANY_ID
                        .eq(companyId)
                        .and(LOCATIONS.PARENT_ID.eq(locationId))
                        .and(LOCATIONS.IS_ACTIVE.isTrue()));
    }

    static InventoryViews.Location toLocation(LocationsRecord r) {
        return new InventoryViews.Location(
                r.getId(),
                r.getCompanyId(),
                r.getWarehouseId(),
                r.getCode(),
                r.getName(),
                r.getParentId(),
                LocationType.valueOf(r.getLocationType()),
                r.getIsActive(),
                r.getCreatedAt(),
                r.getUpdatedAt(),
                r.getVersion());
    }
}
