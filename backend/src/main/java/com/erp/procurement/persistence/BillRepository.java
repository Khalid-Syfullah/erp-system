package com.erp.procurement.persistence;

import static com.erp.db.procurement.Tables.SUPPLIER_BILLS;
import static com.erp.db.procurement.Tables.SUPPLIER_BILL_LINES;
import static com.erp.db.procurement.Tables.SUPPLIER_BILL_TAXES;

import com.erp.db.procurement.tables.records.SupplierBillLinesRecord;
import com.erp.db.procurement.tables.records.SupplierBillsRecord;
import com.erp.platform.jooq.KeysetPaginator;
import com.erp.platform.jooq.ListBinding;
import com.erp.platform.web.paging.ListQuery;
import com.erp.platform.web.paging.PageResponse;
import com.erp.procurement.application.ProcurementListings;
import com.erp.procurement.application.ProcurementViews;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;

/** Supplier bills and debit notes with their lines and tax summary. */
@Repository
public class BillRepository {

    private static final ListBinding BINDING = ListBinding.builder(ProcurementListings.BILLS)
            .field("billDate", SUPPLIER_BILLS.BILL_DATE)
            .field("dueDate", SUPPLIER_BILLS.DUE_DATE)
            .field("createdAt", SUPPLIER_BILLS.CREATED_AT)
            .field("number", SUPPLIER_BILLS.NUMBER)
            .field("total", SUPPLIER_BILLS.TOTAL)
            .field("documentType", SUPPLIER_BILLS.DOCUMENT_TYPE)
            .field("status", SUPPLIER_BILLS.STATUS)
            .field("matchStatus", SUPPLIER_BILLS.MATCH_STATUS)
            .field("supplierId", SUPPLIER_BILLS.SUPPLIER_ID)
            .field("purchaseOrderId", SUPPLIER_BILLS.PURCHASE_ORDER_ID)
            .field("originalBillId", SUPPLIER_BILLS.ORIGINAL_BILL_ID)
            .field("supplierInvoiceNumber", SUPPLIER_BILLS.SUPPLIER_INVOICE_NUMBER)
            .tiebreaker(SUPPLIER_BILLS.ID)
            .search(List.of(SUPPLIER_BILLS.NUMBER, SUPPLIER_BILLS.SUPPLIER_INVOICE_NUMBER, SUPPLIER_BILLS.NOTES))
            .build();

    /** Header values of a draft (totals computed by the service). */
    public record Header(
            String documentType,
            String supplierInvoiceNumber,
            UUID supplierId,
            @Nullable UUID purchaseOrderId,
            @Nullable UUID originalBillId,
            LocalDate billDate,
            LocalDate accountingDate,
            LocalDate dueDate,
            String currencyCode,
            BigDecimal exchangeRate,
            boolean pricesIncludeTax,
            @Nullable UUID paymentTermsId,
            BigDecimal subtotal,
            BigDecimal taxTotal,
            BigDecimal total,
            BigDecimal subtotalBase,
            BigDecimal taxTotalBase,
            BigDecimal totalBase,
            @Nullable String notes) {}

    public record NewLine(
            int lineNo,
            String lineKind,
            @Nullable UUID purchaseOrderLineId,
            @Nullable UUID goodsReceiptLineId,
            UUID variantId,
            String description,
            BigDecimal quantity,
            UUID uomId,
            BigDecimal quantityBase,
            BigDecimal unitPrice,
            BigDecimal discountPercent,
            @Nullable UUID taxCodeId,
            BigDecimal netAmount,
            BigDecimal taxAmount,
            BigDecimal totalAmount,
            BigDecimal netAmountBase,
            BigDecimal taxAmountBase,
            @Nullable UUID branchId,
            @Nullable UUID departmentId) {}

    private final DSLContext dsl;
    private final KeysetPaginator paginator;

    public BillRepository(DSLContext dsl, KeysetPaginator paginator) {
        this.dsl = dsl;
        this.paginator = paginator;
    }

    public UUID insert(UUID companyId, Header h, UUID actor) {
        return dsl.insertInto(SUPPLIER_BILLS)
                .set(SUPPLIER_BILLS.COMPANY_ID, companyId)
                .set(header(h))
                .set(SUPPLIER_BILLS.CREATED_BY, actor)
                .set(SUPPLIER_BILLS.UPDATED_BY, actor)
                .returning(SUPPLIER_BILLS.ID)
                .fetchOne(SUPPLIER_BILLS.ID);
    }

