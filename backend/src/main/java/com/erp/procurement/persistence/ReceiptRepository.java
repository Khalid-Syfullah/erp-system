package com.erp.procurement.persistence;

import static com.erp.db.procurement.Tables.GOODS_RECEIPTS;
import static com.erp.db.procurement.Tables.GOODS_RECEIPT_LINES;

import com.erp.db.procurement.tables.records.GoodsReceiptLinesRecord;
import com.erp.db.procurement.tables.records.GoodsReceiptsRecord;
import com.erp.platform.jooq.KeysetPaginator;
import com.erp.platform.jooq.ListBinding;
import com.erp.platform.web.paging.ListQuery;
import com.erp.platform.web.paging.PageResponse;
import com.erp.procurement.application.ProcurementListings;
import com.erp.procurement.application.ProcurementViews;
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

/** Goods receipts and their lines (with the billing and return counters). */
@Repository
public class ReceiptRepository {

    private static final ListBinding BINDING = ListBinding.builder(ProcurementListings.RECEIPTS)
            .field("receiptDate", GOODS_RECEIPTS.RECEIPT_DATE)
            .field("createdAt", GOODS_RECEIPTS.CREATED_AT)
            .field("number", GOODS_RECEIPTS.NUMBER)
            .field("status", GOODS_RECEIPTS.STATUS)
            .field("purchaseOrderId", GOODS_RECEIPTS.PURCHASE_ORDER_ID)
            .field("supplierId", GOODS_RECEIPTS.SUPPLIER_ID)
            .field("warehouseId", GOODS_RECEIPTS.WAREHOUSE_ID)
            .tiebreaker(GOODS_RECEIPTS.ID)
            .search(List.of(GOODS_RECEIPTS.NUMBER, GOODS_RECEIPTS.SUPPLIER_DELIVERY_NOTE))
            .build();

    public record NewLine(
            int lineNo,
            UUID purchaseOrderLineId,
            UUID variantId,
            @Nullable UUID locationId,
            BigDecimal quantity,
            UUID uomId,
            BigDecimal quantityBase,
            BigDecimal unitCostDoc) {}

    private final DSLContext dsl;
    private final KeysetPaginator paginator;

    public ReceiptRepository(DSLContext dsl, KeysetPaginator paginator) {
        this.dsl = dsl;
        this.paginator = paginator;
    }

    public UUID insert(
            UUID companyId,
            ProcurementViews.PurchaseOrder order,
            LocalDate receiptDate,
            @Nullable String deliveryNote,
            @Nullable String notes,
            UUID actor) {
        return dsl.insertInto(GOODS_RECEIPTS)
                .set(GOODS_RECEIPTS.COMPANY_ID, companyId)
                .set(GOODS_RECEIPTS.PURCHASE_ORDER_ID, order.id())
                .set(GOODS_RECEIPTS.SUPPLIER_ID, order.supplierId())
                .set(GOODS_RECEIPTS.BRANCH_ID, order.branchId())
                .set(GOODS_RECEIPTS.WAREHOUSE_ID, order.warehouseId())
                .set(GOODS_RECEIPTS.CURRENCY_CODE, order.currencyCode())
                .set(GOODS_RECEIPTS.RECEIPT_DATE, receiptDate)
                .set(GOODS_RECEIPTS.SUPPLIER_DELIVERY_NOTE, deliveryNote)
                .set(GOODS_RECEIPTS.NOTES, notes)
                .set(GOODS_RECEIPTS.CREATED_BY, actor)
                .set(GOODS_RECEIPTS.UPDATED_BY, actor)
                .returning(GOODS_RECEIPTS.ID)
                .fetchOne(GOODS_RECEIPTS.ID);
    }

    public void insertLines(UUID companyId, UUID receiptId, List<NewLine> lines, UUID actor) {
        for (NewLine l : lines) {
            dsl.insertInto(GOODS_RECEIPT_LINES)
                    .set(GOODS_RECEIPT_LINES.COMPANY_ID, companyId)
                    .set(GOODS_RECEIPT_LINES.GOODS_RECEIPT_ID, receiptId)
                    .set(GOODS_RECEIPT_LINES.LINE_NO, l.lineNo())
                    .set(GOODS_RECEIPT_LINES.PURCHASE_ORDER_LINE_ID, l.purchaseOrderLineId())
                    .set(GOODS_RECEIPT_LINES.VARIANT_ID, l.variantId())
                    .set(GOODS_RECEIPT_LINES.LOCATION_ID, l.locationId())
                    .set(GOODS_RECEIPT_LINES.QUANTITY, l.quantity())
                    .set(GOODS_RECEIPT_LINES.UOM_ID, l.uomId())
                    .set(GOODS_RECEIPT_LINES.QUANTITY_BASE, l.quantityBase())
                    .set(GOODS_RECEIPT_LINES.UNIT_COST_DOC, l.unitCostDoc())
                    .set(GOODS_RECEIPT_LINES.CREATED_BY, actor)
                    .set(GOODS_RECEIPT_LINES.UPDATED_BY, actor)
                    .execute();
        }
    }

