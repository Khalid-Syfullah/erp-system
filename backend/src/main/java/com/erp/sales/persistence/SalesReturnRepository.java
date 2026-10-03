package com.erp.sales.persistence;

import static com.erp.db.sales.Tables.SALES_RETURNS;
import static com.erp.db.sales.Tables.SALES_RETURN_LINES;

import com.erp.db.sales.tables.records.SalesReturnLinesRecord;
import com.erp.db.sales.tables.records.SalesReturnsRecord;
import com.erp.platform.jooq.KeysetPaginator;
import com.erp.platform.jooq.ListBinding;
import com.erp.platform.web.paging.ListQuery;
import com.erp.platform.web.paging.PageResponse;
import com.erp.sales.application.SalesListings;
import com.erp.sales.application.SalesViews;
import java.math.BigDecimal;
import java.time.LocalDate;
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

/** Sales returns (customer returns) and their lines (with the credited counter). */
@Repository
public class SalesReturnRepository {

    private static final ListBinding BINDING = ListBinding.builder(SalesListings.RETURNS)
            .field("returnDate", SALES_RETURNS.RETURN_DATE)
            .field("createdAt", SALES_RETURNS.CREATED_AT)
            .field("status", SALES_RETURNS.STATUS)
            .field("deliveryId", SALES_RETURNS.DELIVERY_ID)
            .field("salesOrderId", SALES_RETURNS.SALES_ORDER_ID)
            .field("customerId", SALES_RETURNS.CUSTOMER_ID)
            .tiebreaker(SALES_RETURNS.ID)
            .search(List.of(SALES_RETURNS.NUMBER, SALES_RETURNS.REASON))
            .build();

    public record NewLine(
            int lineNo,
            UUID deliveryLineId,
            UUID variantId,
            @Nullable UUID locationId,
            BigDecimal quantity,
            UUID uomId,
            BigDecimal quantityBase,
            BigDecimal unitCostBase) {}

    private final DSLContext dsl;
    private final KeysetPaginator paginator;

    public SalesReturnRepository(DSLContext dsl, KeysetPaginator paginator) {
        this.dsl = dsl;
        this.paginator = paginator;
    }

    public UUID insert(UUID companyId, SalesViews.Delivery delivery, LocalDate returnDate, String reason, UUID actor) {
        return dsl.insertInto(SALES_RETURNS)
                .set(SALES_RETURNS.COMPANY_ID, companyId)
                .set(SALES_RETURNS.CUSTOMER_ID, delivery.customerId())
                .set(SALES_RETURNS.SALES_ORDER_ID, delivery.salesOrderId())
                .set(SALES_RETURNS.DELIVERY_ID, delivery.id())
                .set(SALES_RETURNS.BRANCH_ID, delivery.branchId())
                .set(SALES_RETURNS.WAREHOUSE_ID, delivery.warehouseId())
                .set(SALES_RETURNS.RETURN_DATE, returnDate)
                .set(SALES_RETURNS.REASON, reason)
                .set(SALES_RETURNS.CREATED_BY, actor)
                .set(SALES_RETURNS.UPDATED_BY, actor)
                .returning(SALES_RETURNS.ID)
                .fetchOne(SALES_RETURNS.ID);
    }

    public void insertLines(UUID companyId, UUID returnId, List<NewLine> lines, UUID actor) {
        for (NewLine l : lines) {
            dsl.insertInto(SALES_RETURN_LINES)
                    .set(SALES_RETURN_LINES.COMPANY_ID, companyId)
                    .set(SALES_RETURN_LINES.SALES_RETURN_ID, returnId)
                    .set(SALES_RETURN_LINES.LINE_NO, l.lineNo())
                    .set(SALES_RETURN_LINES.DELIVERY_LINE_ID, l.deliveryLineId())
                    .set(SALES_RETURN_LINES.VARIANT_ID, l.variantId())
                    .set(SALES_RETURN_LINES.LOCATION_ID, l.locationId())
                    .set(SALES_RETURN_LINES.QUANTITY, l.quantity())
                    .set(SALES_RETURN_LINES.UOM_ID, l.uomId())
                    .set(SALES_RETURN_LINES.QUANTITY_BASE, l.quantityBase())
                    .set(SALES_RETURN_LINES.UNIT_COST_BASE, l.unitCostBase())
                    .set(SALES_RETURN_LINES.CREATED_BY, actor)
                    .set(SALES_RETURN_LINES.UPDATED_BY, actor)
                    .execute();
        }
    }