    public boolean updateDraft(UUID companyId, UUID id, int version, UUID actor, Header h) {
        return dsl.update(SUPPLIER_BILLS)
                        .set(header(h))
                        .set(SUPPLIER_BILLS.MATCH_STATUS, "NOT_CHECKED")
                        .set(SUPPLIER_BILLS.MATCH_OVERRIDE_BY, (UUID) null)
                        .set(SUPPLIER_BILLS.MATCH_OVERRIDE_REASON, (String) null)
                        .set(SUPPLIER_BILLS.UPDATED_AT, OffsetDateTime.now())
                        .set(SUPPLIER_BILLS.UPDATED_BY, actor)
                        .set(SUPPLIER_BILLS.VERSION, version + 1)
                        .where(SUPPLIER_BILLS.COMPANY_ID.eq(companyId))
                        .and(SUPPLIER_BILLS.ID.eq(id))
                        .and(SUPPLIER_BILLS.VERSION.eq(version))
                        .and(SUPPLIER_BILLS.STATUS.eq("DRAFT"))
                        .execute()
                == 1;
    }

    public void insertLines(UUID companyId, UUID billId, List<NewLine> lines, UUID actor) {
        for (NewLine l : lines) {
            dsl.insertInto(SUPPLIER_BILL_LINES)
                    .set(SUPPLIER_BILL_LINES.COMPANY_ID, companyId)
                    .set(SUPPLIER_BILL_LINES.SUPPLIER_BILL_ID, billId)
                    .set(SUPPLIER_BILL_LINES.LINE_NO, l.lineNo())
                    .set(SUPPLIER_BILL_LINES.LINE_KIND, l.lineKind())
                    .set(SUPPLIER_BILL_LINES.PURCHASE_ORDER_LINE_ID, l.purchaseOrderLineId())
                    .set(SUPPLIER_BILL_LINES.GOODS_RECEIPT_LINE_ID, l.goodsReceiptLineId())
                    .set(SUPPLIER_BILL_LINES.VARIANT_ID, l.variantId())
                    .set(SUPPLIER_BILL_LINES.DESCRIPTION, l.description())
                    .set(SUPPLIER_BILL_LINES.QUANTITY, l.quantity())
                    .set(SUPPLIER_BILL_LINES.UOM_ID, l.uomId())
                    .set(SUPPLIER_BILL_LINES.QUANTITY_BASE, l.quantityBase())
                    .set(SUPPLIER_BILL_LINES.UNIT_PRICE, l.unitPrice())
                    .set(SUPPLIER_BILL_LINES.DISCOUNT_PERCENT, l.discountPercent())
                    .set(SUPPLIER_BILL_LINES.TAX_CODE_ID, l.taxCodeId())
                    .set(SUPPLIER_BILL_LINES.NET_AMOUNT, l.netAmount())
                    .set(SUPPLIER_BILL_LINES.TAX_AMOUNT, l.taxAmount())
                    .set(SUPPLIER_BILL_LINES.TOTAL_AMOUNT, l.totalAmount())
                    .set(SUPPLIER_BILL_LINES.NET_AMOUNT_BASE, l.netAmountBase())
                    .set(SUPPLIER_BILL_LINES.TAX_AMOUNT_BASE, l.taxAmountBase())
                    .set(SUPPLIER_BILL_LINES.BRANCH_ID, l.branchId())
                    .set(SUPPLIER_BILL_LINES.DEPARTMENT_ID, l.departmentId())
                    .set(SUPPLIER_BILL_LINES.CREATED_BY, actor)
                    .set(SUPPLIER_BILL_LINES.UPDATED_BY, actor)
                    .execute();
        }
    }

    public void replaceTaxes(UUID companyId, UUID billId, List<ProcurementViews.SupplierBillTax> taxes) {
        dsl.deleteFrom(SUPPLIER_BILL_TAXES)
                .where(SUPPLIER_BILL_TAXES.COMPANY_ID.eq(companyId))
                .and(SUPPLIER_BILL_TAXES.SUPPLIER_BILL_ID.eq(billId))
                .execute();
        for (var t : taxes) {
            dsl.insertInto(SUPPLIER_BILL_TAXES)
                    .set(SUPPLIER_BILL_TAXES.COMPANY_ID, companyId)
                    .set(SUPPLIER_BILL_TAXES.SUPPLIER_BILL_ID, billId)
                    .set(SUPPLIER_BILL_TAXES.TAX_CODE_ID, t.taxCodeId())
                    .set(SUPPLIER_BILL_TAXES.RATE_PERCENT, t.ratePercent())
                    .set(SUPPLIER_BILL_TAXES.TAXABLE_AMOUNT, t.taxableAmount())
                    .set(SUPPLIER_BILL_TAXES.TAX_AMOUNT, t.taxAmount())
                    .set(SUPPLIER_BILL_TAXES.TAXABLE_AMOUNT_BASE, t.taxableAmountBase())
                    .set(SUPPLIER_BILL_TAXES.TAX_AMOUNT_BASE, t.taxAmountBase())
                    .execute();
        }
    }

