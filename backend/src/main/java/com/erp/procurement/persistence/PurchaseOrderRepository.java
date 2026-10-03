package com.erp.procurement.persistence;

import static com.erp.db.procurement.Tables.PURCHASE_ORDERS;
import static com.erp.db.procurement.Tables.PURCHASE_ORDER_LINES;

import com.erp.db.procurement.tables.records.PurchaseOrderLinesRecord;
import com.erp.db.procurement.tables.records.PurchaseOrdersRecord;
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

/** Purchase orders and their lines. The header row is the lock that serializes fulfilment (§9). */
@Repository
public class PurchaseOrderRepository {

    private static final ListBinding BINDING = ListBinding.builder(ProcurementListings.PURCHASE_ORDERS)
            .field("orderDate", PURCHASE_ORDERS.ORDER_DATE)
            .field("createdAt", PURCHASE_ORDERS.CREATED_AT)
            .field("number", PURCHASE_ORDERS.NUMBER)
            .field("total", PURCHASE_ORDERS.TOTAL)
            .field("status", PURCHASE_ORDERS.STATUS)
            .field("billingStatus", PURCHASE_ORDERS.BILLING_STATUS)
            .field("supplierId", PURCHASE_ORDERS.SUPPLIER_ID)
            .field("warehouseId", PURCHASE_ORDERS.WAREHOUSE_ID)
            .field("branchId", PURCHASE_ORDERS.BRANCH_ID)
            .tiebreaker(PURCHASE_ORDERS.ID)
            .search(List.of(PURCHASE_ORDERS.NUMBER, PURCHASE_ORDERS.NOTES))
            .build();

    /** Header values a draft sets (the totals are computed by the service). */
    public record Header(
            UUID supplierId,
            UUID branchId,
            UUID warehouseId,
            @Nullable UUID departmentId,
            LocalDate orderDate,
            @Nullable LocalDate expectedDate,
            String currencyCode,
            @Nullable UUID paymentTermsId,
            boolean pricesIncludeTax,
            BigDecimal subtotal,
            BigDecimal taxTotal,
            BigDecimal total,
            @Nullable String notes) {}

    /** A priced line to store. */
    public record NewLine(
            int lineNo,
            UUID variantId,
            String description,
            boolean stockable,
            BigDecimal quantity,
            UUID uomId,
            BigDecimal quantityBase,
            BigDecimal unitPrice,
            BigDecimal discountPercent,
            @Nullable UUID taxCodeId,
            BigDecimal netAmount,
            BigDecimal taxAmount,
            BigDecimal totalAmount,
            @Nullable UUID requisitionLineId) {}

    private final DSLContext dsl;
    private final KeysetPaginator paginator;

    public PurchaseOrderRepository(DSLContext dsl, KeysetPaginator paginator) {
        this.dsl = dsl;
        this.paginator = paginator;
    }

    public UUID insert(UUID companyId, Header h, UUID actor) {
        return dsl.insertInto(PURCHASE_ORDERS)
                .set(PURCHASE_ORDERS.COMPANY_ID, companyId)
                .set(header(h))
                .set(PURCHASE_ORDERS.CREATED_BY, actor)
                .set(PURCHASE_ORDERS.UPDATED_BY, actor)
                .returning(PURCHASE_ORDERS.ID)
                .fetchOne(PURCHASE_ORDERS.ID);
    }

    public void insertLines(UUID companyId, UUID orderId, List<NewLine> lines, UUID actor) {
        for (NewLine l : lines) {
            dsl.insertInto(PURCHASE_ORDER_LINES)
                    .set(PURCHASE_ORDER_LINES.COMPANY_ID, companyId)
                    .set(PURCHASE_ORDER_LINES.PURCHASE_ORDER_ID, orderId)
                    .set(PURCHASE_ORDER_LINES.LINE_NO, l.lineNo())
                    .set(PURCHASE_ORDER_LINES.VARIANT_ID, l.variantId())
                    .set(PURCHASE_ORDER_LINES.DESCRIPTION, l.description())
                    .set(PURCHASE_ORDER_LINES.IS_STOCKABLE, l.stockable())
                    .set(PURCHASE_ORDER_LINES.QUANTITY, l.quantity())
                    .set(PURCHASE_ORDER_LINES.UOM_ID, l.uomId())
                    .set(PURCHASE_ORDER_LINES.QUANTITY_BASE, l.quantityBase())
                    .set(PURCHASE_ORDER_LINES.UNIT_PRICE, l.unitPrice())
                    .set(PURCHASE_ORDER_LINES.DISCOUNT_PERCENT, l.discountPercent())
                    .set(PURCHASE_ORDER_LINES.TAX_CODE_ID, l.taxCodeId())
                    .set(PURCHASE_ORDER_LINES.NET_AMOUNT, l.netAmount())
                    .set(PURCHASE_ORDER_LINES.TAX_AMOUNT, l.taxAmount())
                    .set(PURCHASE_ORDER_LINES.TOTAL_AMOUNT, l.totalAmount())
                    .set(PURCHASE_ORDER_LINES.REQUISITION_LINE_ID, l.requisitionLineId())
                    .set(PURCHASE_ORDER_LINES.CREATED_BY, actor)
                    .set(PURCHASE_ORDER_LINES.UPDATED_BY, actor)
                    .execute();
        }
    }

