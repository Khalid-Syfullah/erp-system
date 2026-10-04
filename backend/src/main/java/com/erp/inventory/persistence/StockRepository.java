package com.erp.inventory.persistence;

import static com.erp.db.inventory.Tables.INVENTORY_TRANSACTIONS;
import static com.erp.db.inventory.Tables.ITEM_VALUATIONS;
import static com.erp.db.inventory.Tables.STOCK_BALANCES;
import static com.erp.db.inventory.Tables.STOCK_RESERVATIONS;
import static com.erp.db.inventory.Tables.WAREHOUSE_STOCK;

import com.erp.db.inventory.tables.records.InventoryTransactionsRecord;
import com.erp.inventory.application.InventoryListings;
import com.erp.inventory.application.InventoryViews;
import com.erp.inventory.domain.Valuation;
import com.erp.platform.jooq.KeysetPaginator;
import com.erp.platform.jooq.ListBinding;
import com.erp.platform.web.paging.ListQuery;
import com.erp.platform.web.paging.PageResponse;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Row2;
import org.jooq.impl.DSL;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;

/**
 * The stock ledger and the balances derived from it (DATABASE.md §5.5). Balance rows are created on
 * first use and locked {@code FOR UPDATE} in key order — warehouse stock, then location balances,
 * then valuations (ARCHITECTURE.md §6.2, DATABASE.md §9) — so concurrent postings cannot deadlock on
 * them and never overwrite each other.
 */
@Repository
public class StockRepository {

    /** A (variant, warehouse) key. */
    public record WarehouseKey(UUID variantId, UUID warehouseId) {}

    /** A (variant, location) key with the location's warehouse. */
    public record LocationKey(UUID variantId, UUID locationId, UUID warehouseId) {}

    public record WarehouseStock(BigDecimal onHand, BigDecimal reserved) {}

    /** A ledger row to append. */
    public record LedgerRow(
            UUID movementId,
            UUID movementLineId,
            String movementType,
            LocalDate transactionDate,
            UUID variantId,
            UUID warehouseId,
            UUID locationId,
            BigDecimal quantityBase,
            BigDecimal unitCostBase,
            BigDecimal valueBase) {}

    private static final Comparator<WarehouseKey> WAREHOUSE_ORDER =
            Comparator.comparing(WarehouseKey::variantId).thenComparing(WarehouseKey::warehouseId);
    private static final Comparator<LocationKey> LOCATION_ORDER =
            Comparator.comparing(LocationKey::variantId).thenComparing(LocationKey::locationId);

    private static final ListBinding LEVEL_BINDING = ListBinding.builder(InventoryListings.STOCK_LEVELS)
            .field("variantId", WAREHOUSE_STOCK.VARIANT_ID)
            .field("warehouseId", WAREHOUSE_STOCK.WAREHOUSE_ID)
            .tiebreaker(WAREHOUSE_STOCK.WAREHOUSE_ID)
            .build();

    private static final ListBinding BALANCE_BINDING = ListBinding.builder(InventoryListings.STOCK_BY_LOCATION)
            .field("variantId", STOCK_BALANCES.VARIANT_ID)
            .field("warehouseId", STOCK_BALANCES.WAREHOUSE_ID)
            .field("locationId", STOCK_BALANCES.LOCATION_ID)
            .tiebreaker(STOCK_BALANCES.LOCATION_ID)
            .build();

    private static final ListBinding LEDGER_BINDING = ListBinding.builder(InventoryListings.LEDGER)
            .field("createdAt", INVENTORY_TRANSACTIONS.CREATED_AT)
            .field("transactionDate", INVENTORY_TRANSACTIONS.TRANSACTION_DATE)
            .field("variantId", INVENTORY_TRANSACTIONS.VARIANT_ID)
            .field("warehouseId", INVENTORY_TRANSACTIONS.WAREHOUSE_ID)
            .field("locationId", INVENTORY_TRANSACTIONS.LOCATION_ID)
            .field("movementId", INVENTORY_TRANSACTIONS.MOVEMENT_ID)
            .field("movementType", INVENTORY_TRANSACTIONS.MOVEMENT_TYPE)
            .tiebreaker(INVENTORY_TRANSACTIONS.ID)
            .build();

