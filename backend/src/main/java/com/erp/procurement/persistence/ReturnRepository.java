package com.erp.procurement.persistence;

import static com.erp.db.procurement.Tables.PURCHASE_RETURNS;
import static com.erp.db.procurement.Tables.PURCHASE_RETURN_LINES;

import com.erp.db.procurement.tables.records.PurchaseReturnLinesRecord;
import com.erp.db.procurement.tables.records.PurchaseReturnsRecord;
import com.erp.platform.jooq.KeysetPaginator;
import com.erp.platform.jooq.ListBinding;
import com.erp.platform.web.paging.ListQuery;
import com.erp.platform.web.paging.PageResponse;
import com.erp.procurement.application.ProcurementListings;
import com.erp.procurement.application.ProcurementViews;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;

/** Purchase returns and their lines. */
@Repository
public class ReturnRepository {

    private static final ListBinding BINDING = ListBinding.builder(ProcurementListings.RETURNS)
            .field("returnDate", PURCHASE_RETURNS.RETURN_DATE)
            .field("createdAt", PURCHASE_RETURNS.CREATED_AT)
            .field("number", PURCHASE_RETURNS.NUMBER)
            .field("status", PURCHASE_RETURNS.STATUS)
            .field("goodsReceiptId", PURCHASE_RETURNS.GOODS_RECEIPT_ID)
            .field("purchaseOrderId", PURCHASE_RETURNS.PURCHASE_ORDER_ID)
            .field("supplierId", PURCHASE_RETURNS.SUPPLIER_ID)
            .tiebreaker(PURCHASE_RETURNS.ID)
            .search(List.of(PURCHASE_RETURNS.NUMBER, PURCHASE_RETURNS.REASON))
            .build();

    public record NewLine(
            int lineNo,
            UUID goodsReceiptLineId,
            UUID variantId,
            @Nullable UUID locationId,
            BigDecimal quantity,
            UUID uomId,
            BigDecimal quantityBase,
            BigDecimal unitCostBase) {}

    private final DSLContext dsl;
    private final KeysetPaginator paginator;

    public ReturnRepository(DSLContext dsl, KeysetPaginator paginator) {
        this.dsl = dsl;
        this.paginator = paginator;
    }

    public UUID insert(
            UUID companyId, ProcurementViews.GoodsReceipt receipt, LocalDate returnDate, String reason, UUID actor) {
        return dsl.insertInto(PURCHASE_RETURNS)
                .set(PURCHASE_RETURNS.COMPANY_ID, companyId)
                .set(PURCHASE_RETURNS.SUPPLIER_ID, receipt.supplierId())
                .set(PURCHASE_RETURNS.PURCHASE_ORDER_ID, receipt.purchaseOrderId())
                .set(PURCHASE_RETURNS.GOODS_RECEIPT_ID, receipt.id())
                .set(PURCHASE_RETURNS.BRANCH_ID, receipt.branchId())
                .set(PURCHASE_RETURNS.WAREHOUSE_ID, receipt.warehouseId())
                .set(PURCHASE_RETURNS.RETURN_DATE, returnDate)
                .set(PURCHASE_RETURNS.REASON, reason)
                .set(PURCHASE_RETURNS.CREATED_BY, actor)
                .set(PURCHASE_RETURNS.UPDATED_BY, actor)
                .returning(PURCHASE_RETURNS.ID)
                .fetchOne(PURCHASE_RETURNS.ID);
    }

    public void insertLines(UUID companyId, UUID returnId, List<NewLine> lines, UUID actor) {
        for (NewLine l : lines) {
            dsl.insertInto(PURCHASE_RETURN_LINES)
                    .set(PURCHASE_RETURN_LINES.COMPANY_ID, companyId)
                    .set(PURCHASE_RETURN_LINES.PURCHASE_RETURN_ID, returnId)
                    .set(PURCHASE_RETURN_LINES.LINE_NO, l.lineNo())
                    .set(PURCHASE_RETURN_LINES.GOODS_RECEIPT_LINE_ID, l.goodsReceiptLineId())
                    .set(PURCHASE_RETURN_LINES.VARIANT_ID, l.variantId())
                    .set(PURCHASE_RETURN_LINES.LOCATION_ID, l.locationId())
                    .set(PURCHASE_RETURN_LINES.QUANTITY, l.quantity())
                    .set(PURCHASE_RETURN_LINES.UOM_ID, l.uomId())
                    .set(PURCHASE_RETURN_LINES.QUANTITY_BASE, l.quantityBase())
                    .set(PURCHASE_RETURN_LINES.UNIT_COST_BASE, l.unitCostBase())
                    .set(PURCHASE_RETURN_LINES.CREATED_BY, actor)
                    .set(PURCHASE_RETURN_LINES.UPDATED_BY, actor)
                    .execute();
        }
    }

    public Optional<ProcurementViews.PurchaseReturn> find(UUID companyId, UUID id) {
        return dsl.selectFrom(PURCHASE_RETURNS)
                .where(PURCHASE_RETURNS.COMPANY_ID.eq(companyId))
                .and(PURCHASE_RETURNS.ID.eq(id))
                .fetchOptional(ReturnRepository::toView);
    }

