package com.erp.procurement.persistence;

import static com.erp.db.procurement.Tables.PURCHASE_ORDERS;
import static com.erp.db.procurement.Tables.PURCHASE_ORDER_LINES;
import static com.erp.db.procurement.Tables.PURCHASE_REQUISITIONS;
import static com.erp.db.procurement.Tables.PURCHASE_REQUISITION_LINES;

import com.erp.db.procurement.tables.records.PurchaseRequisitionLinesRecord;
import com.erp.db.procurement.tables.records.PurchaseRequisitionsRecord;
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

/** Purchase requisitions and their lines. */
@Repository
public class RequisitionRepository {

    private static final ListBinding BINDING = ListBinding.builder(ProcurementListings.REQUISITIONS)
            .field("createdAt", PURCHASE_REQUISITIONS.CREATED_AT)
            .field("number", PURCHASE_REQUISITIONS.NUMBER)
            .field("status", PURCHASE_REQUISITIONS.STATUS)
            .field("branchId", PURCHASE_REQUISITIONS.BRANCH_ID)
            .field("departmentId", PURCHASE_REQUISITIONS.DEPARTMENT_ID)
            .field("requestedBy", PURCHASE_REQUISITIONS.REQUESTED_BY)
            .tiebreaker(PURCHASE_REQUISITIONS.ID)
            .search(List.of(PURCHASE_REQUISITIONS.NUMBER, PURCHASE_REQUISITIONS.NOTES))
            .build();

    public record NewLine(
            int lineNo,
            UUID variantId,
            String description,
            BigDecimal quantity,
            UUID uomId,
            BigDecimal quantityBase,
            @Nullable BigDecimal estimatedUnitPrice,
            @Nullable UUID suggestedSupplierId) {}

    private final DSLContext dsl;
    private final KeysetPaginator paginator;

    public RequisitionRepository(DSLContext dsl, KeysetPaginator paginator) {
        this.dsl = dsl;
        this.paginator = paginator;
    }

    public UUID insert(
            UUID companyId,
            UUID branchId,
            @Nullable UUID departmentId,
            @Nullable LocalDate neededBy,
            @Nullable String notes,
            UUID actor) {
        return dsl.insertInto(PURCHASE_REQUISITIONS)
                .set(PURCHASE_REQUISITIONS.COMPANY_ID, companyId)
                .set(PURCHASE_REQUISITIONS.BRANCH_ID, branchId)
                .set(PURCHASE_REQUISITIONS.DEPARTMENT_ID, departmentId)
                .set(PURCHASE_REQUISITIONS.NEEDED_BY, neededBy)
                .set(PURCHASE_REQUISITIONS.NOTES, notes)
                .set(PURCHASE_REQUISITIONS.REQUESTED_BY, actor)
                .set(PURCHASE_REQUISITIONS.CREATED_BY, actor)
                .set(PURCHASE_REQUISITIONS.UPDATED_BY, actor)
                .returning(PURCHASE_REQUISITIONS.ID)
                .fetchOne(PURCHASE_REQUISITIONS.ID);
    }

    public void insertLines(UUID companyId, UUID requisitionId, List<NewLine> lines, UUID actor) {
        for (NewLine l : lines) {
            dsl.insertInto(PURCHASE_REQUISITION_LINES)
                    .set(PURCHASE_REQUISITION_LINES.COMPANY_ID, companyId)
                    .set(PURCHASE_REQUISITION_LINES.REQUISITION_ID, requisitionId)
                    .set(PURCHASE_REQUISITION_LINES.LINE_NO, l.lineNo())
                    .set(PURCHASE_REQUISITION_LINES.VARIANT_ID, l.variantId())
                    .set(PURCHASE_REQUISITION_LINES.DESCRIPTION, l.description())
                    .set(PURCHASE_REQUISITION_LINES.QUANTITY, l.quantity())
                    .set(PURCHASE_REQUISITION_LINES.UOM_ID, l.uomId())
                    .set(PURCHASE_REQUISITION_LINES.QUANTITY_BASE, l.quantityBase())
                    .set(PURCHASE_REQUISITION_LINES.ESTIMATED_UNIT_PRICE, l.estimatedUnitPrice())
                    .set(PURCHASE_REQUISITION_LINES.SUGGESTED_SUPPLIER_ID, l.suggestedSupplierId())
                    .set(PURCHASE_REQUISITION_LINES.CREATED_BY, actor)
                    .set(PURCHASE_REQUISITION_LINES.UPDATED_BY, actor)
                    .execute();
        }
    }