    public void deleteLines(UUID companyId, UUID billId) {
        dsl.deleteFrom(SUPPLIER_BILL_LINES)
                .where(SUPPLIER_BILL_LINES.COMPANY_ID.eq(companyId))
                .and(SUPPLIER_BILL_LINES.SUPPLIER_BILL_ID.eq(billId))
                .execute();
    }

    public Optional<ProcurementViews.SupplierBill> find(UUID companyId, UUID id) {
        return dsl.selectFrom(SUPPLIER_BILLS)
                .where(SUPPLIER_BILLS.COMPANY_ID.eq(companyId))
                .and(SUPPLIER_BILLS.ID.eq(id))
                .fetchOptional(BillRepository::toView);
    }

    public Optional<ProcurementViews.SupplierBill> lock(UUID companyId, UUID id) {
        return dsl.selectFrom(SUPPLIER_BILLS)
                .where(SUPPLIER_BILLS.COMPANY_ID.eq(companyId))
                .and(SUPPLIER_BILLS.ID.eq(id))
                .forUpdate()
                .fetchOptional(BillRepository::toView);
    }

    public PageResponse<ProcurementViews.SupplierBill> list(UUID companyId, ListQuery query) {
        return paginator.fetch(
                dsl, SUPPLIER_BILLS, SUPPLIER_BILLS.COMPANY_ID.eq(companyId), query, BINDING, BillRepository::toView);
    }

    public List<ProcurementViews.SupplierBillLine> lines(UUID companyId, UUID billId) {
        return dsl.selectFrom(SUPPLIER_BILL_LINES)
                .where(SUPPLIER_BILL_LINES.COMPANY_ID.eq(companyId))
                .and(SUPPLIER_BILL_LINES.SUPPLIER_BILL_ID.eq(billId))
                .orderBy(SUPPLIER_BILL_LINES.LINE_NO)
                .fetch(BillRepository::toLine);
    }

    public List<ProcurementViews.SupplierBillTax> taxes(UUID companyId, UUID billId) {
        return dsl.selectFrom(SUPPLIER_BILL_TAXES)
                .where(SUPPLIER_BILL_TAXES.COMPANY_ID.eq(companyId))
                .and(SUPPLIER_BILL_TAXES.SUPPLIER_BILL_ID.eq(billId))
                .orderBy(SUPPLIER_BILL_TAXES.TAX_CODE_ID)
                .fetch(r -> new ProcurementViews.SupplierBillTax(
                        r.getTaxCodeId(),
                        r.getRatePercent(),
                        r.getTaxableAmount(),
                        r.getTaxAmount(),
                        r.getTaxableAmountBase(),
                        r.getTaxAmountBase()));
    }

    public boolean setMatch(
            UUID companyId,
            UUID id,
            int version,
            UUID actor,
            String status,
            @Nullable UUID overrideBy,
            @Nullable String overrideReason) {
        return dsl.update(SUPPLIER_BILLS)
                        .set(SUPPLIER_BILLS.MATCH_STATUS, status)
                        .set(SUPPLIER_BILLS.MATCH_OVERRIDE_BY, overrideBy)
                        .set(SUPPLIER_BILLS.MATCH_OVERRIDE_REASON, overrideReason)
                        .set(SUPPLIER_BILLS.UPDATED_AT, OffsetDateTime.now())
                        .set(SUPPLIER_BILLS.UPDATED_BY, actor)
                        .set(SUPPLIER_BILLS.VERSION, version + 1)
                        .where(SUPPLIER_BILLS.COMPANY_ID.eq(companyId))
                        .and(SUPPLIER_BILLS.ID.eq(id))
                        .and(SUPPLIER_BILLS.VERSION.eq(version))
                        .and(SUPPLIER_BILLS.STATUS.eq("DRAFT"))
                        .execute()
                == 1;
    }

