package com.erp.inventory.persistence;

import static com.erp.db.inventory.Tables.STOCK_MOVEMENTS;
import static com.erp.db.inventory.Tables.STOCK_MOVEMENT_LINES;

import com.erp.db.inventory.tables.records.StockMovementLinesRecord;
import com.erp.db.inventory.tables.records.StockMovementsRecord;
import com.erp.inventory.application.InventoryCommands;
import com.erp.inventory.application.InventoryListings;
import com.erp.inventory.application.InventoryViews;
import com.erp.inventory.domain.MovementStatus;
import com.erp.inventory.domain.MovementType;
import com.erp.platform.jooq.KeysetPaginator;
import com.erp.platform.jooq.ListBinding;
import com.erp.platform.web.paging.ListQuery;
import com.erp.platform.web.paging.PageResponse;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;

/** Stock movements and their lines. Only drafts change; triggers guard posted ones (DATABASE.md §8.2). */
@Repository
public class MovementRepository {

    private static final ListBinding BINDING = ListBinding.builder(InventoryListings.MOVEMENTS)
            .field("movementDate", STOCK_MOVEMENTS.MOVEMENT_DATE)
            .field("createdAt", STOCK_MOVEMENTS.CREATED_AT)
            .field("movementType", STOCK_MOVEMENTS.MOVEMENT_TYPE)
            .field("status", STOCK_MOVEMENTS.STATUS)
            .field("warehouseId", STOCK_MOVEMENTS.WAREHOUSE_ID)
            .field("destWarehouseId", STOCK_MOVEMENTS.DEST_WAREHOUSE_ID)
            .field("sourceId", STOCK_MOVEMENTS.SOURCE_ID)
            .field("number", STOCK_MOVEMENTS.NUMBER)
            .tiebreaker(STOCK_MOVEMENTS.ID)
            .search(List.of(STOCK_MOVEMENTS.NUMBER, STOCK_MOVEMENTS.SOURCE_NUMBER, STOCK_MOVEMENTS.NOTES))
            .build();

    private final DSLContext dsl;
    private final KeysetPaginator paginator;

    public MovementRepository(DSLContext dsl, KeysetPaginator paginator) {
        this.dsl = dsl;
        this.paginator = paginator;
    }

    public UUID insert(UUID companyId, InventoryCommands.Movement c, UUID actor) {
        return dsl.insertInto(STOCK_MOVEMENTS)
                .set(STOCK_MOVEMENTS.COMPANY_ID, companyId)
                .set(STOCK_MOVEMENTS.MOVEMENT_TYPE, c.type().name())
                .set(STOCK_MOVEMENTS.STATUS, MovementStatus.DRAFT.name())
                .set(STOCK_MOVEMENTS.MOVEMENT_DATE, c.movementDate())
                .set(STOCK_MOVEMENTS.WAREHOUSE_ID, c.warehouseId())
                .set(STOCK_MOVEMENTS.DEST_WAREHOUSE_ID, c.destWarehouseId())
                .set(STOCK_MOVEMENTS.PARTNER_ID, c.partnerId())
                .set(STOCK_MOVEMENTS.REASON_CODE_ID, c.reasonCodeId())
                .set(STOCK_MOVEMENTS.SOURCE_MODULE, c.sourceModule())
                .set(STOCK_MOVEMENTS.SOURCE_TYPE, c.sourceType())
                .set(STOCK_MOVEMENTS.SOURCE_ID, c.sourceId())
                .set(STOCK_MOVEMENTS.SOURCE_NUMBER, c.sourceNumber())
                .set(STOCK_MOVEMENTS.REVERSAL_OF_ID, c.reversalOfId())
                .set(STOCK_MOVEMENTS.RELATED_MOVEMENT_ID, c.relatedMovementId())
                .set(STOCK_MOVEMENTS.NOTES, c.notes())
                .set(STOCK_MOVEMENTS.CREATED_BY, actor)
                .set(STOCK_MOVEMENTS.UPDATED_BY, actor)
                .returning(STOCK_MOVEMENTS.ID)
                .fetchOne(STOCK_MOVEMENTS.ID);
    }