    private final DSLContext dsl;
    private final KeysetPaginator paginator;

    public StockRepository(DSLContext dsl, KeysetPaginator paginator) {
        this.dsl = dsl;
        this.paginator = paginator;
    }

    // ------------------------------------------------------------------------------ locking

    /** Creates missing rows and locks all of them in (variant, warehouse) order. */
    public Map<WarehouseKey, WarehouseStock> lockWarehouseStock(UUID companyId, Collection<WarehouseKey> keys) {
        Map<WarehouseKey, WarehouseStock> result = new LinkedHashMap<>();
        if (keys.isEmpty()) {
            return result;
        }
        List<WarehouseKey> sorted =
                keys.stream().distinct().sorted(WAREHOUSE_ORDER).toList();
        var insert = dsl.insertInto(
                WAREHOUSE_STOCK, WAREHOUSE_STOCK.COMPANY_ID, WAREHOUSE_STOCK.VARIANT_ID, WAREHOUSE_STOCK.WAREHOUSE_ID);
        for (WarehouseKey key : sorted) {
            insert = insert.values(companyId, key.variantId(), key.warehouseId());
        }
        insert.onConflictDoNothing().execute();
        List<Row2<UUID, UUID>> rows = sorted.stream()
                .map(k -> DSL.row(k.variantId(), k.warehouseId()))
                .toList();
        dsl.selectFrom(WAREHOUSE_STOCK)
                .where(WAREHOUSE_STOCK.COMPANY_ID.eq(companyId))
                .and(DSL.row(WAREHOUSE_STOCK.VARIANT_ID, WAREHOUSE_STOCK.WAREHOUSE_ID)
                        .in(rows))
                .orderBy(WAREHOUSE_STOCK.VARIANT_ID, WAREHOUSE_STOCK.WAREHOUSE_ID)
                .forUpdate()
                .fetch()
                .forEach(r -> result.put(
                        new WarehouseKey(r.getVariantId(), r.getWarehouseId()),
                        new WarehouseStock(r.getOnHand(), r.getReserved())));
        return result;
    }

    /** Creates missing rows and locks all of them in (variant, location) order. */
    public Map<LocationKey, BigDecimal> lockBalances(UUID companyId, Collection<LocationKey> keys) {
        Map<LocationKey, BigDecimal> result = new LinkedHashMap<>();
        if (keys.isEmpty()) {
            return result;
        }
        List<LocationKey> sorted =
                keys.stream().distinct().sorted(LOCATION_ORDER).toList();
        var insert = dsl.insertInto(
                STOCK_BALANCES,
                STOCK_BALANCES.COMPANY_ID,
                STOCK_BALANCES.VARIANT_ID,
                STOCK_BALANCES.LOCATION_ID,
                STOCK_BALANCES.WAREHOUSE_ID);
        for (LocationKey key : sorted) {
            insert = insert.values(companyId, key.variantId(), key.locationId(), key.warehouseId());
        }
        insert.onConflictDoNothing().execute();
        Map<UUID, UUID> warehouseOf = new LinkedHashMap<>();
        sorted.forEach(k -> warehouseOf.put(k.locationId(), k.warehouseId()));
        List<Row2<UUID, UUID>> rows =
                sorted.stream().map(k -> DSL.row(k.variantId(), k.locationId())).toList();
        dsl.selectFrom(STOCK_BALANCES)
                .where(STOCK_BALANCES.COMPANY_ID.eq(companyId))
                .and(DSL.row(STOCK_BALANCES.VARIANT_ID, STOCK_BALANCES.LOCATION_ID)
                        .in(rows))
                .orderBy(STOCK_BALANCES.VARIANT_ID, STOCK_BALANCES.LOCATION_ID)
                .forUpdate()
                .fetch()
                .forEach(r -> result.put(
                        new LocationKey(r.getVariantId(), r.getLocationId(), r.getWarehouseId()), r.getOnHand()));
        return result;
    }