    /** Records the receipt value a received-stock line clears (while the bill is still a draft). */
    public void setReceiptValue(UUID companyId, UUID lineId, BigDecimal value) {
        dsl.update(SUPPLIER_BILL_LINES)
                .set(SUPPLIER_BILL_LINES.RECEIPT_VALUE_BASE, value)
                .where(SUPPLIER_BILL_LINES.COMPANY_ID.eq(companyId))
                .and(SUPPLIER_BILL_LINES.ID.eq(lineId))
                .execute();
    }

    public boolean markPosted(UUID companyId, UUID id, int version, UUID actor, String number) {
        return dsl.update(SUPPLIER_BILLS)
                        .set(SUPPLIER_BILLS.STATUS, "POSTED")
                        .set(SUPPLIER_BILLS.NUMBER, number)
                        .set(SUPPLIER_BILLS.POSTED_AT, OffsetDateTime.now())
                        .set(SUPPLIER_BILLS.POSTED_BY, actor)
                        .set(SUPPLIER_BILLS.UPDATED_AT, OffsetDateTime.now())
                        .set(SUPPLIER_BILLS.UPDATED_BY, actor)
                        .set(SUPPLIER_BILLS.VERSION, version + 1)
                        .where(SUPPLIER_BILLS.COMPANY_ID.eq(companyId))
                        .and(SUPPLIER_BILLS.ID.eq(id))
                        .and(SUPPLIER_BILLS.VERSION.eq(version))
                        .and(SUPPLIER_BILLS.STATUS.eq("DRAFT"))
                        .execute()
                == 1;
    }

    public boolean markCancelled(UUID companyId, UUID id, int version, UUID actor) {
        return dsl.update(SUPPLIER_BILLS)
                        .set(SUPPLIER_BILLS.STATUS, "CANCELLED")
                        .set(SUPPLIER_BILLS.UPDATED_AT, OffsetDateTime.now())
                        .set(SUPPLIER_BILLS.UPDATED_BY, actor)
                        .set(SUPPLIER_BILLS.VERSION, version + 1)
                        .where(SUPPLIER_BILLS.COMPANY_ID.eq(companyId))
                        .and(SUPPLIER_BILLS.ID.eq(id))
                        .and(SUPPLIER_BILLS.VERSION.eq(version))
                        .and(SUPPLIER_BILLS.STATUS.eq("DRAFT"))
                        .execute()
                == 1;
    }

    public boolean delete(UUID companyId, UUID id, int version) {
        return dsl.deleteFrom(SUPPLIER_BILLS)
                        .where(SUPPLIER_BILLS.COMPANY_ID.eq(companyId))
                        .and(SUPPLIER_BILLS.ID.eq(id))
                        .and(SUPPLIER_BILLS.VERSION.eq(version))
                        .and(SUPPLIER_BILLS.STATUS.eq("DRAFT"))
                        .execute()
                == 1;
    }

    /** Total of the posted debit notes against a bill (what has been credited so far). */
    public BigDecimal creditedTotal(UUID companyId, UUID billId) {
        return dsl.select(DSL.coalesce(DSL.sum(SUPPLIER_BILLS.TOTAL), BigDecimal.ZERO))
                .from(SUPPLIER_BILLS)
                .where(SUPPLIER_BILLS.COMPANY_ID.eq(companyId))
                .and(SUPPLIER_BILLS.ORIGINAL_BILL_ID.eq(billId))
                .and(SUPPLIER_BILLS.STATUS.eq("POSTED"))
                .fetchSingle()
                .value1();
    }

    /** Whether the supplier already has a live document with this invoice number (PRC-5). */
    public boolean invoiceNumberTaken(
            UUID companyId, UUID supplierId, String documentType, String invoiceNumber, @Nullable UUID except) {
        return dsl.fetchExists(dsl.selectOne()
                .from(SUPPLIER_BILLS)
                .where(SUPPLIER_BILLS.COMPANY_ID.eq(companyId))
                .and(SUPPLIER_BILLS.SUPPLIER_ID.eq(supplierId))
                .and(SUPPLIER_BILLS.DOCUMENT_TYPE.eq(documentType))
                .and(DSL.lower(SUPPLIER_BILLS.SUPPLIER_INVOICE_NUMBER)
                        .eq(invoiceNumber.toLowerCase(java.util.Locale.ROOT)))
                .and(SUPPLIER_BILLS.STATUS.ne("CANCELLED"))
                .and(except == null ? DSL.noCondition() : SUPPLIER_BILLS.ID.ne(except)));
    }

