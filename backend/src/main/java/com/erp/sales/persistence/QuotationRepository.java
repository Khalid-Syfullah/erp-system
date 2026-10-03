package com.erp.sales.persistence;

import static com.erp.db.sales.Tables.QUOTATIONS;
import static com.erp.db.sales.Tables.QUOTATION_LINES;

import com.erp.db.sales.tables.records.QuotationLinesRecord;
import com.erp.db.sales.tables.records.QuotationsRecord;
import com.erp.platform.jooq.KeysetPaginator;
import com.erp.platform.jooq.ListBinding;
import com.erp.platform.web.paging.ListQuery;
import com.erp.platform.web.paging.PageResponse;
import com.erp.sales.application.SalesListings;
import com.erp.sales.application.SalesViews;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.impl.DSL;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;

/** Quotations and their lines. */
@Repository
public class QuotationRepository {

    private static final ListBinding BINDING = ListBinding.builder(SalesListings.QUOTATIONS)
            .field("quotationDate", QUOTATIONS.QUOTATION_DATE)
            .field("validUntil", QUOTATIONS.VALID_UNTIL)
            .field("createdAt", QUOTATIONS.CREATED_AT)
            .field("total", QUOTATIONS.TOTAL)
            .field("number", QUOTATIONS.NUMBER)
            .field("status", QUOTATIONS.STATUS)
            .field("customerId", QUOTATIONS.CUSTOMER_ID)
            .field("branchId", QUOTATIONS.BRANCH_ID)
            .tiebreaker(QUOTATIONS.ID)
            .search(List.of(QUOTATIONS.NUMBER, QUOTATIONS.NOTES))
            .build();

    public record Header(
            UUID customerId,
            UUID branchId,
            UUID warehouseId,
            LocalDate quotationDate,
            LocalDate validUntil,
            String currencyCode,
            @Nullable UUID priceListId,
            boolean pricesIncludeTax,
            @Nullable UUID paymentTermsId,
            BigDecimal subtotal,
            BigDecimal taxTotal,
            BigDecimal total,
            @Nullable String notes) {}

