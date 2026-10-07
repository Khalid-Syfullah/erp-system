package com.erp.sales.persistence;

import static com.erp.db.sales.Tables.DELIVERIES;
import static com.erp.db.sales.Tables.DELIVERY_LINES;

import com.erp.db.sales.tables.records.DeliveriesRecord;
import com.erp.db.sales.tables.records.DeliveryLinesRecord;
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

/** Deliveries and their lines (with the returned counter). */
@Repository
public class DeliveryRepository {

    private static final ListBinding BINDING = ListBinding.builder(SalesListings.DELIVERIES)
            .field("deliveryDate", DELIVERIES.DELIVERY_DATE)
            .field("createdAt", DELIVERIES.CREATED_AT)
            .field("number", DELIVERIES.NUMBER)
            .field("status", DELIVERIES.STATUS)
            .field("salesOrderId", DELIVERIES.SALES_ORDER_ID)
            .field("customerId", DELIVERIES.CUSTOMER_ID)
            .field("warehouseId", DELIVERIES.WAREHOUSE_ID)
            .tiebreaker(DELIVERIES.ID)
            .search(List.of(DELIVERIES.NUMBER, DELIVERIES.TRACKING_NUMBER))
            .build();

    public record Header(
            LocalDate deliveryDate,
            @Nullable String carrier,
            @Nullable String trackingNumber,
            @Nullable String notes) {}

    public record NewLine(
            int lineNo,
            UUID salesOrderLineId,
            UUID variantId,
            @Nullable UUID locationId,
            BigDecimal quantity,
            UUID uomId,
            BigDecimal quantityBase) {}

    private final DSLContext dsl;
    private final KeysetPaginator paginator;
    private final AddressJson addresses;

    public DeliveryRepository(DSLContext dsl, KeysetPaginator paginator, AddressJson addresses) {
        this.dsl = dsl;
        this.paginator = paginator;
        this.addresses = addresses;
    }

    public UUID insert(UUID companyId, SalesViews.SalesOrder order, Header h, UUID actor) {
        return dsl.insertInto(DELIVERIES)
                .set(DELIVERIES.COMPANY_ID, companyId)
                .set(DELIVERIES.SALES_ORDER_ID, order.id())
                .set(DELIVERIES.CUSTOMER_ID, order.customerId())
                .set(DELIVERIES.BRANCH_ID, order.branchId())
                .set(DELIVERIES.WAREHOUSE_ID, order.warehouseId())
                .set(DELIVERIES.SHIPPING_ADDRESS, addresses.write(order.shippingAddress()))
                .set(DELIVERIES.DELIVERY_DATE, h.deliveryDate())
                .set(DELIVERIES.CARRIER, h.carrier())
                .set(DELIVERIES.TRACKING_NUMBER, h.trackingNumber())
                .set(DELIVERIES.NOTES, h.notes())
                .set(DELIVERIES.CREATED_BY, actor)
                .set(DELIVERIES.UPDATED_BY, actor)
                .returning(DELIVERIES.ID)
                .fetchOne(DELIVERIES.ID);
    }

    public void insertLines(UUID companyId, UUID deliveryId, List<NewLine> lines, UUID actor) {
        for (NewLine l : lines) {
            dsl.insertInto(DELIVERY_LINES)
                    .set(DELIVERY_LINES.COMPANY_ID, companyId)
                    .set(DELIVERY_LINES.DELIVERY_ID, deliveryId)
                    .set(DELIVERY_LINES.LINE_NO, l.lineNo())
                    .set(DELIVERY_LINES.SALES_ORDER_LINE_ID, l.salesOrderLineId())
                    .set(DELIVERY_LINES.VARIANT_ID, l.variantId())
                    .set(DELIVERY_LINES.LOCATION_ID, l.locationId())
                    .set(DELIVERY_LINES.QUANTITY, l.quantity())
                    .set(DELIVERY_LINES.UOM_ID, l.uomId())
                    .set(DELIVERY_LINES.QUANTITY_BASE, l.quantityBase())
                    .set(DELIVERY_LINES.CREATED_BY, actor)
                    .set(DELIVERY_LINES.UPDATED_BY, actor)
                    .execute();
        }
    }

    public void deleteLines(UUID companyId, UUID deliveryId) {
        dsl.deleteFrom(DELIVERY_LINES)
                .where(DELIVERY_LINES.COMPANY_ID.eq(companyId))
                .and(DELIVERY_LINES.DELIVERY_ID.eq(deliveryId))
                .execute();
    }