    public void deleteLines(UUID companyId, UUID requisitionId) {
        dsl.deleteFrom(PURCHASE_REQUISITION_LINES)
                .where(PURCHASE_REQUISITION_LINES.COMPANY_ID.eq(companyId))
                .and(PURCHASE_REQUISITION_LINES.REQUISITION_ID.eq(requisitionId))
                .execute();
    }

    public Optional<ProcurementViews.Requisition> find(UUID companyId, UUID id) {
        return dsl.selectFrom(PURCHASE_REQUISITIONS)
                .where(PURCHASE_REQUISITIONS.COMPANY_ID.eq(companyId))
                .and(PURCHASE_REQUISITIONS.ID.eq(id))
                .fetchOptional(RequisitionRepository::toView);
    }

    public Optional<ProcurementViews.Requisition> lock(UUID companyId, UUID id) {
        return dsl.selectFrom(PURCHASE_REQUISITIONS)
                .where(PURCHASE_REQUISITIONS.COMPANY_ID.eq(companyId))
                .and(PURCHASE_REQUISITIONS.ID.eq(id))
                .forUpdate()
                .fetchOptional(RequisitionRepository::toView);
    }

    /** Requisitions owning the lines, locked {@code FOR UPDATE} in ID order. */
    public Map<UUID, ProcurementViews.Requisition> lockOwnersOfLines(UUID companyId, Collection<UUID> lineIds) {
        Map<UUID, ProcurementViews.Requisition> result = new LinkedHashMap<>();
        if (lineIds.isEmpty()) {
            return result;
        }
        dsl.selectFrom(PURCHASE_REQUISITIONS)
                .where(PURCHASE_REQUISITIONS.COMPANY_ID.eq(companyId))
                .and(PURCHASE_REQUISITIONS.ID.in(DSL.select(PURCHASE_REQUISITION_LINES.REQUISITION_ID)
                        .from(PURCHASE_REQUISITION_LINES)
                        .where(PURCHASE_REQUISITION_LINES.ID.in(lineIds))))
                .orderBy(PURCHASE_REQUISITIONS.ID)
                .forUpdate()
                .fetch(RequisitionRepository::toView)
                .forEach(r -> result.put(r.id(), r));
        return result;
    }

    public PageResponse<ProcurementViews.Requisition> list(
            UUID companyId, @Nullable Set<UUID> branchScope, ListQuery query) {
        Condition scope = PURCHASE_REQUISITIONS
                .COMPANY_ID
                .eq(companyId)
                .and(branchScope == null ? DSL.noCondition() : PURCHASE_REQUISITIONS.BRANCH_ID.in(branchScope));
        return paginator.fetch(dsl, PURCHASE_REQUISITIONS, scope, query, BINDING, RequisitionRepository::toView);
    }

    public List<ProcurementViews.RequisitionLine> lines(UUID companyId, UUID requisitionId) {
        return dsl.selectFrom(PURCHASE_REQUISITION_LINES)
                .where(PURCHASE_REQUISITION_LINES.COMPANY_ID.eq(companyId))
                .and(PURCHASE_REQUISITION_LINES.REQUISITION_ID.eq(requisitionId))
                .orderBy(PURCHASE_REQUISITION_LINES.LINE_NO)
                .fetch(RequisitionRepository::toLine);
    }

    /** Lines by ID with their requisition. */
    public Map<UUID, LineOfRequisition> linesById(UUID companyId, Collection<UUID> lineIds) {
        Map<UUID, LineOfRequisition> result = new LinkedHashMap<>();
        if (lineIds.isEmpty()) {
            return result;
        }
        dsl.select()
                .from(PURCHASE_REQUISITION_LINES)
                .join(PURCHASE_REQUISITIONS)
                .on(PURCHASE_REQUISITIONS.ID.eq(PURCHASE_REQUISITION_LINES.REQUISITION_ID))
                .where(PURCHASE_REQUISITION_LINES.COMPANY_ID.eq(companyId))
                .and(PURCHASE_REQUISITION_LINES.ID.in(lineIds))
                .fetch()
                .forEach(r -> result.put(
                        r.get(PURCHASE_REQUISITION_LINES.ID),
                        new LineOfRequisition(
                                toView(r.into(PURCHASE_REQUISITIONS)), toLine(r.into(PURCHASE_REQUISITION_LINES)))));
        return result;
    }