    /** A priced line to store (quotations and orders). */
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
            BigDecimal totalAmount) {}

    private final DSLContext dsl;
    private final KeysetPaginator paginator;

    public QuotationRepository(DSLContext dsl, KeysetPaginator paginator) {
        this.dsl = dsl;
        this.paginator = paginator;
    }

    public UUID insert(UUID companyId, Header h, UUID actor) {
        return dsl.insertInto(QUOTATIONS)
                .set(QUOTATIONS.COMPANY_ID, companyId)
                .set(header(h))
                .set(QUOTATIONS.CREATED_BY, actor)
                .set(QUOTATIONS.UPDATED_BY, actor)
                .returning(QUOTATIONS.ID)
                .fetchOne(QUOTATIONS.ID);
    }

    public void insertLines(UUID companyId, UUID quotationId, List<NewLine> lines, UUID actor) {
        for (NewLine l : lines) {
            dsl.insertInto(QUOTATION_LINES)
                    .set(QUOTATION_LINES.COMPANY_ID, companyId)
                    .set(QUOTATION_LINES.QUOTATION_ID, quotationId)
                    .set(QUOTATION_LINES.LINE_NO, l.lineNo())
                    .set(QUOTATION_LINES.VARIANT_ID, l.variantId())
                    .set(QUOTATION_LINES.DESCRIPTION, l.description())
                    .set(QUOTATION_LINES.QUANTITY, l.quantity())
                    .set(QUOTATION_LINES.UOM_ID, l.uomId())
                    .set(QUOTATION_LINES.QUANTITY_BASE, l.quantityBase())
                    .set(QUOTATION_LINES.UNIT_PRICE, l.unitPrice())
                    .set(QUOTATION_LINES.DISCOUNT_PERCENT, l.discountPercent())
                    .set(QUOTATION_LINES.TAX_CODE_ID, l.taxCodeId())
                    .set(QUOTATION_LINES.NET_AMOUNT, l.netAmount())
                    .set(QUOTATION_LINES.TAX_AMOUNT, l.taxAmount())
                    .set(QUOTATION_LINES.TOTAL_AMOUNT, l.totalAmount())
                    .set(QUOTATION_LINES.CREATED_BY, actor)
                    .set(QUOTATION_LINES.UPDATED_BY, actor)
                    .execute();
        }
    }

    public void deleteLines(UUID companyId, UUID quotationId) {
        dsl.deleteFrom(QUOTATION_LINES)
                .where(QUOTATION_LINES.COMPANY_ID.eq(companyId))
                .and(QUOTATION_LINES.QUOTATION_ID.eq(quotationId))
                .execute();
    }

    public Optional<SalesViews.Quotation> find(UUID companyId, UUID id) {
        return dsl.selectFrom(QUOTATIONS)
                .where(QUOTATIONS.COMPANY_ID.eq(companyId))
                .and(QUOTATIONS.ID.eq(id))
                .fetchOptional(QuotationRepository::toView);
    }

    public Optional<SalesViews.Quotation> lock(UUID companyId, UUID id) {
        return dsl.selectFrom(QUOTATIONS)
                .where(QUOTATIONS.COMPANY_ID.eq(companyId))
                .and(QUOTATIONS.ID.eq(id))
                .forUpdate()
                .fetchOptional(QuotationRepository::toView);
    }

    public PageResponse<SalesViews.Quotation> list(UUID companyId, @Nullable Set<UUID> branchScope, ListQuery query) {
        Condition scope = QUOTATIONS
                .COMPANY_ID
                .eq(companyId)
                .and(branchScope == null ? DSL.noCondition() : QUOTATIONS.BRANCH_ID.in(branchScope));
        return paginator.fetch(dsl, QUOTATIONS, scope, query, BINDING, QuotationRepository::toView);
    }

    public List<SalesViews.Line> lines(UUID companyId, UUID quotationId) {
        return dsl.selectFrom(QUOTATION_LINES)
                .where(QUOTATION_LINES.COMPANY_ID.eq(companyId))
                .and(QUOTATION_LINES.QUOTATION_ID.eq(quotationId))
                .orderBy(QUOTATION_LINES.LINE_NO)
                .fetch(QuotationRepository::toLine);
    }

    public boolean updateDraft(UUID companyId, UUID id, int version, UUID actor, Header h) {
        return dsl.update(QUOTATIONS)
                        .set(header(h))
                        .set(QUOTATIONS.UPDATED_AT, OffsetDateTime.now())
                        .set(QUOTATIONS.UPDATED_BY, actor)
                        .set(QUOTATIONS.VERSION, version + 1)
                        .where(QUOTATIONS.COMPANY_ID.eq(companyId))
                        .and(QUOTATIONS.ID.eq(id))
                        .and(QUOTATIONS.VERSION.eq(version))
                        .and(QUOTATIONS.STATUS.eq("DRAFT"))
                        .execute()
                == 1;
    }

    /** State change; {@code number} and {@code sent} only while leaving DRAFT. */
    public boolean transition(
            UUID companyId,
            UUID id,
            int version,
            @Nullable UUID actor,
            String status,
            @Nullable String number,
            @Nullable UUID salesOrderId,
            @Nullable String rejectionReason) {
        var update = dsl.update(QUOTATIONS)
                .set(QUOTATIONS.STATUS, status)
                .set(QUOTATIONS.UPDATED_AT, OffsetDateTime.now())
                .set(QUOTATIONS.UPDATED_BY, actor)
                .set(QUOTATIONS.VERSION, version + 1);
        if (number != null) {
            update = update.set(QUOTATIONS.NUMBER, number)
                    .set(QUOTATIONS.SENT_AT, OffsetDateTime.now())
                    .set(QUOTATIONS.SENT_BY, actor);
        }
        if (salesOrderId != null) {
            update = update.set(QUOTATIONS.SALES_ORDER_ID, salesOrderId);
        }
        if (rejectionReason != null) {
            update = update.set(QUOTATIONS.REJECTION_REASON, rejectionReason);
        }
        return update.where(QUOTATIONS.COMPANY_ID.eq(companyId))
                        .and(QUOTATIONS.ID.eq(id))
                        .and(QUOTATIONS.VERSION.eq(version))
                        .execute()
                == 1;
    }

    public boolean delete(UUID companyId, UUID id, int version) {
        return dsl.deleteFrom(QUOTATIONS)
                        .where(QUOTATIONS.COMPANY_ID.eq(companyId))
                        .and(QUOTATIONS.ID.eq(id))
                        .and(QUOTATIONS.VERSION.eq(version))
                        .and(QUOTATIONS.STATUS.eq("DRAFT"))
                        .execute()
                == 1;
    }

    /** Sent quotations past their validity (the daily expiry job), locked and skipping locked ones. */
    public List<SalesViews.Quotation> expired(UUID companyId, LocalDate today, int limit) {
        return dsl.selectFrom(QUOTATIONS)
                .where(QUOTATIONS.COMPANY_ID.eq(companyId))
                .and(QUOTATIONS.STATUS.eq("SENT"))
                .and(QUOTATIONS.VALID_UNTIL.lt(today))
                .orderBy(QUOTATIONS.ID)
                .limit(limit)
                .forUpdate()
                .skipLocked()
                .fetch(QuotationRepository::toView);
    }

    public boolean usesBranch(UUID companyId, UUID branchId) {
        return dsl.fetchExists(dsl.selectOne()
                .from(QUOTATIONS)
                .where(QUOTATIONS.COMPANY_ID.eq(companyId))
                .and(QUOTATIONS.BRANCH_ID.eq(branchId))
                .and(QUOTATIONS.STATUS.in("DRAFT", "SENT")));
    }

    public boolean usesTaxCode(UUID companyId, UUID taxCodeId) {
        return dsl.fetchExists(dsl.selectOne()
                .from(QUOTATION_LINES)
                .join(QUOTATIONS)
                .on(QUOTATIONS.ID.eq(QUOTATION_LINES.QUOTATION_ID))
                .where(QUOTATION_LINES.COMPANY_ID.eq(companyId))
                .and(QUOTATION_LINES.TAX_CODE_ID.eq(taxCodeId))
                .and(QUOTATIONS.STATUS.ne("DRAFT")));
    }

    private static Map<Field<?>, Object> header(Header h) {
        Map<Field<?>, Object> values = new LinkedHashMap<>();
        values.put(QUOTATIONS.CUSTOMER_ID, h.customerId());
        values.put(QUOTATIONS.BRANCH_ID, h.branchId());
        values.put(QUOTATIONS.WAREHOUSE_ID, h.warehouseId());
        values.put(QUOTATIONS.QUOTATION_DATE, h.quotationDate());
        values.put(QUOTATIONS.VALID_UNTIL, h.validUntil());
        values.put(QUOTATIONS.CURRENCY_CODE, h.currencyCode());
        values.put(QUOTATIONS.PRICE_LIST_ID, h.priceListId());
        values.put(QUOTATIONS.PRICES_INCLUDE_TAX, h.pricesIncludeTax());
        values.put(QUOTATIONS.PAYMENT_TERMS_ID, h.paymentTermsId());
        values.put(QUOTATIONS.SUBTOTAL, h.subtotal());
        values.put(QUOTATIONS.TAX_TOTAL, h.taxTotal());
        values.put(QUOTATIONS.TOTAL, h.total());
        values.put(QUOTATIONS.NOTES, h.notes());
        return values;
    }

    static SalesViews.Quotation toView(QuotationsRecord r) {
        return new SalesViews.Quotation(
                r.getId(),
                r.getCompanyId(),
                r.getNumber(),
                r.getCustomerId(),
                r.getBranchId(),
                r.getWarehouseId(),
                r.getQuotationDate(),
                r.getValidUntil(),
                r.getCurrencyCode(),
                r.getPriceListId(),
                r.getPricesIncludeTax(),
                r.getPaymentTermsId(),
                r.getStatus(),
                r.getSubtotal(),
                r.getTaxTotal(),
                r.getTotal(),
                r.getSalesOrderId(),
                r.getSentAt(),
                r.getRejectionReason(),
                r.getNotes(),
                r.getCreatedBy(),
                r.getCreatedAt(),
                r.getUpdatedAt(),
                r.getVersion());
    }

    static SalesViews.Line toLine(QuotationLinesRecord r) {
        return new SalesViews.Line(
                r.getId(),
                r.getLineNo(),
                r.getVariantId(),
                r.getDescription(),
                r.getQuantity(),
                r.getUomId(),
                r.getQuantityBase(),
                r.getUnitPrice(),
                r.getDiscountPercent(),
                r.getTaxCodeId(),
                r.getNetAmount(),
                r.getTaxAmount(),
                r.getTotalAmount());
    }
}