    public void deleteLines(UUID companyId, UUID receiptId) {
        dsl.deleteFrom(GOODS_RECEIPT_LINES)
                .where(GOODS_RECEIPT_LINES.COMPANY_ID.eq(companyId))
                .and(GOODS_RECEIPT_LINES.GOODS_RECEIPT_ID.eq(receiptId))
                .execute();
    }

    public Optional<ProcurementViews.GoodsReceipt> find(UUID companyId, UUID id) {
        return dsl.selectFrom(GOODS_RECEIPTS)
                .where(GOODS_RECEIPTS.COMPANY_ID.eq(companyId))
                .and(GOODS_RECEIPTS.ID.eq(id))
                .fetchOptional(ReceiptRepository::toView);
    }

    public Optional<ProcurementViews.GoodsReceipt> lock(UUID companyId, UUID id) {
        return dsl.selectFrom(GOODS_RECEIPTS)
                .where(GOODS_RECEIPTS.COMPANY_ID.eq(companyId))
                .and(GOODS_RECEIPTS.ID.eq(id))
                .forUpdate()
                .fetchOptional(ReceiptRepository::toView);
    }

    public PageResponse<ProcurementViews.GoodsReceipt> list(
            UUID companyId, @Nullable Set<UUID> branchScope, ListQuery query) {
        Condition scope = GOODS_RECEIPTS
                .COMPANY_ID
                .eq(companyId)
                .and(branchScope == null ? DSL.noCondition() : GOODS_RECEIPTS.BRANCH_ID.in(branchScope));
        return paginator.fetch(dsl, GOODS_RECEIPTS, scope, query, BINDING, ReceiptRepository::toView);
    }

    public List<ProcurementViews.GoodsReceiptLine> lines(UUID companyId, UUID receiptId) {
        return dsl.selectFrom(GOODS_RECEIPT_LINES)
                .where(GOODS_RECEIPT_LINES.COMPANY_ID.eq(companyId))
                .and(GOODS_RECEIPT_LINES.GOODS_RECEIPT_ID.eq(receiptId))
                .orderBy(GOODS_RECEIPT_LINES.LINE_NO)
                .fetch(ReceiptRepository::toLine);
    }

    /** Lines by ID with their receipt header. */
    public Map<UUID, LineOfReceipt> linesById(UUID companyId, Collection<UUID> lineIds) {
        Map<UUID, LineOfReceipt> result = new LinkedHashMap<>();
        if (lineIds.isEmpty()) {
            return result;
        }
        dsl.select()
                .from(GOODS_RECEIPT_LINES)
                .join(GOODS_RECEIPTS)
                .on(GOODS_RECEIPTS.ID.eq(GOODS_RECEIPT_LINES.GOODS_RECEIPT_ID))
                .where(GOODS_RECEIPT_LINES.COMPANY_ID.eq(companyId))
                .and(GOODS_RECEIPT_LINES.ID.in(lineIds))
                .fetch()
                .forEach(r -> result.put(
                        r.get(GOODS_RECEIPT_LINES.ID),
                        new LineOfReceipt(toView(r.into(GOODS_RECEIPTS)), toLine(r.into(GOODS_RECEIPT_LINES)))));
        return result;
    }

    public record LineOfReceipt(ProcurementViews.GoodsReceipt receipt, ProcurementViews.GoodsReceiptLine line) {}

    /** Posted receipt lines of the orders that still have quantity to bill. */
    public List<LineOfReceipt> unbilledLines(UUID companyId, Collection<UUID> receiptIds) {
        return dsl.select()
                .from(GOODS_RECEIPT_LINES)
                .join(GOODS_RECEIPTS)
                .on(GOODS_RECEIPTS.ID.eq(GOODS_RECEIPT_LINES.GOODS_RECEIPT_ID))
                .where(GOODS_RECEIPT_LINES.COMPANY_ID.eq(companyId))
                .and(GOODS_RECEIPTS.ID.in(receiptIds))
                .and(GOODS_RECEIPTS.STATUS.eq("POSTED"))
                .and(GOODS_RECEIPT_LINES
                        .BILLED_QUANTITY_BASE
                        .add(GOODS_RECEIPT_LINES.RETURNED_QUANTITY_BASE)
                        .sub(GOODS_RECEIPT_LINES.CREDITED_QUANTITY_BASE)
                        .lt(GOODS_RECEIPT_LINES.QUANTITY_BASE))
                .orderBy(GOODS_RECEIPTS.RECEIPT_DATE, GOODS_RECEIPTS.ID, GOODS_RECEIPT_LINES.LINE_NO)
                .fetch(r -> new LineOfReceipt(toView(r.into(GOODS_RECEIPTS)), toLine(r.into(GOODS_RECEIPT_LINES))));
    }

