package com.erp.inventory.persistence;

import static com.erp.db.inventory.Tables.STOCK_COUNTS;
import static com.erp.db.inventory.Tables.STOCK_COUNT_LINES;

import com.erp.db.inventory.tables.records.StockCountsRecord;
import com.erp.inventory.application.InventoryListings;
import com.erp.inventory.application.InventoryViews;
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

/** Physical stock counts (INV-8) and their lines. */
@Repository
public class CountRepository {

    private static final ListBinding BINDING = ListBinding.builder(InventoryListings.COUNTS)
            .field("countDate", STOCK_COUNTS.COUNT_DATE)
            .field("createdAt", STOCK_COUNTS.CREATED_AT)
            .field("warehouseId", STOCK_COUNTS.WAREHOUSE_ID)
            .field("status", STOCK_COUNTS.STATUS)
            .tiebreaker(STOCK_COUNTS.ID)
            .build();

    private final DSLContext dsl;
    private final KeysetPaginator paginator;

    public CountRepository(DSLContext dsl, KeysetPaginator paginator) {
        this.dsl = dsl;
        this.paginator = paginator;
    }

    public UUID insert(
            UUID companyId,
            UUID warehouseId,
            LocalDate countDate,
            @Nullable UUID reasonCodeId,
            @Nullable String notes,
            UUID actor) {
        return dsl.insertInto(STOCK_COUNTS)
                .set(STOCK_COUNTS.COMPANY_ID, companyId)
                .set(STOCK_COUNTS.WAREHOUSE_ID, warehouseId)
                .set(STOCK_COUNTS.COUNT_DATE, countDate)
                .set(STOCK_COUNTS.REASON_CODE_ID, reasonCodeId)
                .set(STOCK_COUNTS.NOTES, notes)
                .set(STOCK_COUNTS.CREATED_BY, actor)
                .set(STOCK_COUNTS.UPDATED_BY, actor)
                .returning(STOCK_COUNTS.ID)
                .fetchOne(STOCK_COUNTS.ID);
    }

    public Optional<InventoryViews.Count> find(UUID companyId, UUID id) {
        return dsl.selectFrom(STOCK_COUNTS)
                .where(STOCK_COUNTS.COMPANY_ID.eq(companyId))
                .and(STOCK_COUNTS.ID.eq(id))
                .fetchOptional(CountRepository::toView);
    }

    public Optional<InventoryViews.Count> lockForChange(UUID companyId, UUID id) {
        return dsl.selectFrom(STOCK_COUNTS)
                .where(STOCK_COUNTS.COMPANY_ID.eq(companyId))
                .and(STOCK_COUNTS.ID.eq(id))
                .forUpdate()
                .fetchOptional(CountRepository::toView);
    }

    public PageResponse<InventoryViews.Count> list(
            UUID companyId, @Nullable Set<UUID> visibleWarehouses, ListQuery query) {
        Condition scope = STOCK_COUNTS.COMPANY_ID.eq(companyId);
        if (visibleWarehouses != null) {
            scope = scope.and(STOCK_COUNTS.WAREHOUSE_ID.in(visibleWarehouses));
        }
        return paginator.fetch(dsl, STOCK_COUNTS, scope, query, BINDING, CountRepository::toView);
    }

    public List<InventoryViews.CountLine> lines(UUID companyId, UUID countId) {
        return dsl.selectFrom(STOCK_COUNT_LINES)
                .where(STOCK_COUNT_LINES.COMPANY_ID.eq(companyId))
                .and(STOCK_COUNT_LINES.STOCK_COUNT_ID.eq(countId))
                .orderBy(STOCK_COUNT_LINES.VARIANT_ID, STOCK_COUNT_LINES.LOCATION_ID)
                .fetch(r -> new InventoryViews.CountLine(
                        r.getId(),
                        r.getVariantId(),
                        r.getLocationId(),
                        r.getSystemQuantityBase(),
                        r.getCountedQuantityBase()));
    }