    public void deleteLines(UUID companyId, UUID returnId) {
        dsl.deleteFrom(SALES_RETURN_LINES)
                .where(SALES_RETURN_LINES.COMPANY_ID.eq(companyId))
                .and(SALES_RETURN_LINES.SALES_RETURN_ID.eq(returnId))
                .execute();
    }

    public Optional<SalesViews.SalesReturn> find(UUID companyId, UUID id) {
        return dsl.selectFrom(SALES_RETURNS)
                .where(SALES_RETURNS.COMPANY_ID.eq(companyId))
                .and(SALES_RETURNS.ID.eq(id))
                .fetchOptional(SalesReturnRepository::toView);
    }

    public Optional<SalesViews.SalesReturn> lock(UUID companyId, UUID id) {
        return dsl.selectFrom(SALES_RETURNS)
                .where(SALES_RETURNS.COMPANY_ID.eq(companyId))
                .and(SALES_RETURNS.ID.eq(id))
                .forUpdate()
                .fetchOptional(SalesReturnRepository::toView);
    }

    public PageResponse<SalesViews.SalesReturn> list(UUID companyId, @Nullable Set<UUID> branchScope, ListQuery query) {
        Condition scope = SALES_RETURNS
                .COMPANY_ID
                .eq(companyId)
                .and(branchScope == null ? DSL.noCondition() : SALES_RETURNS.BRANCH_ID.in(branchScope));
        return paginator.fetch(dsl, SALES_RETURNS, scope, query, BINDING, SalesReturnRepository::toView);
    }

    public List<SalesViews.SalesReturnLine> lines(UUID companyId, UUID returnId) {
        return dsl.selectFrom(SALES_RETURN_LINES)
                .where(SALES_RETURN_LINES.COMPANY_ID.eq(companyId))
                .and(SALES_RETURN_LINES.SALES_RETURN_ID.eq(returnId))
                .orderBy(SALES_RETURN_LINES.LINE_NO)
                .fetch(SalesReturnRepository::toLine);
    }

    public Map<UUID, LineOfReturn> linesById(UUID companyId, Collection<UUID> lineIds) {
        Map<UUID, LineOfReturn> result = new LinkedHashMap<>();
        if (lineIds.isEmpty()) {
            return result;
        }
        dsl.select()
                .from(SALES_RETURN_LINES)
                .join(SALES_RETURNS)
                .on(SALES_RETURNS.ID.eq(SALES_RETURN_LINES.SALES_RETURN_ID))
                .where(SALES_RETURN_LINES.COMPANY_ID.eq(companyId))
                .and(SALES_RETURN_LINES.ID.in(lineIds))
                .fetch()
                .forEach(r -> result.put(
                        r.get(SALES_RETURN_LINES.ID),
                        new LineOfReturn(toView(r.into(SALES_RETURNS)), toLine(r.into(SALES_RETURN_LINES)))));
        return result;
    }

    public record LineOfReturn(SalesViews.SalesReturn salesReturn, SalesViews.SalesReturnLine line) {}

    public boolean updateDraft(UUID companyId, UUID id, int version, UUID actor, LocalDate returnDate, String reason) {
        return dsl.update(SALES_RETURNS)
                        .set(SALES_RETURNS.RETURN_DATE, returnDate)
                        .set(SALES_RETURNS.REASON, reason)
                        .set(SALES_RETURNS.UPDATED_AT, OffsetDateTime.now())
                        .set(SALES_RETURNS.UPDATED_BY, actor)
                        .set(SALES_RETURNS.VERSION, version + 1)
                        .where(SALES_RETURNS.COMPANY_ID.eq(companyId))
                        .and(SALES_RETURNS.ID.eq(id))
                        .and(SALES_RETURNS.VERSION.eq(version))
                        .and(SALES_RETURNS.STATUS.eq("DRAFT"))
                        .execute()
                == 1;
    }