    /** Creates missing valuations and locks all of them in variant order. */
    public Map<UUID, Valuation> lockValuations(UUID companyId, Collection<UUID> variantIds) {
        Map<UUID, Valuation> result = new LinkedHashMap<>();
        if (variantIds.isEmpty()) {
            return result;
        }
        List<UUID> sorted = variantIds.stream().distinct().sorted().toList();
        var insert = dsl.insertInto(ITEM_VALUATIONS, ITEM_VALUATIONS.COMPANY_ID, ITEM_VALUATIONS.VARIANT_ID);
        for (UUID variant : sorted) {
            insert = insert.values(companyId, variant);
        }
        insert.onConflictDoNothing().execute();
        dsl.selectFrom(ITEM_VALUATIONS)
                .where(ITEM_VALUATIONS.COMPANY_ID.eq(companyId))
                .and(ITEM_VALUATIONS.VARIANT_ID.in(sorted))
                .orderBy(ITEM_VALUATIONS.VARIANT_ID)
                .forUpdate()
                .fetch()
                .forEach(r -> result.put(r.getVariantId(), new Valuation(r.getQuantityBase(), r.getTotalValueBase())));
        return result;
    }

    // ------------------------------------------------------------------------------ writing

    public void updateWarehouseStock(UUID companyId, WarehouseKey key, BigDecimal onHand, BigDecimal reserved) {
        dsl.update(WAREHOUSE_STOCK)
                .set(WAREHOUSE_STOCK.ON_HAND, onHand)
                .set(WAREHOUSE_STOCK.RESERVED, reserved)
                .set(WAREHOUSE_STOCK.UPDATED_AT, OffsetDateTime.now())
                .where(WAREHOUSE_STOCK.COMPANY_ID.eq(companyId))
                .and(WAREHOUSE_STOCK.VARIANT_ID.eq(key.variantId()))
                .and(WAREHOUSE_STOCK.WAREHOUSE_ID.eq(key.warehouseId()))
                .execute();
    }

    public void updateBalance(UUID companyId, LocationKey key, BigDecimal onHand) {
        dsl.update(STOCK_BALANCES)
                .set(STOCK_BALANCES.ON_HAND, onHand)
                .set(STOCK_BALANCES.UPDATED_AT, OffsetDateTime.now())
                .where(STOCK_BALANCES.COMPANY_ID.eq(companyId))
                .and(STOCK_BALANCES.VARIANT_ID.eq(key.variantId()))
                .and(STOCK_BALANCES.LOCATION_ID.eq(key.locationId()))
                .execute();
    }

    /** Σ item valuations of the company (base currency). */
    public BigDecimal valuationTotal(UUID companyId) {
        BigDecimal total = dsl.select(DSL.sum(ITEM_VALUATIONS.TOTAL_VALUE_BASE))
                .from(ITEM_VALUATIONS)
                .where(ITEM_VALUATIONS.COMPANY_ID.eq(companyId))
                .fetchOne(0, BigDecimal.class);
        return total == null ? BigDecimal.ZERO : total;
    }

    public void updateValuation(UUID companyId, UUID variantId, Valuation valuation) {
        dsl.update(ITEM_VALUATIONS)
                .set(ITEM_VALUATIONS.QUANTITY_BASE, valuation.quantity())
                .set(ITEM_VALUATIONS.TOTAL_VALUE_BASE, valuation.value())
                .set(ITEM_VALUATIONS.UPDATED_AT, OffsetDateTime.now())
                .where(ITEM_VALUATIONS.COMPANY_ID.eq(companyId))
                .and(ITEM_VALUATIONS.VARIANT_ID.eq(variantId))
                .execute();
    }

    /** Appends rows to the ledger; returns their IDs in order. */
    public List<UUID> appendLedger(UUID companyId, List<LedgerRow> rows, UUID actor) {
        return rows.stream()
                .map(r -> dsl.insertInto(INVENTORY_TRANSACTIONS)
                        .set(INVENTORY_TRANSACTIONS.COMPANY_ID, companyId)
                        .set(INVENTORY_TRANSACTIONS.MOVEMENT_ID, r.movementId())
                        .set(INVENTORY_TRANSACTIONS.MOVEMENT_LINE_ID, r.movementLineId())
                        .set(INVENTORY_TRANSACTIONS.MOVEMENT_TYPE, r.movementType())
                        .set(INVENTORY_TRANSACTIONS.TRANSACTION_DATE, r.transactionDate())
                        .set(INVENTORY_TRANSACTIONS.VARIANT_ID, r.variantId())
                        .set(INVENTORY_TRANSACTIONS.WAREHOUSE_ID, r.warehouseId())
                        .set(INVENTORY_TRANSACTIONS.LOCATION_ID, r.locationId())
                        .set(INVENTORY_TRANSACTIONS.QUANTITY_BASE, r.quantityBase())
                        .set(INVENTORY_TRANSACTIONS.UNIT_COST_BASE, r.unitCostBase())
                        .set(INVENTORY_TRANSACTIONS.VALUE_BASE, r.valueBase())
                        .set(INVENTORY_TRANSACTIONS.CREATED_BY, actor)
                        .returning(INVENTORY_TRANSACTIONS.ID)
                        .fetchOne(INVENTORY_TRANSACTIONS.ID))
                .toList();
    }

