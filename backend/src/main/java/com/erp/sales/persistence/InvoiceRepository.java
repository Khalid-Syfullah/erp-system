package com.erp.sales.persistence;

import static com.erp.db.sales.Tables.INVOICES;
import static com.erp.db.sales.Tables.INVOICE_LINES;
import static com.erp.db.sales.Tables.INVOICE_TAXES;

import com.erp.db.sales.tables.records.InvoiceLinesRecord;
import com.erp.db.sales.tables.records.InvoicesRecord;
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
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;

/** Customer invoices and credit notes with their lines and tax summary. */
@Repository
public class InvoiceRepository {

    private static final ListBinding BINDING = ListBinding.builder(SalesListings.INVOICES)
            .field("invoiceDate", INVOICES.INVOICE_DATE)
            .field("dueDate", INVOICES.DUE_DATE)
            .field("createdAt", INVOICES.CREATED_AT)
            .field("total", INVOICES.TOTAL)
            .field("documentType", INVOICES.DOCUMENT_TYPE)
            .field("status", INVOICES.STATUS)
            .field("customerId", INVOICES.CUSTOMER_ID)
            .field("salesOrderId", INVOICES.SALES_ORDER_ID)
            .field("originalInvoiceId", INVOICES.ORIGINAL_INVOICE_ID)
            .field("number", INVOICES.NUMBER)
            .tiebreaker(INVOICES.ID)
            .search(List.of(INVOICES.NUMBER, INVOICES.NOTES))
            .build();

    public record Header(
            String documentType,
            UUID customerId,
            @Nullable UUID salesOrderId,
            @Nullable UUID originalInvoiceId,
            @Nullable UUID salesReturnId,
            LocalDate invoiceDate,
            LocalDate accountingDate,
            LocalDate dueDate,
            String currencyCode,
            BigDecimal exchangeRate,
            boolean pricesIncludeTax,
            @Nullable UUID paymentTermsId,
            Map<String, Object> billingAddress,
            @Nullable String customerTaxRegistrationNo,
            BigDecimal subtotal,
            BigDecimal taxTotal,
            BigDecimal total,
            BigDecimal subtotalBase,
            BigDecimal taxTotalBase,
            BigDecimal totalBase,
            @Nullable String notes) {}