    public void setLinePosting(UUID companyId, UUID lineId, UUID locationId, BigDecimal valueBase) {
        dsl.update(SALES_RETURN_LINES)
                .set(SALES_RETURN_LINES.LOCATION_ID, locationId)
                .set(SALES_RETURN_LINES.VALUE_BASE, valueBase)
                .where(SALES_RETURN_LINES.COMPANY_ID.eq(companyId))
                .and(SALES_RETURN_LINES.ID.eq(lineId))
                .execute();
    }

    public boolean markReceived(UUID companyId, UUID id, int version, UUID actor, String number, UUID movementId) {
        return dsl.update(SALES_RETURNS)
                        .set(SALES_RETURNS.STATUS, "RECEIVED")
                        .set(SALES_RETURNS.NUMBER, number)
                        .set(SALES_RETURNS.STOCK_MOVEMENT_ID, movementId)
                        .set(SALES_RETURNS.RECEIVED_AT, OffsetDateTime.now())
                        .set(SALES_RETURNS.RECEIVED_BY, actor)
                        .set(SALES_RETURNS.UPDATED_AT, OffsetDateTime.now())
                        .set(SALES_RETURNS.UPDATED_BY, actor)
                        .set(SALES_RETURNS.VERSION, version + 1)
                        .where(SALES_RETURNS.COMPANY_ID.eq(companyId))
                        .and(SALES_RETURNS.ID.eq(id))
                        .and(SALES_RETURNS.VERSION.eq(version))
                        .and(SALES_RETURNS.STATUS.eq("DRAFT"))
                        .execute()
                == 1;
    }

    public boolean markCancelled(UUID companyId, UUID id, int version, UUID actor) {
        return dsl.update(SALES_RETURNS)
                        .set(SALES_RETURNS.STATUS, "CANCELLED")
                        .set(SALES_RETURNS.UPDATED_AT, OffsetDateTime.now())
                        .set(SALES_RETURNS.UPDATED_BY, actor)
                        .set(SALES_RETURNS.VERSION, version + 1)
                        .where(SALES_RETURNS.COMPANY_ID.eq(companyId))
                        .and(SALES_RETURNS.ID.eq(id))
                        .and(SALES_RETURNS.VERSION.eq(version))
                        .and(SALES_RETURNS.STATUS.eq("DRAFT"))
                        .execute()
                == 1;
    }

    public void updateCredited(UUID companyId, UUID lineId, BigDecimal credited) {
        dsl.update(SALES_RETURN_LINES)
                .set(SALES_RETURN_LINES.CREDITED_QUANTITY_BASE, credited)
                .set(SALES_RETURN_LINES.UPDATED_AT, OffsetDateTime.now())
                .where(SALES_RETURN_LINES.COMPANY_ID.eq(companyId))
                .and(SALES_RETURN_LINES.ID.eq(lineId))
                .execute();
    }

    public boolean usesBranch(UUID companyId, UUID branchId) {
        return dsl.fetchExists(dsl.selectOne()
                .from(SALES_RETURNS)
                .where(SALES_RETURNS.COMPANY_ID.eq(companyId))
                .and(SALES_RETURNS.BRANCH_ID.eq(branchId))
                .and(SALES_RETURNS.STATUS.eq("DRAFT")));
    }

    static SalesViews.SalesReturn toView(SalesReturnsRecord r) {
        return new SalesViews.SalesReturn(
                r.getId(),
                r.getCompanyId(),
                r.getNumber(),
                r.getCustomerId(),
                r.getSalesOrderId(),
                r.getDeliveryId(),
                r.getBranchId(),
                r.getWarehouseId(),
                r.getReturnDate(),
                r.getReason(),
                r.getStatus(),
                r.getStockMovementId(),
                r.getReceivedAt(),
                r.getCreatedAt(),
                r.getUpdatedAt(),
                r.getVersion());
    }

    static SalesViews.SalesReturnLine toLine(SalesReturnLinesRecord r) {
        return new SalesViews.SalesReturnLine(
                r.getId(),
                r.getSalesReturnId(),
                r.getLineNo(),
                r.getDeliveryLineId(),
                r.getVariantId(),
                r.getLocationId(),
                r.getQuantity(),
                r.getUomId(),
                r.getQuantityBase(),
                r.getUnitCostBase(),
                r.getValueBase(),
                r.getCreditedQuantityBase());
    }
}