    public void deleteLines(UUID companyId, UUID orderId) {
        dsl.deleteFrom(PURCHASE_ORDER_LINES)
                .where(PURCHASE_ORDER_LINES.COMPANY_ID.eq(companyId))
                .and(PURCHASE_ORDER_LINES.PURCHASE_ORDER_ID.eq(orderId))
                .execute();
    }

    public Optional<ProcurementViews.PurchaseOrder> find(UUID companyId, UUID id) {
        return dsl.selectFrom(PURCHASE_ORDERS)
                .where(PURCHASE_ORDERS.COMPANY_ID.eq(companyId))
                .and(PURCHASE_ORDERS.ID.eq(id))
                .fetchOptional(PurchaseOrderRepository::toView);
    }

    /** The header {@code FOR UPDATE}: every change and every fulfilment of the order serializes here. */
    public Optional<ProcurementViews.PurchaseOrder> lock(UUID companyId, UUID id) {
        return dsl.selectFrom(PURCHASE_ORDERS)
                .where(PURCHASE_ORDERS.COMPANY_ID.eq(companyId))
                .and(PURCHASE_ORDERS.ID.eq(id))
                .forUpdate()
                .fetchOptional(PurchaseOrderRepository::toView);
    }

    /** Several headers {@code FOR UPDATE} in ID order (bills spanning orders). */
    public Map<UUID, ProcurementViews.PurchaseOrder> lockAll(UUID companyId, Collection<UUID> ids) {
        Map<UUID, ProcurementViews.PurchaseOrder> result = new LinkedHashMap<>();
        if (ids.isEmpty()) {
            return result;
        }
        dsl.selectFrom(PURCHASE_ORDERS)
                .where(PURCHASE_ORDERS.COMPANY_ID.eq(companyId))
                .and(PURCHASE_ORDERS.ID.in(ids))
                .orderBy(PURCHASE_ORDERS.ID)
                .forUpdate()
                .fetch(PurchaseOrderRepository::toView)
                .forEach(o -> result.put(o.id(), o));
        return result;
    }

    public PageResponse<ProcurementViews.PurchaseOrder> list(
            UUID companyId, @Nullable Set<UUID> branchScope, ListQuery query) {
        Condition scope = PURCHASE_ORDERS
                .COMPANY_ID
                .eq(companyId)
                .and(branchScope == null ? DSL.noCondition() : PURCHASE_ORDERS.BRANCH_ID.in(branchScope));
        return paginator.fetch(dsl, PURCHASE_ORDERS, scope, query, BINDING, PurchaseOrderRepository::toView);
    }

    public List<ProcurementViews.PurchaseOrderLine> lines(UUID companyId, UUID orderId) {
        return dsl.selectFrom(PURCHASE_ORDER_LINES)
                .where(PURCHASE_ORDER_LINES.COMPANY_ID.eq(companyId))
                .and(PURCHASE_ORDER_LINES.PURCHASE_ORDER_ID.eq(orderId))
                .orderBy(PURCHASE_ORDER_LINES.LINE_NO)
                .fetch(PurchaseOrderRepository::toLine);
    }

    /** Lines by ID with their order ID. */
    public Map<UUID, LineOfOrder> linesById(UUID companyId, Collection<UUID> lineIds) {
        Map<UUID, LineOfOrder> result = new LinkedHashMap<>();
        if (lineIds.isEmpty()) {
            return result;
        }
        dsl.selectFrom(PURCHASE_ORDER_LINES)
                .where(PURCHASE_ORDER_LINES.COMPANY_ID.eq(companyId))
                .and(PURCHASE_ORDER_LINES.ID.in(lineIds))
                .fetch()
                .forEach(r -> result.put(r.getId(), new LineOfOrder(r.getPurchaseOrderId(), toLine(r))));
        return result;
    }

    public record LineOfOrder(UUID purchaseOrderId, ProcurementViews.PurchaseOrderLine line) {}