    public boolean updateDraft(
            UUID companyId,
            UUID id,
            int version,
            UUID actor,
            LocalDate receiptDate,
            @Nullable String deliveryNote,
            @Nullable String notes) {
        return dsl.update(GOODS_RECEIPTS)
                        .set(GOODS_RECEIPTS.RECEIPT_DATE, receiptDate)
                        .set(GOODS_RECEIPTS.SUPPLIER_DELIVERY_NOTE, deliveryNote)
                        .set(GOODS_RECEIPTS.NOTES, notes)
                        .set(GOODS_RECEIPTS.UPDATED_AT, OffsetDateTime.now())
                        .set(GOODS_RECEIPTS.UPDATED_BY, actor)
                        .set(GOODS_RECEIPTS.VERSION, version + 1)
                        .where(GOODS_RECEIPTS.COMPANY_ID.eq(companyId))
                        .and(GOODS_RECEIPTS.ID.eq(id))
                        .and(GOODS_RECEIPTS.VERSION.eq(version))
                        .and(GOODS_RECEIPTS.STATUS.eq("DRAFT"))
                        .execute()
                == 1;
    }

    /** Records where the stock went and its value (while the receipt is still a draft). */
    public void setLinePosting(
            UUID companyId, UUID lineId, UUID locationId, BigDecimal unitCostBase, BigDecimal value) {
        dsl.update(GOODS_RECEIPT_LINES)
                .set(GOODS_RECEIPT_LINES.LOCATION_ID, locationId)
                .set(GOODS_RECEIPT_LINES.UNIT_COST_BASE, unitCostBase)
                .set(GOODS_RECEIPT_LINES.VALUE_BASE, value)
                .where(GOODS_RECEIPT_LINES.COMPANY_ID.eq(companyId))
                .and(GOODS_RECEIPT_LINES.ID.eq(lineId))
                .execute();
    }

    public boolean markPosted(
            UUID companyId, UUID id, int version, UUID actor, String number, BigDecimal rate, UUID movementId) {
        return dsl.update(GOODS_RECEIPTS)
                        .set(GOODS_RECEIPTS.STATUS, "POSTED")
                        .set(GOODS_RECEIPTS.NUMBER, number)
                        .set(GOODS_RECEIPTS.EXCHANGE_RATE, rate)
                        .set(GOODS_RECEIPTS.STOCK_MOVEMENT_ID, movementId)
                        .set(GOODS_RECEIPTS.POSTED_AT, OffsetDateTime.now())
                        .set(GOODS_RECEIPTS.POSTED_BY, actor)
                        .set(GOODS_RECEIPTS.UPDATED_AT, OffsetDateTime.now())
                        .set(GOODS_RECEIPTS.UPDATED_BY, actor)
                        .set(GOODS_RECEIPTS.VERSION, version + 1)
                        .where(GOODS_RECEIPTS.COMPANY_ID.eq(companyId))
                        .and(GOODS_RECEIPTS.ID.eq(id))
                        .and(GOODS_RECEIPTS.VERSION.eq(version))
                        .and(GOODS_RECEIPTS.STATUS.eq("DRAFT"))
                        .execute()
                == 1;
    }

    public boolean markCancelled(UUID companyId, UUID id, int version, UUID actor) {
        return dsl.update(GOODS_RECEIPTS)
                        .set(GOODS_RECEIPTS.STATUS, "CANCELLED")
                        .set(GOODS_RECEIPTS.UPDATED_AT, OffsetDateTime.now())
                        .set(GOODS_RECEIPTS.UPDATED_BY, actor)
                        .set(GOODS_RECEIPTS.VERSION, version + 1)
                        .where(GOODS_RECEIPTS.COMPANY_ID.eq(companyId))
                        .and(GOODS_RECEIPTS.ID.eq(id))
                        .and(GOODS_RECEIPTS.VERSION.eq(version))
                        .and(GOODS_RECEIPTS.STATUS.eq("DRAFT"))
                        .execute()
                == 1;
    }