    public List<InventoryViews.LedgerEntry> ledgerOfMovement(UUID companyId, UUID movementId) {
        return dsl.selectFrom(INVENTORY_TRANSACTIONS)
                .where(INVENTORY_TRANSACTIONS.COMPANY_ID.eq(companyId))
                .and(INVENTORY_TRANSACTIONS.MOVEMENT_ID.eq(movementId))
                .orderBy(INVENTORY_TRANSACTIONS.CREATED_AT, INVENTORY_TRANSACTIONS.ID)
                .fetch(StockRepository::toLedger);
    }

    // ------------------------------------------------------------------------------ reading

    public PageResponse<InventoryViews.WarehouseStockLevel> stockLevels(
            UUID companyId, @Nullable Set<UUID> visibleWarehouses, ListQuery query) {
        Condition scope = WAREHOUSE_STOCK
                .COMPANY_ID
                .eq(companyId)
                .and(WAREHOUSE_STOCK.ON_HAND.gt(BigDecimal.ZERO).or(WAREHOUSE_STOCK.RESERVED.gt(BigDecimal.ZERO)));
        if (visibleWarehouses != null) {
            scope = scope.and(WAREHOUSE_STOCK.WAREHOUSE_ID.in(visibleWarehouses));
        }
        return paginator.fetch(
                dsl,
                WAREHOUSE_STOCK,
                scope,
                query,
                LEVEL_BINDING,
                r -> new InventoryViews.WarehouseStockLevel(
                        r.getVariantId(), r.getWarehouseId(), r.getOnHand(), r.getReserved(), r.getUpdatedAt()));
    }

    public WarehouseStock warehouseStock(UUID companyId, UUID variantId, UUID warehouseId) {
        return dsl.selectFrom(WAREHOUSE_STOCK)
                .where(WAREHOUSE_STOCK.COMPANY_ID.eq(companyId))
                .and(WAREHOUSE_STOCK.VARIANT_ID.eq(variantId))
                .and(WAREHOUSE_STOCK.WAREHOUSE_ID.eq(warehouseId))
                .fetchOptional(r -> new WarehouseStock(r.getOnHand(), r.getReserved()))
                .orElse(new WarehouseStock(BigDecimal.ZERO, BigDecimal.ZERO));
    }

    public BigDecimal balance(UUID companyId, UUID variantId, UUID locationId) {
        return dsl.select(STOCK_BALANCES.ON_HAND)
                .from(STOCK_BALANCES)
                .where(STOCK_BALANCES.COMPANY_ID.eq(companyId))
                .and(STOCK_BALANCES.VARIANT_ID.eq(variantId))
                .and(STOCK_BALANCES.LOCATION_ID.eq(locationId))
                .fetchOptional(STOCK_BALANCES.ON_HAND)
                .orElse(BigDecimal.ZERO);
    }

    public PageResponse<InventoryViews.LocationStockLevel> balances(
            UUID companyId, @Nullable Set<UUID> visibleWarehouses, ListQuery query) {
        Condition scope = STOCK_BALANCES.COMPANY_ID.eq(companyId).and(STOCK_BALANCES.ON_HAND.gt(BigDecimal.ZERO));
        if (visibleWarehouses != null) {
            scope = scope.and(STOCK_BALANCES.WAREHOUSE_ID.in(visibleWarehouses));
        }
        return paginator.fetch(
                dsl,
                STOCK_BALANCES,
                scope,
                query,
                BALANCE_BINDING,
                r -> new InventoryViews.LocationStockLevel(
                        r.getVariantId(), r.getWarehouseId(), r.getLocationId(), r.getOnHand(), r.getUpdatedAt()));
    }