    public record NewLine(
            int lineNo,
            @Nullable UUID salesOrderLineId,
            @Nullable UUID deliveryLineId,
            @Nullable UUID originalInvoiceLineId,
            @Nullable UUID salesReturnLineId,
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
    private final AddressJson addresses;

    public InvoiceRepository(DSLContext dsl, KeysetPaginator paginator, AddressJson addresses) {
        this.dsl = dsl;
        this.paginator = paginator;
        this.addresses = addresses;
    }

    public UUID insert(UUID companyId, Header h, UUID actor) {
        return dsl.insertInto(INVOICES)
                .set(INVOICES.COMPANY_ID, companyId)
                .set(header(h))
                .set(INVOICES.CREATED_BY, actor)
                .set(INVOICES.UPDATED_BY, actor)
                .returning(INVOICES.ID)
                .fetchOne(INVOICES.ID);
    }

    public boolean updateDraft(UUID companyId, UUID id, int version, UUID actor, Header h) {
        return dsl.update(INVOICES)
                        .set(header(h))
                        .set(INVOICES.UPDATED_AT, OffsetDateTime.now())
                        .set(INVOICES.UPDATED_BY, actor)
                        .set(INVOICES.VERSION, version + 1)
                        .where(INVOICES.COMPANY_ID.eq(companyId))
                        .and(INVOICES.ID.eq(id))
                        .and(INVOICES.VERSION.eq(version))
                        .and(INVOICES.STATUS.eq("DRAFT"))
                        .execute()
                == 1;
    }

    public void insertLines(UUID companyId, UUID invoiceId, List<NewLine> lines, UUID actor) {
        for (NewLine l : lines) {
            dsl.insertInto(INVOICE_LINES)
                    .set(INVOICE_LINES.COMPANY_ID, companyId)
                    .set(INVOICE_LINES.INVOICE_ID, invoiceId)
                    .set(INVOICE_LINES.LINE_NO, l.lineNo())
                    .set(INVOICE_LINES.SALES_ORDER_LINE_ID, l.salesOrderLineId())
                    .set(INVOICE_LINES.DELIVERY_LINE_ID, l.deliveryLineId())
                    .set(INVOICE_LINES.ORIGINAL_INVOICE_LINE_ID, l.originalInvoiceLineId())
                    .set(INVOICE_LINES.SALES_RETURN_LINE_ID, l.salesReturnLineId())
                    .set(INVOICE_LINES.VARIANT_ID, l.variantId())
                    .set(INVOICE_LINES.DESCRIPTION, l.description())
                    .set(INVOICE_LINES.QUANTITY, l.quantity())
                    .set(INVOICE_LINES.UOM_ID, l.uomId())
                    .set(INVOICE_LINES.QUANTITY_BASE, l.quantityBase())
                    .set(INVOICE_LINES.UNIT_PRICE, l.unitPrice())
                    .set(INVOICE_LINES.DISCOUNT_PERCENT, l.discountPercent())
                    .set(INVOICE_LINES.TAX_CODE_ID, l.taxCodeId())
                    .set(INVOICE_LINES.NET_AMOUNT, l.netAmount())
                    .set(INVOICE_LINES.TAX_AMOUNT, l.taxAmount())
                    .set(INVOICE_LINES.TOTAL_AMOUNT, l.totalAmount())
                    .set(INVOICE_LINES.NET_AMOUNT_BASE, l.netAmountBase())
                    .set(INVOICE_LINES.TAX_AMOUNT_BASE, l.taxAmountBase())
                    .set(INVOICE_LINES.BRANCH_ID, l.branchId())
                    .set(INVOICE_LINES.DEPARTMENT_ID, l.departmentId())
                    .set(INVOICE_LINES.CREATED_BY, actor)
                    .set(INVOICE_LINES.UPDATED_BY, actor)
                    .execute();
        }
    }

    public void deleteLines(UUID companyId, UUID invoiceId) {
        dsl.deleteFrom(INVOICE_LINES)
                .where(INVOICE_LINES.COMPANY_ID.eq(companyId))
                .and(INVOICE_LINES.INVOICE_ID.eq(invoiceId))
                .execute();
    }

    public void replaceTaxes(UUID companyId, UUID invoiceId, List<SalesViews.InvoiceTax> taxes) {
        dsl.deleteFrom(INVOICE_TAXES)
                .where(INVOICE_TAXES.COMPANY_ID.eq(companyId))
                .and(INVOICE_TAXES.INVOICE_ID.eq(invoiceId))
                .execute();
        for (SalesViews.InvoiceTax t : taxes) {
            dsl.insertInto(INVOICE_TAXES)
                    .set(INVOICE_TAXES.COMPANY_ID, companyId)
                    .set(INVOICE_TAXES.INVOICE_ID, invoiceId)
                    .set(INVOICE_TAXES.TAX_CODE_ID, t.taxCodeId())
                    .set(INVOICE_TAXES.RATE_PERCENT, t.ratePercent())
                    .set(INVOICE_TAXES.TAXABLE_AMOUNT, t.taxableAmount())
                    .set(INVOICE_TAXES.TAX_AMOUNT, t.taxAmount())
                    .set(INVOICE_TAXES.TAXABLE_AMOUNT_BASE, t.taxableAmountBase())
                    .set(INVOICE_TAXES.TAX_AMOUNT_BASE, t.taxAmountBase())
                    .execute();
        }
    }

    public Optional<SalesViews.Invoice> find(UUID companyId, UUID id) {
        return dsl.selectFrom(INVOICES)
                .where(INVOICES.COMPANY_ID.eq(companyId))
                .and(INVOICES.ID.eq(id))
                .fetchOptional(this::toView);
    }

    public Optional<SalesViews.Invoice> lock(UUID companyId, UUID id) {
        return dsl.selectFrom(INVOICES)
                .where(INVOICES.COMPANY_ID.eq(companyId))
                .and(INVOICES.ID.eq(id))
                .forUpdate()
                .fetchOptional(this::toView);
    }

    public PageResponse<SalesViews.Invoice> list(UUID companyId, ListQuery query) {
        return paginator.fetch(dsl, INVOICES, INVOICES.COMPANY_ID.eq(companyId), query, BINDING, this::toView);
    }

    public List<SalesViews.InvoiceLine> lines(UUID companyId, UUID invoiceId) {
        return dsl.selectFrom(INVOICE_LINES)
                .where(INVOICE_LINES.COMPANY_ID.eq(companyId))
                .and(INVOICE_LINES.INVOICE_ID.eq(invoiceId))
                .orderBy(INVOICE_LINES.LINE_NO)
                .fetch(InvoiceRepository::toLine);
    }

    public List<SalesViews.InvoiceTax> taxes(UUID companyId, UUID invoiceId) {
        return dsl.selectFrom(INVOICE_TAXES)
                .where(INVOICE_TAXES.COMPANY_ID.eq(companyId))
                .and(INVOICE_TAXES.INVOICE_ID.eq(invoiceId))
                .orderBy(INVOICE_TAXES.TAX_CODE_ID)
                .fetch(r -> new SalesViews.InvoiceTax(
                        r.getTaxCodeId(),
                        r.getRatePercent(),
                        r.getTaxableAmount(),
                        r.getTaxAmount(),
                        r.getTaxableAmountBase(),
                        r.getTaxAmountBase()));
    }

    /** Lines by ID with their invoice header. */
    public Map<UUID, LineOfInvoice> linesById(UUID companyId, Collection<UUID> lineIds) {
        Map<UUID, LineOfInvoice> result = new LinkedHashMap<>();
        if (lineIds.isEmpty()) {
            return result;
        }
        dsl.select()
                .from(INVOICE_LINES)
                .join(INVOICES)
                .on(INVOICES.ID.eq(INVOICE_LINES.INVOICE_ID))
                .where(INVOICE_LINES.COMPANY_ID.eq(companyId))
                .and(INVOICE_LINES.ID.in(lineIds))
                .fetch()
                .forEach(r -> result.put(
                        r.get(INVOICE_LINES.ID),
                        new LineOfInvoice(toView(r.into(INVOICES)), toLine(r.into(INVOICE_LINES)))));
        return result;
    }

    public record LineOfInvoice(SalesViews.Invoice invoice, SalesViews.InvoiceLine line) {}

    public boolean markPosted(UUID companyId, UUID id, int version, UUID actor, String number) {
        return dsl.update(INVOICES)
                        .set(INVOICES.STATUS, "POSTED")
                        .set(INVOICES.NUMBER, number)
                        .set(INVOICES.POSTED_AT, OffsetDateTime.now())
                        .set(INVOICES.POSTED_BY, actor)
                        .set(INVOICES.UPDATED_AT, OffsetDateTime.now())
                        .set(INVOICES.UPDATED_BY, actor)
                        .set(INVOICES.VERSION, version + 1)
                        .where(INVOICES.COMPANY_ID.eq(companyId))
                        .and(INVOICES.ID.eq(id))
                        .and(INVOICES.VERSION.eq(version))
                        .and(INVOICES.STATUS.eq("DRAFT"))
                        .execute()
                == 1;
    }

    public boolean markCancelled(UUID companyId, UUID id, int version, UUID actor) {
        return dsl.update(INVOICES)
                        .set(INVOICES.STATUS, "CANCELLED")
                        .set(INVOICES.UPDATED_AT, OffsetDateTime.now())
                        .set(INVOICES.UPDATED_BY, actor)
                        .set(INVOICES.VERSION, version + 1)
                        .where(INVOICES.COMPANY_ID.eq(companyId))
                        .and(INVOICES.ID.eq(id))
                        .and(INVOICES.VERSION.eq(version))
                        .and(INVOICES.STATUS.eq("DRAFT"))
                        .execute()
                == 1;
    }

    public boolean delete(UUID companyId, UUID id, int version) {
        return dsl.deleteFrom(INVOICES)
                        .where(INVOICES.COMPANY_ID.eq(companyId))
                        .and(INVOICES.ID.eq(id))
                        .and(INVOICES.VERSION.eq(version))
                        .and(INVOICES.STATUS.eq("DRAFT"))
                        .execute()
                == 1;
    }

    public void updateCredited(UUID companyId, UUID lineId, BigDecimal credited) {
        dsl.update(INVOICE_LINES)
                .set(INVOICE_LINES.CREDITED_QUANTITY_BASE, credited)
                .set(INVOICE_LINES.UPDATED_AT, OffsetDateTime.now())
                .where(INVOICE_LINES.COMPANY_ID.eq(companyId))
                .and(INVOICE_LINES.ID.eq(lineId))
                .execute();
    }

    /**
     * Draft invoices of the order (cancelling the order cancels them), locked {@code NOWAIT}: a draft being posted
     * holds its row and waits for the order the caller has locked, so waiting here would deadlock
     * (DATABASE.md §9). The caller fails at once with {@code 409 RESOURCE_BUSY}; the posting goes ahead.
     */
    public List<SalesViews.Invoice> draftsOfOrder(UUID companyId, UUID orderId) {
        return dsl.selectFrom(INVOICES)
                .where(INVOICES.COMPANY_ID.eq(companyId))
                .and(INVOICES.SALES_ORDER_ID.eq(orderId))
                .and(INVOICES.STATUS.eq("DRAFT"))
                .orderBy(INVOICES.ID)
                .forUpdate()
                .noWait()
                .fetch(this::toView);
    }

    public boolean usesTaxCode(UUID companyId, UUID taxCodeId) {
        return dsl.fetchExists(dsl.selectOne()
                .from(INVOICE_LINES)
                .join(INVOICES)
                .on(INVOICES.ID.eq(INVOICE_LINES.INVOICE_ID))
                .where(INVOICE_LINES.COMPANY_ID.eq(companyId))
                .and(INVOICE_LINES.TAX_CODE_ID.eq(taxCodeId))
                .and(INVOICES.STATUS.eq("POSTED")));
    }

    public boolean usesBranch(UUID companyId, UUID branchId) {
        return dsl.fetchExists(dsl.selectOne()
                .from(INVOICE_LINES)
                .join(INVOICES)
                .on(INVOICES.ID.eq(INVOICE_LINES.INVOICE_ID))
                .where(INVOICE_LINES.COMPANY_ID.eq(companyId))
                .and(INVOICE_LINES.BRANCH_ID.eq(branchId))
                .and(INVOICES.STATUS.eq("DRAFT")));
    }

    public boolean usesDepartment(UUID companyId, UUID departmentId) {
        return dsl.fetchExists(dsl.selectOne()
                .from(INVOICE_LINES)
                .join(INVOICES)
                .on(INVOICES.ID.eq(INVOICE_LINES.INVOICE_ID))
                .where(INVOICE_LINES.COMPANY_ID.eq(companyId))
                .and(INVOICE_LINES.DEPARTMENT_ID.eq(departmentId))
                .and(INVOICES.STATUS.eq("DRAFT")));
    }

    private Map<Field<?>, Object> header(Header h) {
        Map<Field<?>, Object> values = new LinkedHashMap<>();
        values.put(INVOICES.DOCUMENT_TYPE, h.documentType());
        values.put(INVOICES.CUSTOMER_ID, h.customerId());
        values.put(INVOICES.SALES_ORDER_ID, h.salesOrderId());
        values.put(INVOICES.ORIGINAL_INVOICE_ID, h.originalInvoiceId());
        values.put(INVOICES.SALES_RETURN_ID, h.salesReturnId());
        values.put(INVOICES.INVOICE_DATE, h.invoiceDate());
        values.put(INVOICES.ACCOUNTING_DATE, h.accountingDate());
        values.put(INVOICES.DUE_DATE, h.dueDate());
        values.put(INVOICES.CURRENCY_CODE, h.currencyCode());
        values.put(INVOICES.EXCHANGE_RATE, h.exchangeRate());
        values.put(INVOICES.PRICES_INCLUDE_TAX, h.pricesIncludeTax());
        values.put(INVOICES.PAYMENT_TERMS_ID, h.paymentTermsId());
        values.put(INVOICES.BILLING_ADDRESS, addresses.write(h.billingAddress()));
        values.put(INVOICES.CUSTOMER_TAX_REGISTRATION_NO, h.customerTaxRegistrationNo());
        values.put(INVOICES.SUBTOTAL, h.subtotal());
        values.put(INVOICES.TAX_TOTAL, h.taxTotal());
        values.put(INVOICES.TOTAL, h.total());
        values.put(INVOICES.SUBTOTAL_BASE, h.subtotalBase());
        values.put(INVOICES.TAX_TOTAL_BASE, h.taxTotalBase());
        values.put(INVOICES.TOTAL_BASE, h.totalBase());
        values.put(INVOICES.NOTES, h.notes());
        return values;
    }

    SalesViews.Invoice toView(InvoicesRecord r) {
        return new SalesViews.Invoice(
                r.getId(),
                r.getCompanyId(),
                r.getDocumentType(),
                r.getNumber(),
                r.getCustomerId(),
                r.getSalesOrderId(),
                r.getOriginalInvoiceId(),
                r.getSalesReturnId(),
                r.getInvoiceDate(),
                r.getAccountingDate(),
                r.getDueDate(),
                r.getCurrencyCode(),
                r.getExchangeRate(),
                r.getPricesIncludeTax(),
                r.getPaymentTermsId(),
                addresses.read(r.getBillingAddress()),
                r.getCustomerTaxRegistrationNo(),
                r.getStatus(),
                r.getSubtotal(),
                r.getTaxTotal(),
                r.getTotal(),
                r.getSubtotalBase(),
                r.getTaxTotalBase(),
                r.getTotalBase(),
                r.getNotes(),
                r.getPostedAt(),
                r.getCreatedBy(),
                r.getCreatedAt(),
                r.getUpdatedAt(),
                r.getVersion());
    }

    static SalesViews.InvoiceLine toLine(InvoiceLinesRecord r) {
        return new SalesViews.InvoiceLine(
                r.getId(),
                r.getInvoiceId(),
                r.getLineNo(),
                r.getSalesOrderLineId(),
                r.getDeliveryLineId(),
                r.getOriginalInvoiceLineId(),
                r.getSalesReturnLineId(),
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
                r.getBranchId(),
                r.getDepartmentId(),
                r.getCreditedQuantityBase());
    }
}