    public boolean updateDraft(UUID companyId, UUID id, int version, UUID actor, Header h) {
        return dsl.update(PURCHASE_ORDERS)
                        .set(header(h))
                        .set(PURCHASE_ORDERS.UPDATED_AT, OffsetDateTime.now())
                        .set(PURCHASE_ORDERS.UPDATED_BY, actor)
                        .set(PURCHASE_ORDERS.VERSION, version + 1)
                        .where(PURCHASE_ORDERS.COMPANY_ID.eq(companyId))
                        .and(PURCHASE_ORDERS.ID.eq(id))
                        .and(PURCHASE_ORDERS.VERSION.eq(version))
                        .and(PURCHASE_ORDERS.STATUS.eq("DRAFT"))
                        .execute()
                == 1;
    }

    /** State change with the fields that go with it (null leaves a field unchanged). */
    public boolean transition(UUID companyId, UUID id, int version, UUID actor, String status, Transition t) {
        var update = dsl.update(PURCHASE_ORDERS)
                .set(PURCHASE_ORDERS.STATUS, status)
                .set(PURCHASE_ORDERS.UPDATED_AT, OffsetDateTime.now())
                .set(PURCHASE_ORDERS.UPDATED_BY, actor)
                .set(PURCHASE_ORDERS.VERSION, version + 1);
        if (t.number() != null) {
            update = update.set(PURCHASE_ORDERS.NUMBER, t.number());
        }
        if (t.submitted()) {
            update = update.set(PURCHASE_ORDERS.SUBMITTED_BY, actor)
                    .set(PURCHASE_ORDERS.SUBMITTED_AT, OffsetDateTime.now())
                    .set(PURCHASE_ORDERS.REJECTION_REASON, (String) null);
        }
        if (t.approved()) {
            update = update.set(PURCHASE_ORDERS.APPROVED_BY, actor)
                    .set(PURCHASE_ORDERS.APPROVED_AT, OffsetDateTime.now());
        }
        if (t.rejectionReason() != null) {
            update = update.set(PURCHASE_ORDERS.REJECTION_REASON, t.rejectionReason());
        }
        if (t.cancelReason() != null) {
            update = update.set(PURCHASE_ORDERS.CANCEL_REASON, t.cancelReason());
        }
        if (t.closeReason() != null) {
            update = update.set(PURCHASE_ORDERS.CLOSE_REASON, t.closeReason());
        }
        if (t.billingStatus() != null) {
            update = update.set(PURCHASE_ORDERS.BILLING_STATUS, t.billingStatus());
        }
        return update.where(PURCHASE_ORDERS.COMPANY_ID.eq(companyId))
                        .and(PURCHASE_ORDERS.ID.eq(id))
                        .and(PURCHASE_ORDERS.VERSION.eq(version))
                        .execute()
                == 1;
    }

    /** Optional fields of a {@link #transition}. */
    public record Transition(
            @Nullable String number,
            boolean submitted,
            boolean approved,
            @Nullable String rejectionReason,
            @Nullable String cancelReason,
            @Nullable String closeReason,
            @Nullable String billingStatus) {

        public static final Transition NONE = new Transition(null, false, false, null, null, null, null);
    }

    public void updateLineCounters(
            UUID companyId, UUID lineId, BigDecimal received, BigDecimal returned, BigDecimal billed) {
        dsl.update(PURCHASE_ORDER_LINES)
                .set(PURCHASE_ORDER_LINES.RECEIVED_QUANTITY_BASE, received)
                .set(PURCHASE_ORDER_LINES.RETURNED_QUANTITY_BASE, returned)
                .set(PURCHASE_ORDER_LINES.BILLED_QUANTITY_BASE, billed)
                .set(PURCHASE_ORDER_LINES.UPDATED_AT, OffsetDateTime.now())
                .where(PURCHASE_ORDER_LINES.COMPANY_ID.eq(companyId))
                .and(PURCHASE_ORDER_LINES.ID.eq(lineId))
                .execute();
    }

    public boolean delete(UUID companyId, UUID id, int version) {
        return dsl.deleteFrom(PURCHASE_ORDERS)
                        .where(PURCHASE_ORDERS.COMPANY_ID.eq(companyId))
                        .and(PURCHASE_ORDERS.ID.eq(id))
                        .and(PURCHASE_ORDERS.VERSION.eq(version))
                        .and(PURCHASE_ORDERS.STATUS.eq("DRAFT"))
                        .execute()
                == 1;
    }