    /** All positive balances of a warehouse (count snapshots). */
    public List<InventoryViews.LocationStockLevel> balancesOfWarehouse(UUID companyId, UUID warehouseId) {
        return dsl.selectFrom(STOCK_BALANCES)
                .where(STOCK_BALANCES.COMPANY_ID.eq(companyId))
                .and(STOCK_BALANCES.WAREHOUSE_ID.eq(warehouseId))
                .and(STOCK_BALANCES.ON_HAND.gt(BigDecimal.ZERO))
                .orderBy(STOCK_BALANCES.VARIANT_ID, STOCK_BALANCES.LOCATION_ID)
                .fetch(r -> new InventoryViews.LocationStockLevel(
                        r.getVariantId(), r.getWarehouseId(), r.getLocationId(), r.getOnHand(), r.getUpdatedAt()));
    }

    public PageResponse<InventoryViews.LedgerEntry> ledger(
            UUID companyId, @Nullable Set<UUID> visibleWarehouses, ListQuery query) {
        Condition scope = INVENTORY_TRANSACTIONS.COMPANY_ID.eq(companyId);
        if (visibleWarehouses != null) {
            scope = scope.and(INVENTORY_TRANSACTIONS.WAREHOUSE_ID.in(visibleWarehouses));
        }
        return paginator.fetch(dsl, INVENTORY_TRANSACTIONS, scope, query, LEDGER_BINDING, StockRepository::toLedger);
    }

    /** Current valuation per variant (quantity and value in base units/currency). */
    public List<InventoryViews.ItemValuation> valuations(UUID companyId, @Nullable UUID variantId) {
        return dsl.selectFrom(ITEM_VALUATIONS)
                .where(ITEM_VALUATIONS.COMPANY_ID.eq(companyId))
                .and(variantId == null ? DSL.noCondition() : ITEM_VALUATIONS.VARIANT_ID.eq(variantId))
                .and(ITEM_VALUATIONS.QUANTITY_BASE.gt(BigDecimal.ZERO))
                .orderBy(ITEM_VALUATIONS.VARIANT_ID)
                .fetch(r ->
                        new InventoryViews.ItemValuation(r.getVariantId(), r.getQuantityBase(), r.getTotalValueBase()));
    }

    /** Valuation on a past date, summed from the ledger (INV-5: the ledger is the source of truth). */
    public List<InventoryViews.ItemValuation> valuationsAsOf(UUID companyId, LocalDate asOf, @Nullable UUID variantId) {
        var qty = DSL.sum(INVENTORY_TRANSACTIONS.QUANTITY_BASE);
        var value = DSL.sum(INVENTORY_TRANSACTIONS.VALUE_BASE);
        return dsl.select(INVENTORY_TRANSACTIONS.VARIANT_ID, qty, value)
                .from(INVENTORY_TRANSACTIONS)
                .where(INVENTORY_TRANSACTIONS.COMPANY_ID.eq(companyId))
                .and(INVENTORY_TRANSACTIONS.TRANSACTION_DATE.le(asOf))
                .and(variantId == null ? DSL.noCondition() : INVENTORY_TRANSACTIONS.VARIANT_ID.eq(variantId))
                .groupBy(INVENTORY_TRANSACTIONS.VARIANT_ID)
                .having(qty.gt(BigDecimal.ZERO))
                .orderBy(INVENTORY_TRANSACTIONS.VARIANT_ID)
                .fetch(r -> new InventoryViews.ItemValuation(r.value1(), r.value2(), r.value3()));
    }

    // -------------------------------------------------------------------- invariant checks

    /** (variant, location) pairs whose balance differs from the ledger sum (ARCHITECTURE.md §6.8). */
    public int balanceLedgerMismatches(UUID companyId) {
        return dsl.fetchSingle("""
                SELECT count(*) FROM (
                  SELECT coalesce(b.variant_id, l.variant_id)
                    FROM inventory.stock_balances b
                    FULL JOIN (SELECT variant_id, location_id, sum(quantity_base) AS qty
                                 FROM inventory.inventory_transactions WHERE company_id = ?
                                GROUP BY variant_id, location_id) l
                      ON l.variant_id = b.variant_id AND l.location_id = b.location_id
                   WHERE (b.company_id = ? OR b.company_id IS NULL)
                     AND coalesce(b.on_hand, 0) <> coalesce(l.qty, 0)) mismatches
                """, companyId, companyId).get(0, Long.class).intValue();
    }