    public Optional<SalesViews.Delivery> find(UUID companyId, UUID id) {
        return dsl.selectFrom(DELIVERIES)
                .where(DELIVERIES.COMPANY_ID.eq(companyId))
                .and(DELIVERIES.ID.eq(id))
                .fetchOptional(this::toView);
    }

    public Optional<SalesViews.Delivery> lock(UUID companyId, UUID id) {
        return dsl.selectFrom(DELIVERIES)
                .where(DELIVERIES.COMPANY_ID.eq(companyId))
                .and(DELIVERIES.ID.eq(id))
                .forUpdate()
                .fetchOptional(this::toView);
    }

    public PageResponse<SalesViews.Delivery> list(UUID companyId, @Nullable Set<UUID> branchScope, ListQuery query) {
        Condition scope = DELIVERIES
                .COMPANY_ID
                .eq(companyId)
                .and(branchScope == null ? DSL.noCondition() : DELIVERIES.BRANCH_ID.in(branchScope));
        return paginator.fetch(dsl, DELIVERIES, scope, query, BINDING, this::toView);
    }

    public List<SalesViews.DeliveryLine> lines(UUID companyId, UUID deliveryId) {
        return dsl.selectFrom(DELIVERY_LINES)
                .where(DELIVERY_LINES.COMPANY_ID.eq(companyId))
                .and(DELIVERY_LINES.DELIVERY_ID.eq(deliveryId))
                .orderBy(DELIVERY_LINES.LINE_NO)
                .fetch(DeliveryRepository::toLine);
    }

    /** Lines by ID with their delivery header. */
    public Map<UUID, LineOfDelivery> linesById(UUID companyId, Collection<UUID> lineIds) {
        Map<UUID, LineOfDelivery> result = new LinkedHashMap<>();
        if (lineIds.isEmpty()) {
            return result;
        }
        dsl.select()
                .from(DELIVERY_LINES)
                .join(DELIVERIES)
                .on(DELIVERIES.ID.eq(DELIVERY_LINES.DELIVERY_ID))
                .where(DELIVERY_LINES.COMPANY_ID.eq(companyId))
                .and(DELIVERY_LINES.ID.in(lineIds))
                .fetch()
                .forEach(r -> result.put(
                        r.get(DELIVERY_LINES.ID),
                        new LineOfDelivery(toView(r.into(DELIVERIES)), toLine(r.into(DELIVERY_LINES)))));
        return result;
    }

    public record LineOfDelivery(SalesViews.Delivery delivery, SalesViews.DeliveryLine line) {}

    public boolean updateDraft(UUID companyId, UUID id, int version, UUID actor, Header h) {
        return dsl.update(DELIVERIES)
                        .set(DELIVERIES.DELIVERY_DATE, h.deliveryDate())
                        .set(DELIVERIES.CARRIER, h.carrier())
                        .set(DELIVERIES.TRACKING_NUMBER, h.trackingNumber())
                        .set(DELIVERIES.NOTES, h.notes())
                        .set(DELIVERIES.UPDATED_AT, OffsetDateTime.now())
                        .set(DELIVERIES.UPDATED_BY, actor)
                        .set(DELIVERIES.VERSION, version + 1)
                        .where(DELIVERIES.COMPANY_ID.eq(companyId))
                        .and(DELIVERIES.ID.eq(id))
                        .and(DELIVERIES.VERSION.eq(version))
                        .and(DELIVERIES.STATUS.eq("DRAFT"))
                        .execute()
                == 1;
    }

    /** Records where the stock came from and its cost (while the delivery is still a draft). */
    public void setLinePosting(
            UUID companyId, UUID lineId, UUID locationId, BigDecimal unitCostBase, BigDecimal valueBase) {
        dsl.update(DELIVERY_LINES)
                .set(DELIVERY_LINES.LOCATION_ID, locationId)
                .set(DELIVERY_LINES.UNIT_COST_BASE, unitCostBase)
                .set(DELIVERY_LINES.VALUE_BASE, valueBase)
                .where(DELIVERY_LINES.COMPANY_ID.eq(companyId))
                .and(DELIVERY_LINES.ID.eq(lineId))
                .execute();
    }