    public record LineOfRequisition(ProcurementViews.Requisition requisition, ProcurementViews.RequisitionLine line) {}

    public boolean updateDraft(
            UUID companyId,
            UUID id,
            int version,
            UUID actor,
            UUID branchId,
            @Nullable UUID departmentId,
            @Nullable LocalDate neededBy,
            @Nullable String notes) {
        return dsl.update(PURCHASE_REQUISITIONS)
                        .set(PURCHASE_REQUISITIONS.BRANCH_ID, branchId)
                        .set(PURCHASE_REQUISITIONS.DEPARTMENT_ID, departmentId)
                        .set(PURCHASE_REQUISITIONS.NEEDED_BY, neededBy)
                        .set(PURCHASE_REQUISITIONS.NOTES, notes)
                        .set(PURCHASE_REQUISITIONS.UPDATED_AT, OffsetDateTime.now())
                        .set(PURCHASE_REQUISITIONS.UPDATED_BY, actor)
                        .set(PURCHASE_REQUISITIONS.VERSION, version + 1)
                        .where(PURCHASE_REQUISITIONS.COMPANY_ID.eq(companyId))
                        .and(PURCHASE_REQUISITIONS.ID.eq(id))
                        .and(PURCHASE_REQUISITIONS.VERSION.eq(version))
                        .and(PURCHASE_REQUISITIONS.STATUS.eq("DRAFT"))
                        .execute()
                == 1;
    }

    /** State change; {@code number}, {@code submitted}, {@code approved} and the reasons are optional. */
    public boolean transition(
            UUID companyId,
            UUID id,
            int version,
            UUID actor,
            String status,
            @Nullable String number,
            boolean submitted,
            boolean approved,
            @Nullable String rejectionReason,
            @Nullable String cancelReason) {
        var update = dsl.update(PURCHASE_REQUISITIONS)
                .set(PURCHASE_REQUISITIONS.STATUS, status)
                .set(PURCHASE_REQUISITIONS.UPDATED_AT, OffsetDateTime.now())
                .set(PURCHASE_REQUISITIONS.UPDATED_BY, actor)
                .set(PURCHASE_REQUISITIONS.VERSION, version + 1);
        if (number != null) {
            update = update.set(PURCHASE_REQUISITIONS.NUMBER, number);
        }
        if (submitted) {
            update = update.set(PURCHASE_REQUISITIONS.SUBMITTED_BY, actor)
                    .set(PURCHASE_REQUISITIONS.SUBMITTED_AT, OffsetDateTime.now());
        }
        if (approved) {
            update = update.set(PURCHASE_REQUISITIONS.APPROVED_BY, actor)
                    .set(PURCHASE_REQUISITIONS.APPROVED_AT, OffsetDateTime.now());
        }
        if (rejectionReason != null) {
            update = update.set(PURCHASE_REQUISITIONS.REJECTION_REASON, rejectionReason);
        }
        if (cancelReason != null) {
            update = update.set(PURCHASE_REQUISITIONS.CANCEL_REASON, cancelReason);
        }
        return update.where(PURCHASE_REQUISITIONS.COMPANY_ID.eq(companyId))
                        .and(PURCHASE_REQUISITIONS.ID.eq(id))
                        .and(PURCHASE_REQUISITIONS.VERSION.eq(version))
                        .execute()
                == 1;
    }

    public boolean delete(UUID companyId, UUID id, int version) {
        return dsl.deleteFrom(PURCHASE_REQUISITIONS)
                        .where(PURCHASE_REQUISITIONS.COMPANY_ID.eq(companyId))
                        .and(PURCHASE_REQUISITIONS.ID.eq(id))
                        .and(PURCHASE_REQUISITIONS.VERSION.eq(version))
                        .and(PURCHASE_REQUISITIONS.STATUS.eq("DRAFT"))
                        .execute()
                == 1;
    }