    /** Warehouses whose on-hand differs from the sum of their counting locations' balances. */
    public int warehouseBalanceMismatches(UUID companyId) {
        return dsl.fetchSingle("""
                SELECT count(*) FROM (
                  SELECT 1
                    FROM inventory.warehouse_stock w
                    FULL JOIN (SELECT b.variant_id, b.warehouse_id, sum(b.on_hand) AS qty
                                 FROM inventory.stock_balances b
                                 JOIN inventory.locations l ON l.company_id = b.company_id AND l.id = b.location_id
                                WHERE b.company_id = ? AND l.location_type IN ('INTERNAL', 'RECEIVING', 'SHIPPING')
                                GROUP BY b.variant_id, b.warehouse_id) s
                      ON s.variant_id = w.variant_id AND s.warehouse_id = w.warehouse_id
                   WHERE (w.company_id = ? OR w.company_id IS NULL)
                     AND coalesce(w.on_hand, 0) <> coalesce(s.qty, 0)) mismatches
                """, companyId, companyId).get(0, Long.class).intValue();
    }

    /** Variants whose valuation differs from the ledger's quantity or value. */
    public int valuationLedgerMismatches(UUID companyId) {
        return dsl.fetchSingle("""
                SELECT count(*) FROM (
                  SELECT 1
                    FROM inventory.item_valuations v
                    FULL JOIN (SELECT variant_id, sum(quantity_base) AS qty, sum(value_base) AS value
                                 FROM inventory.inventory_transactions WHERE company_id = ?
                                GROUP BY variant_id) l
                      ON l.variant_id = v.variant_id
                   WHERE (v.company_id = ? OR v.company_id IS NULL)
                     AND (coalesce(v.quantity_base, 0) <> coalesce(l.qty, 0)
                          OR coalesce(v.total_value_base, 0) <> coalesce(l.value, 0))) mismatches
                """, companyId, companyId).get(0, Long.class).intValue();
    }

    /** Warehouses whose reserved quantity differs from the sum of their active reservations. */
    public int reservationMismatches(UUID companyId) {
        var active = dsl.select(
                        STOCK_RESERVATIONS.VARIANT_ID,
                        STOCK_RESERVATIONS.WAREHOUSE_ID,
                        DSL.sum(STOCK_RESERVATIONS.QUANTITY_BASE).as("qty"))
                .from(STOCK_RESERVATIONS)
                .where(STOCK_RESERVATIONS.COMPANY_ID.eq(companyId))
                .and(STOCK_RESERVATIONS.STATUS.eq("ACTIVE"))
                .groupBy(STOCK_RESERVATIONS.VARIANT_ID, STOCK_RESERVATIONS.WAREHOUSE_ID)
                .asTable("r");
        return dsl.fetchCount(dsl.selectOne()
                .from(WAREHOUSE_STOCK)
                .leftJoin(active)
                .on(active.field(STOCK_RESERVATIONS.VARIANT_ID).eq(WAREHOUSE_STOCK.VARIANT_ID))
                .and(active.field(STOCK_RESERVATIONS.WAREHOUSE_ID).eq(WAREHOUSE_STOCK.WAREHOUSE_ID))
                .where(WAREHOUSE_STOCK.COMPANY_ID.eq(companyId))
                .and(WAREHOUSE_STOCK.RESERVED.ne(
                        DSL.coalesce(active.field("qty", BigDecimal.class), BigDecimal.ZERO))));
    }

    static InventoryViews.LedgerEntry toLedger(InventoryTransactionsRecord r) {
        return new InventoryViews.LedgerEntry(
                r.getId(),
                r.getMovementId(),
                r.getMovementLineId(),
                r.getMovementType(),
                r.getTransactionDate(),
                r.getVariantId(),
                r.getWarehouseId(),
                r.getLocationId(),
                r.getQuantityBase(),
                r.getUnitCostBase(),
                r.getValueBase(),
                r.getCreatedAt(),
                r.getCreatedBy());
    }
}