    public boolean usesTaxCode(UUID companyId, UUID taxCodeId) {
        return dsl.fetchExists(dsl.selectOne()
                .from(SUPPLIER_BILL_LINES)
                .join(SUPPLIER_BILLS)
                .on(SUPPLIER_BILLS.ID.eq(SUPPLIER_BILL_LINES.SUPPLIER_BILL_ID))
                .where(SUPPLIER_BILL_LINES.COMPANY_ID.eq(companyId))
                .and(SUPPLIER_BILL_LINES.TAX_CODE_ID.eq(taxCodeId))
                .and(SUPPLIER_BILLS.STATUS.eq("POSTED")));
    }

    private static Map<org.jooq.Field<?>, Object> header(Header h) {
        Map<org.jooq.Field<?>, Object> values = new LinkedHashMap<>();
        values.put(SUPPLIER_BILLS.DOCUMENT_TYPE, h.documentType());
        values.put(SUPPLIER_BILLS.SUPPLIER_INVOICE_NUMBER, h.supplierInvoiceNumber());
        values.put(SUPPLIER_BILLS.SUPPLIER_ID, h.supplierId());
        values.put(SUPPLIER_BILLS.PURCHASE_ORDER_ID, h.purchaseOrderId());
        values.put(SUPPLIER_BILLS.ORIGINAL_BILL_ID, h.originalBillId());
        values.put(SUPPLIER_BILLS.BILL_DATE, h.billDate());
        values.put(SUPPLIER_BILLS.ACCOUNTING_DATE, h.accountingDate());
        values.put(SUPPLIER_BILLS.DUE_DATE, h.dueDate());
        values.put(SUPPLIER_BILLS.CURRENCY_CODE, h.currencyCode());
        values.put(SUPPLIER_BILLS.EXCHANGE_RATE, h.exchangeRate());
        values.put(SUPPLIER_BILLS.PRICES_INCLUDE_TAX, h.pricesIncludeTax());
        values.put(SUPPLIER_BILLS.PAYMENT_TERMS_ID, h.paymentTermsId());
        values.put(SUPPLIER_BILLS.SUBTOTAL, h.subtotal());
        values.put(SUPPLIER_BILLS.TAX_TOTAL, h.taxTotal());
        values.put(SUPPLIER_BILLS.TOTAL, h.total());
        values.put(SUPPLIER_BILLS.SUBTOTAL_BASE, h.subtotalBase());
        values.put(SUPPLIER_BILLS.TAX_TOTAL_BASE, h.taxTotalBase());
        values.put(SUPPLIER_BILLS.TOTAL_BASE, h.totalBase());
        values.put(SUPPLIER_BILLS.NOTES, h.notes());
        return values;
    }

    static ProcurementViews.SupplierBill toView(SupplierBillsRecord r) {
        return new ProcurementViews.SupplierBill(
                r.getId(),
                r.getCompanyId(),
                r.getDocumentType(),
                r.getNumber(),
                r.getSupplierInvoiceNumber(),
                r.getSupplierId(),
                r.getPurchaseOrderId(),
                r.getOriginalBillId(),
                r.getBillDate(),
                r.getAccountingDate(),
                r.getDueDate(),
                r.getCurrencyCode(),
                r.getExchangeRate(),
                r.getPricesIncludeTax(),
                r.getPaymentTermsId(),
                r.getStatus(),
                r.getMatchStatus(),
                r.getMatchOverrideBy(),
                r.getMatchOverrideReason(),
                r.getSubtotal(),
                r.getTaxTotal(),
                r.getTotal(),
                r.getSubtotalBase(),
                r.getTaxTotalBase(),
                r.getTotalBase(),
                r.getNotes(),
                r.getPostedAt(),
                r.getPostedBy(),
                r.getCreatedBy(),
                r.getCreatedAt(),
                r.getUpdatedAt(),
                r.getVersion());
    }

    static ProcurementViews.SupplierBillLine toLine(SupplierBillLinesRecord r) {
        return new ProcurementViews.SupplierBillLine(
                r.getId(),
                r.getLineNo(),
                r.getLineKind(),
                r.getPurchaseOrderLineId(),
                r.getGoodsReceiptLineId(),
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
                r.getTotalAmount(),
                r.getNetAmountBase(),
                r.getTaxAmountBase(),
                r.getReceiptValueBase(),
                r.getBranchId(),
                r.getDepartmentId());
    }
}