    public Optional<ProcurementViews.PurchaseReturn> lock(UUID companyId, UUID id) {
        return dsl.selectFrom(PURCHASE_RETURNS)
                .where(PURCHASE_RETURNS.COMPANY_ID.eq(companyId))
                .and(PURCHASE_RETURNS.ID.eq(id))
                .forUpdate()
                .fetchOptional(ReturnRepository::toView);
    }

    public PageResponse<ProcurementViews.PurchaseReturn> list(
            UUID companyId, @Nullable Set<UUID> branchScope, ListQuery query) {
        Condition scope = PURCHASE_RETURNS
                .COMPANY_ID
                .eq(companyId)
                .and(branchScope == null ? DSL.noCondition() : PURCHASE_RETURNS.BRANCH_ID.in(branchScope));
        return paginator.fetch(dsl, PURCHASE_RETURNS, scope, query, BINDING, ReturnRepository::toView);
    }

    public List<ProcurementViews.PurchaseReturnLine> lines(UUID companyId, UUID returnId) {
        return dsl.selectFrom(PURCHASE_RETURN_LINES)
                .where(PURCHASE_RETURN_LINES.COMPANY_ID.eq(companyId))
                .and(PURCHASE_RETURN_LINES.PURCHASE_RETURN_ID.eq(returnId))
                .orderBy(PURCHASE_RETURN_LINES.LINE_NO)
                .fetch(ReturnRepository::toLine);
    }

    public void setLinePosting(UUID companyId, UUID lineId, UUID locationId, BigDecimal value) {
        dsl.update(PURCHASE_RETURN_LINES)
                .set(PURCHASE_RETURN_LINES.LOCATION_ID, locationId)
                .set(PURCHASE_RETURN_LINES.VALUE_BASE, value)
                .where(PURCHASE_RETURN_LINES.COMPANY_ID.eq(companyId))
                .and(PURCHASE_RETURN_LINES.ID.eq(lineId))
                .execute();
    }

    public boolean markPosted(UUID companyId, UUID id, int version, UUID actor, String number, UUID movementId) {
        return dsl.update(PURCHASE_RETURNS)
                        .set(PURCHASE_RETURNS.STATUS, "POSTED")
                        .set(PURCHASE_RETURNS.NUMBER, number)
                        .set(PURCHASE_RETURNS.STOCK_MOVEMENT_ID, movementId)
                        .set(PURCHASE_RETURNS.POSTED_AT, OffsetDateTime.now())
                        .set(PURCHASE_RETURNS.POSTED_BY, actor)
                        .set(PURCHASE_RETURNS.UPDATED_AT, OffsetDateTime.now())
                        .set(PURCHASE_RETURNS.UPDATED_BY, actor)
                        .set(PURCHASE_RETURNS.VERSION, version + 1)
                        .where(PURCHASE_RETURNS.COMPANY_ID.eq(companyId))
                        .and(PURCHASE_RETURNS.ID.eq(id))
                        .and(PURCHASE_RETURNS.VERSION.eq(version))
                        .and(PURCHASE_RETURNS.STATUS.eq("DRAFT"))
                        .execute()
                == 1;
    }

    public boolean markCancelled(UUID companyId, UUID id, int version, UUID actor) {
        return dsl.update(PURCHASE_RETURNS)
                        .set(PURCHASE_RETURNS.STATUS, "CANCELLED")
                        .set(PURCHASE_RETURNS.UPDATED_AT, OffsetDateTime.now())
                        .set(PURCHASE_RETURNS.UPDATED_BY, actor)
                        .set(PURCHASE_RETURNS.VERSION, version + 1)
                        .where(PURCHASE_RETURNS.COMPANY_ID.eq(companyId))
                        .and(PURCHASE_RETURNS.ID.eq(id))
                        .and(PURCHASE_RETURNS.VERSION.eq(version))
                        .and(PURCHASE_RETURNS.STATUS.eq("DRAFT"))
                        .execute()
                == 1;
    }

    public boolean usesBranch(UUID companyId, UUID branchId) {
        return dsl.fetchExists(dsl.selectOne()
                .from(PURCHASE_RETURNS)
                .where(PURCHASE_RETURNS.COMPANY_ID.eq(companyId))
                .and(PURCHASE_RETURNS.BRANCH_ID.eq(branchId))
                .and(PURCHASE_RETURNS.STATUS.eq("DRAFT")));
    }

    static ProcurementViews.PurchaseReturn toView(PurchaseReturnsRecord r) {
        return new ProcurementViews.PurchaseReturn(
                r.getId(),
                r.getCompanyId(),
                r.getNumber(),
                r.getSupplierId(),
                r.getPurchaseOrderId(),
                r.getGoodsReceiptId(),
                r.getBranchId(),
                r.getWarehouseId(),
                r.getReturnDate(),
                r.getStatus(),
                r.getReason(),
                r.getStockMovementId(),
                r.getPostedAt(),
                r.getPostedBy(),
                r.getCreatedAt(),
                r.getUpdatedAt(),
                r.getVersion());
    }

    static ProcurementViews.PurchaseReturnLine toLine(PurchaseReturnLinesRecord r) {
        return new ProcurementViews.PurchaseReturnLine(
                r.getId(),
                r.getLineNo(),
                r.getGoodsReceiptLineId(),
                r.getVariantId(),
                r.getLocationId(),
                r.getQuantity(),
                r.getUomId(),
                r.getQuantityBase(),
                r.getUnitCostBase(),
                r.getValueBase());
    }
}