    public boolean delete(UUID companyId, UUID id, int version) {
        return dsl.deleteFrom(GOODS_RECEIPTS)
                        .where(GOODS_RECEIPTS.COMPANY_ID.eq(companyId))
                        .and(GOODS_RECEIPTS.ID.eq(id))
                        .and(GOODS_RECEIPTS.VERSION.eq(version))
                        .and(GOODS_RECEIPTS.STATUS.eq("DRAFT"))
                        .execute()
                == 1;
    }

    public void updateCounters(
            UUID companyId,
            UUID lineId,
            BigDecimal billedQty,
            BigDecimal billedValue,
            BigDecimal returnedQty,
            BigDecimal returnedValue,
            BigDecimal creditedQty,
            BigDecimal creditedValue) {
        dsl.update(GOODS_RECEIPT_LINES)
                .set(GOODS_RECEIPT_LINES.BILLED_QUANTITY_BASE, billedQty)
                .set(GOODS_RECEIPT_LINES.BILLED_VALUE_BASE, billedValue)
                .set(GOODS_RECEIPT_LINES.RETURNED_QUANTITY_BASE, returnedQty)
                .set(GOODS_RECEIPT_LINES.RETURNED_VALUE_BASE, returnedValue)
                .set(GOODS_RECEIPT_LINES.CREDITED_QUANTITY_BASE, creditedQty)
                .set(GOODS_RECEIPT_LINES.CREDITED_VALUE_BASE, creditedValue)
                .set(GOODS_RECEIPT_LINES.UPDATED_AT, OffsetDateTime.now())
                .where(GOODS_RECEIPT_LINES.COMPANY_ID.eq(companyId))
                .and(GOODS_RECEIPT_LINES.ID.eq(lineId))
                .execute();
    }

    /**
     * Draft receipts of the order (cancelling the order cancels them), locked {@code NOWAIT}: a draft being posted
     * holds its row and waits for the order the caller has locked, so waiting here would deadlock
     * (DATABASE.md §9). The caller fails at once with {@code 409 RESOURCE_BUSY}; the posting goes ahead.
     */
    public List<ProcurementViews.GoodsReceipt> draftsOfOrder(UUID companyId, UUID orderId) {
        return dsl.selectFrom(GOODS_RECEIPTS)
                .where(GOODS_RECEIPTS.COMPANY_ID.eq(companyId))
                .and(GOODS_RECEIPTS.PURCHASE_ORDER_ID.eq(orderId))
                .and(GOODS_RECEIPTS.STATUS.eq("DRAFT"))
                .orderBy(GOODS_RECEIPTS.ID)
                .forUpdate()
                .noWait()
                .fetch(ReceiptRepository::toView);
    }

    public boolean usesBranch(UUID companyId, UUID branchId) {
        return dsl.fetchExists(dsl.selectOne()
                .from(GOODS_RECEIPTS)
                .where(GOODS_RECEIPTS.COMPANY_ID.eq(companyId))
                .and(GOODS_RECEIPTS.BRANCH_ID.eq(branchId))
                .and(GOODS_RECEIPTS.STATUS.eq("DRAFT")));
    }

    static ProcurementViews.GoodsReceipt toView(GoodsReceiptsRecord r) {
        return new ProcurementViews.GoodsReceipt(
                r.getId(),
                r.getCompanyId(),
                r.getNumber(),
                r.getPurchaseOrderId(),
                r.getSupplierId(),
                r.getBranchId(),
                r.getWarehouseId(),
                r.getReceiptDate(),
                r.getStatus(),
                r.getCurrencyCode(),
                r.getExchangeRate(),
                r.getStockMovementId(),
                r.getSupplierDeliveryNote(),
                r.getNotes(),
                r.getPostedAt(),
                r.getPostedBy(),
                r.getCreatedAt(),
                r.getUpdatedAt(),
                r.getVersion());
    }

    static ProcurementViews.GoodsReceiptLine toLine(GoodsReceiptLinesRecord r) {
        return new ProcurementViews.GoodsReceiptLine(
                r.getId(),
                r.getGoodsReceiptId(),
                r.getLineNo(),
                r.getPurchaseOrderLineId(),
                r.getVariantId(),
                r.getLocationId(),
                r.getQuantity(),
                r.getUomId(),
                r.getQuantityBase(),
                r.getUnitCostDoc(),
                r.getUnitCostBase(),
                r.getValueBase(),
                r.getBilledQuantityBase(),
                r.getBilledValueBase(),
                r.getReturnedQuantityBase(),
                r.getReturnedValueBase(),
                r.getCreditedQuantityBase(),
                r.getCreditedValueBase());
    }
}