    /**
     * Quantity of each requisition line on live (not cancelled) purchase orders: the ordered
     * quantity is always derived from the orders, never counted separately.
     */
    public Map<UUID, BigDecimal> orderedQuantities(UUID companyId, UUID requisitionId) {
        Map<UUID, BigDecimal> result = new LinkedHashMap<>();
        dsl.select(
                        PURCHASE_REQUISITION_LINES.ID,
                        DSL.coalesce(DSL.sum(PURCHASE_ORDER_LINES.QUANTITY_BASE), BigDecimal.ZERO))
                .from(PURCHASE_REQUISITION_LINES)
                .leftJoin(PURCHASE_ORDER_LINES)
                .on(PURCHASE_ORDER_LINES.REQUISITION_LINE_ID.eq(PURCHASE_REQUISITION_LINES.ID))
                .and(PURCHASE_ORDER_LINES.PURCHASE_ORDER_ID.in(DSL.select(PURCHASE_ORDERS.ID)
                        .from(PURCHASE_ORDERS)
                        .where(PURCHASE_ORDERS.COMPANY_ID.eq(companyId))
                        .and(PURCHASE_ORDERS.STATUS.ne("CANCELLED"))))
                .where(PURCHASE_REQUISITION_LINES.COMPANY_ID.eq(companyId))
                .and(PURCHASE_REQUISITION_LINES.REQUISITION_ID.eq(requisitionId))
                .groupBy(PURCHASE_REQUISITION_LINES.ID)
                .fetch()
                .forEach(r -> result.put(r.value1(), r.value2()));
        return result;
    }

    public void setOrderedQuantity(UUID companyId, UUID lineId, BigDecimal ordered) {
        dsl.update(PURCHASE_REQUISITION_LINES)
                .set(PURCHASE_REQUISITION_LINES.ORDERED_QUANTITY_BASE, ordered)
                .set(PURCHASE_REQUISITION_LINES.UPDATED_AT, OffsetDateTime.now())
                .where(PURCHASE_REQUISITION_LINES.COMPANY_ID.eq(companyId))
                .and(PURCHASE_REQUISITION_LINES.ID.eq(lineId))
                .execute();
    }

    public boolean usesBranch(UUID companyId, UUID branchId) {
        return dsl.fetchExists(dsl.selectOne()
                .from(PURCHASE_REQUISITIONS)
                .where(PURCHASE_REQUISITIONS.COMPANY_ID.eq(companyId))
                .and(PURCHASE_REQUISITIONS.BRANCH_ID.eq(branchId))
                .and(PURCHASE_REQUISITIONS.STATUS.in("DRAFT", "SUBMITTED", "APPROVED", "PARTIALLY_ORDERED")));
    }

    public boolean usesDepartment(UUID companyId, UUID departmentId) {
        return dsl.fetchExists(dsl.selectOne()
                .from(PURCHASE_REQUISITIONS)
                .where(PURCHASE_REQUISITIONS.COMPANY_ID.eq(companyId))
                .and(PURCHASE_REQUISITIONS.DEPARTMENT_ID.eq(departmentId))
                .and(PURCHASE_REQUISITIONS.STATUS.in("DRAFT", "SUBMITTED", "APPROVED", "PARTIALLY_ORDERED")));
    }

    static ProcurementViews.Requisition toView(PurchaseRequisitionsRecord r) {
        return new ProcurementViews.Requisition(
                r.getId(),
                r.getCompanyId(),
                r.getNumber(),
                r.getBranchId(),
                r.getDepartmentId(),
                r.getRequestedBy(),
                r.getNeededBy(),
                r.getStatus(),
                r.getSubmittedBy(),
                r.getSubmittedAt(),
                r.getApprovedBy(),
                r.getApprovedAt(),
                r.getRejectionReason(),
                r.getCancelReason(),
                r.getNotes(),
                r.getCreatedBy(),
                r.getCreatedAt(),
                r.getUpdatedAt(),
                r.getVersion());
    }

    static ProcurementViews.RequisitionLine toLine(PurchaseRequisitionLinesRecord r) {
        return new ProcurementViews.RequisitionLine(
                r.getId(),
                r.getLineNo(),
                r.getVariantId(),
                r.getDescription(),
                r.getQuantity(),
                r.getUomId(),
                r.getQuantityBase(),
                r.getEstimatedUnitPrice(),
                r.getSuggestedSupplierId(),
                r.getOrderedQuantityBase());
    }
}
