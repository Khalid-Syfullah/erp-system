package com.erp.inventory.persistence;

import static com.erp.db.inventory.Tables.STOCK_RESERVATIONS;

import com.erp.db.inventory.tables.records.StockReservationsRecord;
import com.erp.inventory.application.InventoryViews;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

/**
 * Stock reservations (INV-6). Locked after the warehouse stock row they belong to (DATABASE.md §9), so
 * {@code warehouse_stock.reserved} always equals the sum of active reservations.
 */
@Repository
public class ReservationRepository {

    private final DSLContext dsl;

    public ReservationRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public UUID insert(
            UUID companyId,
            UUID variantId,
            UUID warehouseId,
            BigDecimal quantityBase,
            String sourceModule,
            String sourceType,
            UUID sourceId,
            UUID sourceLineId,
            UUID actor) {
        return dsl.insertInto(STOCK_RESERVATIONS)
                .set(STOCK_RESERVATIONS.COMPANY_ID, companyId)
                .set(STOCK_RESERVATIONS.VARIANT_ID, variantId)
                .set(STOCK_RESERVATIONS.WAREHOUSE_ID, warehouseId)
                .set(STOCK_RESERVATIONS.QUANTITY_BASE, quantityBase)
                .set(STOCK_RESERVATIONS.SOURCE_MODULE, sourceModule)
                .set(STOCK_RESERVATIONS.SOURCE_TYPE, sourceType)
                .set(STOCK_RESERVATIONS.SOURCE_ID, sourceId)
                .set(STOCK_RESERVATIONS.SOURCE_LINE_ID, sourceLineId)
                .set(STOCK_RESERVATIONS.CREATED_BY, actor)
                .set(STOCK_RESERVATIONS.UPDATED_BY, actor)
                .returning(STOCK_RESERVATIONS.ID)
                .fetchOne(STOCK_RESERVATIONS.ID);
    }

    public Optional<InventoryViews.Reservation> find(UUID companyId, UUID id) {
        return dsl.selectFrom(STOCK_RESERVATIONS)
                .where(STOCK_RESERVATIONS.COMPANY_ID.eq(companyId))
                .and(STOCK_RESERVATIONS.ID.eq(id))
                .fetchOptional(ReservationRepository::toView);
    }

    /** Locks reservations in ID order. */
    public Map<UUID, InventoryViews.Reservation> lock(UUID companyId, Collection<UUID> ids) {
        Map<UUID, InventoryViews.Reservation> result = new LinkedHashMap<>();
        if (ids.isEmpty()) {
            return result;
        }
        dsl.selectFrom(STOCK_RESERVATIONS)
                .where(STOCK_RESERVATIONS.COMPANY_ID.eq(companyId))
                .and(STOCK_RESERVATIONS.ID.in(ids))
                .orderBy(STOCK_RESERVATIONS.ID)
                .forUpdate()
                .fetch(ReservationRepository::toView)
                .forEach(r -> result.put(r.id(), r));
        return result;
    }

    /** The active reservation of a source line in a warehouse, locked. */
    public Optional<InventoryViews.Reservation> lockActiveForSourceLine(
            UUID companyId, String sourceModule, UUID sourceLineId, UUID warehouseId) {
        return dsl.selectFrom(STOCK_RESERVATIONS)
                .where(STOCK_RESERVATIONS.COMPANY_ID.eq(companyId))
                .and(STOCK_RESERVATIONS.SOURCE_MODULE.eq(sourceModule))
                .and(STOCK_RESERVATIONS.SOURCE_LINE_ID.eq(sourceLineId))
                .and(STOCK_RESERVATIONS.WAREHOUSE_ID.eq(warehouseId))
                .and(STOCK_RESERVATIONS.STATUS.eq("ACTIVE"))
                .forUpdate()
                .fetchOptional(ReservationRepository::toView);
    }

    public void update(UUID companyId, UUID id, BigDecimal quantityBase, String status, UUID actor) {
        dsl.update(STOCK_RESERVATIONS)
                .set(STOCK_RESERVATIONS.QUANTITY_BASE, quantityBase)
                .set(STOCK_RESERVATIONS.STATUS, status)
                .set(STOCK_RESERVATIONS.UPDATED_AT, OffsetDateTime.now())
                .set(STOCK_RESERVATIONS.UPDATED_BY, actor)
                .set(STOCK_RESERVATIONS.VERSION, STOCK_RESERVATIONS.VERSION.plus(1))
                .where(STOCK_RESERVATIONS.COMPANY_ID.eq(companyId))
                .and(STOCK_RESERVATIONS.ID.eq(id))
                .execute();
    }

    static InventoryViews.Reservation toView(StockReservationsRecord r) {
        return new InventoryViews.Reservation(
                r.getId(),
                r.getVariantId(),
                r.getWarehouseId(),
                r.getQuantityBase(),
                r.getSourceModule(),
                r.getSourceType(),
                r.getSourceId(),
                r.getSourceLineId(),
                r.getStatus(),
                r.getVersion());
    }
}