    /** Whether open orders (not closed or cancelled) use the branch or department. */
    public boolean usesBranch(UUID companyId, UUID branchId) {
        return dsl.fetchExists(dsl.selectOne()
                .from(PURCHASE_ORDERS)
                .where(PURCHASE_ORDERS.COMPANY_ID.eq(companyId))
                .and(PURCHASE_ORDERS.BRANCH_ID.eq(branchId))
                .and(PURCHASE_ORDERS.STATUS.notIn("CLOSED", "CANCELLED")));
    }

    public boolean usesDepartment(UUID companyId, UUID departmentId) {
        return dsl.fetchExists(dsl.selectOne()
                .from(PURCHASE_ORDERS)
                .where(PURCHASE_ORDERS.COMPANY_ID.eq(companyId))
                .and(PURCHASE_ORDERS.DEPARTMENT_ID.eq(departmentId))
                .and(PURCHASE_ORDERS.STATUS.notIn("CLOSED", "CANCELLED")));
    }

    public boolean usesTaxCode(UUID companyId, UUID taxCodeId) {
        return dsl.fetchExists(dsl.selectOne()
                .from(PURCHASE_ORDER_LINES)
                .join(PURCHASE_ORDERS)
                .on(PURCHASE_ORDERS.ID.eq(PURCHASE_ORDER_LINES.PURCHASE_ORDER_ID))
                .where(PURCHASE_ORDER_LINES.COMPANY_ID.eq(companyId))
                .and(PURCHASE_ORDER_LINES.TAX_CODE_ID.eq(taxCodeId))
                .and(PURCHASE_ORDERS.STATUS.ne("DRAFT")));
    }

    private static Map<org.jooq.Field<?>, Object> header(Header h) {
        Map<org.jooq.Field<?>, Object> values = new LinkedHashMap<>();
        values.put(PURCHASE_ORDERS.SUPPLIER_ID, h.supplierId());
        values.put(PURCHASE_ORDERS.BRANCH_ID, h.branchId());
        values.put(PURCHASE_ORDERS.WAREHOUSE_ID, h.warehouseId());
        values.put(PURCHASE_ORDERS.DEPARTMENT_ID, h.departmentId());
        values.put(PURCHASE_ORDERS.ORDER_DATE, h.orderDate());
        values.put(PURCHASE_ORDERS.EXPECTED_DATE, h.expectedDate());
        values.put(PURCHASE_ORDERS.CURRENCY_CODE, h.currencyCode());
        values.put(PURCHASE_ORDERS.PAYMENT_TERMS_ID, h.paymentTermsId());
        values.put(PURCHASE_ORDERS.PRICES_INCLUDE_TAX, h.pricesIncludeTax());
        values.put(PURCHASE_ORDERS.SUBTOTAL, h.subtotal());
        values.put(PURCHASE_ORDERS.TAX_TOTAL, h.taxTotal());
        values.put(PURCHASE_ORDERS.TOTAL, h.total());
        values.put(PURCHASE_ORDERS.NOTES, h.notes());
        return values;
    }

    static ProcurementViews.PurchaseOrder toView(PurchaseOrdersRecord r) {
        return new ProcurementViews.PurchaseOrder(
                r.getId(),
                r.getCompanyId(),
                r.getNumber(),
                r.getSupplierId(),
                r.getBranchId(),
                r.getWarehouseId(),
                r.getDepartmentId(),
                r.getOrderDate(),
                r.getExpectedDate(),
                r.getCurrencyCode(),
                r.getPaymentTermsId(),
                r.getPricesIncludeTax(),
                r.getStatus(),
                r.getBillingStatus(),
                r.getSubtotal(),
                r.getTaxTotal(),
                r.getTotal(),
                r.getSubmittedBy(),
                r.getSubmittedAt(),
                r.getApprovedBy(),
                r.getApprovedAt(),
                r.getRejectionReason(),
                r.getCancelReason(),
                r.getCloseReason(),
                r.getNotes(),
                r.getCreatedBy(),
                r.getCreatedAt(),
                r.getUpdatedAt(),
                r.getVersion());
    }

    static ProcurementViews.PurchaseOrderLine toLine(PurchaseOrderLinesRecord r) {
        return new ProcurementViews.PurchaseOrderLine(
                r.getId(),
                r.getLineNo(),
                r.getVariantId(),
                r.getDescription(),
                r.getIsStockable(),
                r.getQuantity(),
                r.getUomId(),
                r.getQuantityBase(),
                r.getUnitPrice(),
                r.getDiscountPercent(),
                r.getTaxCodeId(),
                r.getNetAmount(),
                r.getTaxAmount(),
                r.getTotalAmount(),
                r.getReceivedQuantityBase(),
                r.getReturnedQuantityBase(),
                r.getBilledQuantityBase(),
                r.getRequisitionLineId());
    }
}