    public void insertLines(UUID companyId, UUID movementId, List<InventoryCommands.ResolvedLine> lines, UUID actor) {
        int lineNo = 1;
        for (InventoryCommands.ResolvedLine resolved : lines) {
            InventoryCommands.Line l = resolved.line();
            dsl.insertInto(STOCK_MOVEMENT_LINES)
                    .set(STOCK_MOVEMENT_LINES.COMPANY_ID, companyId)
                    .set(STOCK_MOVEMENT_LINES.MOVEMENT_ID, movementId)
                    .set(STOCK_MOVEMENT_LINES.LINE_NO, lineNo++)
                    .set(STOCK_MOVEMENT_LINES.VARIANT_ID, l.variantId())
                    .set(STOCK_MOVEMENT_LINES.FROM_LOCATION_ID, l.fromLocationId())
                    .set(STOCK_MOVEMENT_LINES.TO_LOCATION_ID, l.toLocationId())
                    .set(STOCK_MOVEMENT_LINES.QUANTITY, l.quantity())
                    .set(STOCK_MOVEMENT_LINES.UOM_ID, l.uomId())
                    .set(STOCK_MOVEMENT_LINES.QUANTITY_BASE, resolved.quantityBase())
                    .set(STOCK_MOVEMENT_LINES.UNIT_COST_BASE, l.unitCostBase())
                    .set(STOCK_MOVEMENT_LINES.REFERENCE_UNIT_COST_BASE, l.referenceUnitCostBase())
                    .set(STOCK_MOVEMENT_LINES.RESERVATION_ID, l.reservationId())
                    .set(STOCK_MOVEMENT_LINES.SOURCE_LINE_ID, l.sourceLineId())
                    .set(STOCK_MOVEMENT_LINES.REVERSAL_OF_LINE_ID, l.reversalOfLineId())
                    .set(STOCK_MOVEMENT_LINES.CREATED_BY, actor)
                    .set(STOCK_MOVEMENT_LINES.UPDATED_BY, actor)
                    .execute();
        }
    }

    public void deleteLines(UUID companyId, UUID movementId) {
        dsl.deleteFrom(STOCK_MOVEMENT_LINES)
                .where(STOCK_MOVEMENT_LINES.COMPANY_ID.eq(companyId))
                .and(STOCK_MOVEMENT_LINES.MOVEMENT_ID.eq(movementId))
                .execute();
    }

    public Optional<InventoryViews.Movement> find(UUID companyId, UUID id) {
        return dsl.selectFrom(STOCK_MOVEMENTS)
                .where(STOCK_MOVEMENTS.COMPANY_ID.eq(companyId))
                .and(STOCK_MOVEMENTS.ID.eq(id))
                .fetchOptional(MovementRepository::toView);
    }

    /** Locks the movement header {@code FOR UPDATE}: the document lock that comes first (ARCHITECTURE.md §6.2). */
    public Optional<InventoryViews.Movement> lockForChange(UUID companyId, UUID id) {
        return dsl.selectFrom(STOCK_MOVEMENTS)
                .where(STOCK_MOVEMENTS.COMPANY_ID.eq(companyId))
                .and(STOCK_MOVEMENTS.ID.eq(id))
                .forUpdate()
                .fetchOptional(MovementRepository::toView);
    }

    public List<InventoryViews.MovementLine> lines(UUID companyId, UUID movementId) {
        return dsl.selectFrom(STOCK_MOVEMENT_LINES)
                .where(STOCK_MOVEMENT_LINES.COMPANY_ID.eq(companyId))
                .and(STOCK_MOVEMENT_LINES.MOVEMENT_ID.eq(movementId))
                .orderBy(STOCK_MOVEMENT_LINES.LINE_NO)
                .fetch(MovementRepository::toLine);
    }