    public void insertLine(
            UUID companyId, UUID countId, UUID variantId, UUID locationId, BigDecimal system, UUID actor) {
        dsl.insertInto(STOCK_COUNT_LINES)
                .set(STOCK_COUNT_LINES.COMPANY_ID, companyId)
                .set(STOCK_COUNT_LINES.STOCK_COUNT_ID, countId)
                .set(STOCK_COUNT_LINES.VARIANT_ID, variantId)
                .set(STOCK_COUNT_LINES.LOCATION_ID, locationId)
                .set(STOCK_COUNT_LINES.SYSTEM_QUANTITY_BASE, system)
                .set(STOCK_COUNT_LINES.CREATED_BY, actor)
                .set(STOCK_COUNT_LINES.UPDATED_BY, actor)
                .onConflictDoNothing()
                .execute();
    }

    public int setCounted(
            UUID companyId, UUID countId, UUID variantId, UUID locationId, @Nullable BigDecimal counted, UUID actor) {
        return dsl.update(STOCK_COUNT_LINES)
                .set(STOCK_COUNT_LINES.COUNTED_QUANTITY_BASE, counted)
                .set(STOCK_COUNT_LINES.UPDATED_AT, OffsetDateTime.now())
                .set(STOCK_COUNT_LINES.UPDATED_BY, actor)
                .set(STOCK_COUNT_LINES.VERSION, STOCK_COUNT_LINES.VERSION.plus(1))
                .where(STOCK_COUNT_LINES.COMPANY_ID.eq(companyId))
                .and(STOCK_COUNT_LINES.STOCK_COUNT_ID.eq(countId))
                .and(STOCK_COUNT_LINES.VARIANT_ID.eq(variantId))
                .and(STOCK_COUNT_LINES.LOCATION_ID.eq(locationId))
                .execute();
    }

    /** Header details only; the status, number and adjustment change through {@link #updateHeader}. */
    public boolean updateDetails(
            UUID companyId,
            UUID id,
            int version,
            UUID actor,
            LocalDate countDate,
            @Nullable UUID reasonCodeId,
            @Nullable String notes) {
        return dsl.update(STOCK_COUNTS)
                        .set(STOCK_COUNTS.COUNT_DATE, countDate)
                        .set(STOCK_COUNTS.REASON_CODE_ID, reasonCodeId)
                        .set(STOCK_COUNTS.NOTES, notes)
                        .set(STOCK_COUNTS.UPDATED_AT, OffsetDateTime.now())
                        .set(STOCK_COUNTS.UPDATED_BY, actor)
                        .set(STOCK_COUNTS.VERSION, version + 1)
                        .where(STOCK_COUNTS.COMPANY_ID.eq(companyId))
                        .and(STOCK_COUNTS.ID.eq(id))
                        .and(STOCK_COUNTS.VERSION.eq(version))
                        .execute()
                == 1;
    }

    public boolean updateHeader(
            UUID companyId,
            UUID id,
            int version,
            UUID actor,
            String status,
            @Nullable String number,
            @Nullable UUID reasonCodeId,
            @Nullable String notes,
            @Nullable UUID adjustmentMovementId) {
        return dsl.update(STOCK_COUNTS)
                        .set(STOCK_COUNTS.STATUS, status)
                        .set(STOCK_COUNTS.NUMBER, number)
                        .set(STOCK_COUNTS.REASON_CODE_ID, reasonCodeId)
                        .set(STOCK_COUNTS.NOTES, notes)
                        .set(STOCK_COUNTS.ADJUSTMENT_MOVEMENT_ID, adjustmentMovementId)
                        .set(STOCK_COUNTS.UPDATED_AT, OffsetDateTime.now())
                        .set(STOCK_COUNTS.UPDATED_BY, actor)
                        .set(STOCK_COUNTS.VERSION, version + 1)
                        .where(STOCK_COUNTS.COMPANY_ID.eq(companyId))
                        .and(STOCK_COUNTS.ID.eq(id))
                        .and(STOCK_COUNTS.VERSION.eq(version))
                        .execute()
                == 1;
    }

    static InventoryViews.Count toView(StockCountsRecord r) {
        return new InventoryViews.Count(
                r.getId(),
                r.getCompanyId(),
                r.getNumber(),
                r.getWarehouseId(),
                r.getCountDate(),
                r.getStatus(),
                r.getReasonCodeId(),
                r.getAdjustmentMovementId(),
                r.getNotes(),
                r.getCreatedAt(),
                r.getUpdatedAt(),
                r.getVersion());
    }
}