    public boolean markPosted(UUID companyId, UUID id, int version, UUID actor, String number, UUID movementId) {
        return dsl.update(DELIVERIES)
                        .set(DELIVERIES.STATUS, "POSTED")
                        .set(DELIVERIES.NUMBER, number)
                        .set(DELIVERIES.STOCK_MOVEMENT_ID, movementId)
                        .set(DELIVERIES.POSTED_AT, OffsetDateTime.now())
                        .set(DELIVERIES.POSTED_BY, actor)
                        .set(DELIVERIES.UPDATED_AT, OffsetDateTime.now())
                        .set(DELIVERIES.UPDATED_BY, actor)
                        .set(DELIVERIES.VERSION, version + 1)
                        .where(DELIVERIES.COMPANY_ID.eq(companyId))
                        .and(DELIVERIES.ID.eq(id))
                        .and(DELIVERIES.VERSION.eq(version))
                        .and(DELIVERIES.STATUS.eq("DRAFT"))
                        .execute()
                == 1;
    }

    public boolean markCancelled(UUID companyId, UUID id, int version, UUID actor) {
        return dsl.update(DELIVERIES)
                        .set(DELIVERIES.STATUS, "CANCELLED")
                        .set(DELIVERIES.UPDATED_AT, OffsetDateTime.now())
                        .set(DELIVERIES.UPDATED_BY, actor)
                        .set(DELIVERIES.VERSION, version + 1)
                        .where(DELIVERIES.COMPANY_ID.eq(companyId))
                        .and(DELIVERIES.ID.eq(id))
                        .and(DELIVERIES.VERSION.eq(version))
                        .and(DELIVERIES.STATUS.eq("DRAFT"))
                        .execute()
                == 1;
    }

    public boolean delete(UUID companyId, UUID id, int version) {
        return dsl.deleteFrom(DELIVERIES)
                        .where(DELIVERIES.COMPANY_ID.eq(companyId))
                        .and(DELIVERIES.ID.eq(id))
                        .and(DELIVERIES.VERSION.eq(version))
                        .and(DELIVERIES.STATUS.eq("DRAFT"))
                        .execute()
                == 1;
    }

    public void updateReturned(UUID companyId, UUID lineId, BigDecimal returned) {
        dsl.update(DELIVERY_LINES)
                .set(DELIVERY_LINES.RETURNED_QUANTITY_BASE, returned)
                .set(DELIVERY_LINES.UPDATED_AT, OffsetDateTime.now())
                .where(DELIVERY_LINES.COMPANY_ID.eq(companyId))
                .and(DELIVERY_LINES.ID.eq(lineId))
                .execute();
    }

    /**
     * Draft deliveries of the order (cancelling or closing the order cancels them), locked {@code NOWAIT}: a draft being posted
     * holds its row and waits for the order the caller has locked, so waiting here would deadlock
     * (DATABASE.md §9). The caller fails at once with {@code 409 RESOURCE_BUSY}; the posting goes ahead.
     */
    public List<SalesViews.Delivery> draftsOfOrder(UUID companyId, UUID orderId) {
        return dsl.selectFrom(DELIVERIES)
                .where(DELIVERIES.COMPANY_ID.eq(companyId))
                .and(DELIVERIES.SALES_ORDER_ID.eq(orderId))
                .and(DELIVERIES.STATUS.eq("DRAFT"))
                .orderBy(DELIVERIES.ID)
                .forUpdate()
                .noWait()
                .fetch(this::toView);
    }

    public boolean usesBranch(UUID companyId, UUID branchId) {
        return dsl.fetchExists(dsl.selectOne()
                .from(DELIVERIES)
                .where(DELIVERIES.COMPANY_ID.eq(companyId))
                .and(DELIVERIES.BRANCH_ID.eq(branchId))
                .and(DELIVERIES.STATUS.eq("DRAFT")));
    }

    SalesViews.Delivery toView(DeliveriesRecord r) {
        return new SalesViews.Delivery(
                r.getId(),
                r.getCompanyId(),
                r.getNumber(),
                r.getSalesOrderId(),
                r.getCustomerId(),
                r.getBranchId(),
                r.getWarehouseId(),
                r.getDeliveryDate(),
                r.getStatus(),
                addresses.read(r.getShippingAddress()),
                r.getCarrier(),
                r.getTrackingNumber(),
                r.getStockMovementId(),
                r.getNotes(),
                r.getPostedAt(),
                r.getCreatedAt(),
                r.getUpdatedAt(),
                r.getVersion());
    }

    static SalesViews.DeliveryLine toLine(DeliveryLinesRecord r) {
        return new SalesViews.DeliveryLine(
                r.getId(),
                r.getDeliveryId(),
                r.getLineNo(),
                r.getSalesOrderLineId(),
                r.getVariantId(),
                r.getLocationId(),
                r.getQuantity(),
                r.getUomId(),
                r.getQuantityBase(),
                r.getUnitCostBase(),
                r.getValueBase(),
                r.getReturnedQuantityBase());
    }
}