    /** {@code visibleWarehouses == null}: no branch restriction. */
    public PageResponse<InventoryViews.Movement> list(
            UUID companyId, @Nullable Set<UUID> visibleWarehouses, ListQuery query) {
        Condition scope = STOCK_MOVEMENTS.COMPANY_ID.eq(companyId);
        if (visibleWarehouses != null) {
            scope = scope.and(STOCK_MOVEMENTS
                    .WAREHOUSE_ID
                    .in(visibleWarehouses)
                    .or(STOCK_MOVEMENTS.DEST_WAREHOUSE_ID.in(visibleWarehouses)));
        }
        return paginator.fetch(dsl, STOCK_MOVEMENTS, scope, query, BINDING, MovementRepository::toView);
    }

    public boolean updateDraft(
            UUID companyId,
            UUID id,
            int version,
            UUID actor,
            LocalDate movementDate,
            @Nullable UUID destWarehouseId,
            @Nullable UUID reasonCodeId,
            @Nullable String notes) {
        return dsl.update(STOCK_MOVEMENTS)
                        .set(STOCK_MOVEMENTS.MOVEMENT_DATE, movementDate)
                        .set(STOCK_MOVEMENTS.DEST_WAREHOUSE_ID, destWarehouseId)
                        .set(STOCK_MOVEMENTS.REASON_CODE_ID, reasonCodeId)
                        .set(STOCK_MOVEMENTS.NOTES, notes)
                        .set(STOCK_MOVEMENTS.UPDATED_AT, OffsetDateTime.now())
                        .set(STOCK_MOVEMENTS.UPDATED_BY, actor)
                        .set(STOCK_MOVEMENTS.VERSION, version + 1)
                        .where(STOCK_MOVEMENTS.COMPANY_ID.eq(companyId))
                        .and(STOCK_MOVEMENTS.ID.eq(id))
                        .and(STOCK_MOVEMENTS.VERSION.eq(version))
                        .and(STOCK_MOVEMENTS.STATUS.eq(MovementStatus.DRAFT.name()))
                        .execute()
                == 1;
    }

    /** Records the cost a line was posted at (while the movement is still a draft). */
    public void setLineCost(UUID companyId, UUID lineId, BigDecimal unitCostBase) {
        dsl.update(STOCK_MOVEMENT_LINES)
                .set(STOCK_MOVEMENT_LINES.UNIT_COST_BASE, unitCostBase)
                .where(STOCK_MOVEMENT_LINES.COMPANY_ID.eq(companyId))
                .and(STOCK_MOVEMENT_LINES.ID.eq(lineId))
                .execute();
    }

    public boolean markPosted(UUID companyId, UUID id, int version, String number, OffsetDateTime at, UUID actor) {
        return dsl.update(STOCK_MOVEMENTS)
                        .set(STOCK_MOVEMENTS.STATUS, MovementStatus.POSTED.name())
                        .set(STOCK_MOVEMENTS.NUMBER, number)
                        .set(STOCK_MOVEMENTS.POSTED_AT, at)
                        .set(STOCK_MOVEMENTS.POSTED_BY, actor)
                        .set(STOCK_MOVEMENTS.UPDATED_AT, at)
                        .set(STOCK_MOVEMENTS.UPDATED_BY, actor)
                        .set(STOCK_MOVEMENTS.VERSION, version + 1)
                        .where(STOCK_MOVEMENTS.COMPANY_ID.eq(companyId))
                        .and(STOCK_MOVEMENTS.ID.eq(id))
                        .and(STOCK_MOVEMENTS.VERSION.eq(version))
                        .and(STOCK_MOVEMENTS.STATUS.eq(MovementStatus.DRAFT.name()))
                        .execute()
                == 1;
    }

    public boolean markCancelled(UUID companyId, UUID id, int version, UUID actor) {
        return dsl.update(STOCK_MOVEMENTS)
                        .set(STOCK_MOVEMENTS.STATUS, MovementStatus.CANCELLED.name())
                        .set(STOCK_MOVEMENTS.UPDATED_AT, OffsetDateTime.now())
                        .set(STOCK_MOVEMENTS.UPDATED_BY, actor)
                        .set(STOCK_MOVEMENTS.VERSION, version + 1)
                        .where(STOCK_MOVEMENTS.COMPANY_ID.eq(companyId))
                        .and(STOCK_MOVEMENTS.ID.eq(id))
                        .and(STOCK_MOVEMENTS.VERSION.eq(version))
                        .and(STOCK_MOVEMENTS.STATUS.eq(MovementStatus.DRAFT.name()))
                        .execute()
                == 1;
    }

    public boolean delete(UUID companyId, UUID id, int version) {
        return dsl.deleteFrom(STOCK_MOVEMENTS)
                        .where(STOCK_MOVEMENTS.COMPANY_ID.eq(companyId))
                        .and(STOCK_MOVEMENTS.ID.eq(id))
                        .and(STOCK_MOVEMENTS.VERSION.eq(version))
                        .and(STOCK_MOVEMENTS.STATUS.eq(MovementStatus.DRAFT.name()))
                        .execute()
                == 1;
    }

    /** Whether a live movement already exists for a source document (mirrors uq_stock_movements__company_id_source). */
    public boolean existsForSource(UUID companyId, String module, String type, UUID sourceId) {
        return dsl.fetchExists(dsl.selectOne()
                .from(STOCK_MOVEMENTS)
                .where(STOCK_MOVEMENTS.COMPANY_ID.eq(companyId))
                .and(STOCK_MOVEMENTS.SOURCE_MODULE.eq(module))
                .and(STOCK_MOVEMENTS.SOURCE_TYPE.eq(type))
                .and(STOCK_MOVEMENTS.SOURCE_ID.eq(sourceId))
                .and(STOCK_MOVEMENTS.STATUS.ne(MovementStatus.CANCELLED.name()))
                .and(STOCK_MOVEMENTS.MOVEMENT_TYPE.ne(MovementType.REVERSAL.name())));
    }

    /** The live (draft or posted) movement of a type that refers to another one. */
    public Optional<InventoryViews.Movement> findLinked(
            UUID companyId, MovementType type, @Nullable UUID relatedMovementId, @Nullable UUID reversalOfId) {
        Condition link = relatedMovementId != null
                ? STOCK_MOVEMENTS.RELATED_MOVEMENT_ID.eq(relatedMovementId)
                : STOCK_MOVEMENTS.REVERSAL_OF_ID.eq(reversalOfId);
        return dsl.selectFrom(STOCK_MOVEMENTS)
                .where(STOCK_MOVEMENTS.COMPANY_ID.eq(companyId))
                .and(STOCK_MOVEMENTS.MOVEMENT_TYPE.eq(type.name()))
                .and(STOCK_MOVEMENTS.STATUS.ne(MovementStatus.CANCELLED.name()))
                .and(link)
                .fetchOptional(MovementRepository::toView);
    }

    static InventoryViews.Movement toView(StockMovementsRecord r) {
        return new InventoryViews.Movement(
                r.getId(),
                r.getCompanyId(),
                r.getNumber(),
                MovementType.valueOf(r.getMovementType()),
                MovementStatus.valueOf(r.getStatus()),
                r.getMovementDate(),
                r.getWarehouseId(),
                r.getDestWarehouseId(),
                r.getPartnerId(),
                r.getReasonCodeId(),
                r.getSourceModule(),
                r.getSourceType(),
                r.getSourceId(),
                r.getSourceNumber(),
                r.getReversalOfId(),
                r.getRelatedMovementId(),
                r.getPostedAt(),
                r.getPostedBy(),
                r.getNotes(),
                r.getCreatedAt(),
                r.getUpdatedAt(),
                r.getVersion());
    }

    static InventoryViews.MovementLine toLine(StockMovementLinesRecord r) {
        return new InventoryViews.MovementLine(
                r.getId(),
                r.getMovementId(),
                r.getLineNo(),
                r.getVariantId(),
                r.getFromLocationId(),
                r.getToLocationId(),
                r.getQuantity(),
                r.getUomId(),
                r.getQuantityBase(),
                r.getUnitCostBase(),
                r.getReferenceUnitCostBase(),
                r.getReservationId(),
                r.getSourceLineId(),
                r.getReversalOfLineId());
    }
}
