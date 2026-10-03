package com.erp.sales.persistence;

import static com.erp.db.sales.Tables.SALES_ORDERS;
import static com.erp.db.sales.Tables.SALES_ORDER_LINES;

import com.erp.db.sales.tables.records.SalesOrderLinesRecord;
import com.erp.db.sales.tables.records.SalesOrdersRecord;
import com.erp.platform.jooq.KeysetPaginator;
import com.erp.platform.jooq.ListBinding;
import com.erp.platform.web.paging.ListQuery;
import com.erp.platform.web.paging.PageResponse;
import com.erp.sales.application.SalesListings;
import com.erp.sales.application.SalesViews;
import java.math.BigDecimal;
import java.math.RoundingMode;
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
import org.jooq.Field;
import org.jooq.impl.DSL;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;

/** Sales orders and their lines. The header row is the lock that serializes fulfilment (§9). */
@Repository
public class SalesOrderRepository {

    private static final ListBinding BINDING = ListBinding.builder(SalesListings.ORDERS)
            .field("orderDate", SALES_ORDERS.ORDER_DATE)
            .field("createdAt", SALES_ORDERS.CREATED_AT)
            .field("total", SALES_ORDERS.TOTAL)
            .field("number", SALES_ORDERS.NUMBER)
            .field("status", SALES_ORDERS.STATUS)
            .field("invoiceStatus", SALES_ORDERS.INVOICE_STATUS)
            .field("customerId", SALES_ORDERS.CUSTOMER_ID)
            .field("warehouseId", SALES_ORDERS.WAREHOUSE_ID)
            .field("branchId", SALES_ORDERS.BRANCH_ID)
            .tiebreaker(SALES_ORDERS.ID)
            .search(List.of(SALES_ORDERS.NUMBER, SALES_ORDERS.CUSTOMER_REFERENCE, SALES_ORDERS.NOTES))
            .build();

    public record Header(
            UUID customerId,
            @Nullable UUID quotationId,
            UUID branchId,
            UUID warehouseId,
            LocalDate orderDate,
            @Nullable LocalDate requestedDate,
            @Nullable String customerReference,
            String currencyCode,
            @Nullable UUID priceListId,
            boolean pricesIncludeTax,
            @Nullable UUID paymentTermsId,
            String invoicePolicy,
            Map<String, Object> shippingAddress,
            Map<String, Object> billingAddress,
            BigDecimal subtotal,
            BigDecimal taxTotal,
            BigDecimal total,
            @Nullable String notes) {}

    /** Fields set when an order is confirmed. */
    public record Confirmation(
            String number,
            BigDecimal exchangeRate,
            String creditCheckResult,
            @Nullable UUID creditOverrideBy,
            @Nullable String creditOverrideReason) {}

    private final DSLContext dsl;
    private final KeysetPaginator paginator;
    private final AddressJson addresses;

    public SalesOrderRepository(DSLContext dsl, KeysetPaginator paginator, AddressJson addresses) {
        this.dsl = dsl;
        this.paginator = paginator;
        this.addresses = addresses;
    }

    public UUID insert(UUID companyId, Header h, UUID actor) {
        return dsl.insertInto(SALES_ORDERS)
                .set(SALES_ORDERS.COMPANY_ID, companyId)
                .set(header(h))
                .set(SALES_ORDERS.CREATED_BY, actor)
                .set(SALES_ORDERS.UPDATED_BY, actor)
                .returning(SALES_ORDERS.ID)
                .fetchOne(SALES_ORDERS.ID);
    }

    public void insertLines(UUID companyId, UUID orderId, List<QuotationRepository.NewLine> lines, UUID actor) {
        for (QuotationRepository.NewLine l : lines) {
            dsl.insertInto(SALES_ORDER_LINES)
                    .set(SALES_ORDER_LINES.COMPANY_ID, companyId)
                    .set(SALES_ORDER_LINES.SALES_ORDER_ID, orderId)
                    .set(SALES_ORDER_LINES.LINE_NO, l.lineNo())
                    .set(SALES_ORDER_LINES.VARIANT_ID, l.variantId())
                    .set(SALES_ORDER_LINES.DESCRIPTION, l.description())
                    .set(SALES_ORDER_LINES.IS_STOCKABLE, l.stockable())
                    .set(SALES_ORDER_LINES.QUANTITY, l.quantity())
                    .set(SALES_ORDER_LINES.UOM_ID, l.uomId())
                    .set(SALES_ORDER_LINES.QUANTITY_BASE, l.quantityBase())
                    .set(SALES_ORDER_LINES.UNIT_PRICE, l.unitPrice())
                    .set(SALES_ORDER_LINES.DISCOUNT_PERCENT, l.discountPercent())
                    .set(SALES_ORDER_LINES.TAX_CODE_ID, l.taxCodeId())
                    .set(SALES_ORDER_LINES.NET_AMOUNT, l.netAmount())
                    .set(SALES_ORDER_LINES.TAX_AMOUNT, l.taxAmount())
                    .set(SALES_ORDER_LINES.TOTAL_AMOUNT, l.totalAmount())
                    .set(SALES_ORDER_LINES.CREATED_BY, actor)
                    .set(SALES_ORDER_LINES.UPDATED_BY, actor)
                    .execute();
        }
    }

    public void deleteLines(UUID companyId, UUID orderId) {
        dsl.deleteFrom(SALES_ORDER_LINES)
                .where(SALES_ORDER_LINES.COMPANY_ID.eq(companyId))
                .and(SALES_ORDER_LINES.SALES_ORDER_ID.eq(orderId))
                .execute();
    }

    public Optional<SalesViews.SalesOrder> find(UUID companyId, UUID id) {
        return dsl.selectFrom(SALES_ORDERS)
                .where(SALES_ORDERS.COMPANY_ID.eq(companyId))
                .and(SALES_ORDERS.ID.eq(id))
                .fetchOptional(this::toView);
    }

    /** The header {@code FOR UPDATE}: every change and every fulfilment of the order serializes here. */
    public Optional<SalesViews.SalesOrder> lock(UUID companyId, UUID id) {
        return dsl.selectFrom(SALES_ORDERS)
                .where(SALES_ORDERS.COMPANY_ID.eq(companyId))
                .and(SALES_ORDERS.ID.eq(id))
                .forUpdate()
                .fetchOptional(this::toView);
    }

    public PageResponse<SalesViews.SalesOrder> list(UUID companyId, @Nullable Set<UUID> branchScope, ListQuery query) {
        Condition scope = SALES_ORDERS
                .COMPANY_ID
                .eq(companyId)
                .and(branchScope == null ? DSL.noCondition() : SALES_ORDERS.BRANCH_ID.in(branchScope));
        return paginator.fetch(dsl, SALES_ORDERS, scope, query, BINDING, this::toView);
    }

    public List<SalesViews.SalesOrderLine> lines(UUID companyId, UUID orderId) {
        return dsl.selectFrom(SALES_ORDER_LINES)
                .where(SALES_ORDER_LINES.COMPANY_ID.eq(companyId))
                .and(SALES_ORDER_LINES.SALES_ORDER_ID.eq(orderId))
                .orderBy(SALES_ORDER_LINES.LINE_NO)
                .fetch(SalesOrderRepository::toLine);
    }

    /** Lines by ID with their order ID. */
    public Map<UUID, LineOfOrder> linesById(UUID companyId, Collection<UUID> lineIds) {
        Map<UUID, LineOfOrder> result = new LinkedHashMap<>();
        if (lineIds.isEmpty()) {
            return result;
        }
        dsl.selectFrom(SALES_ORDER_LINES)
                .where(SALES_ORDER_LINES.COMPANY_ID.eq(companyId))
                .and(SALES_ORDER_LINES.ID.in(lineIds))
                .fetch()
                .forEach(r -> result.put(r.getId(), new LineOfOrder(r.getSalesOrderId(), toLine(r))));
        return result;
    }

    public record LineOfOrder(UUID salesOrderId, SalesViews.SalesOrderLine line) {}

    public boolean updateDraft(UUID companyId, UUID id, int version, UUID actor, Header h) {
        return dsl.update(SALES_ORDERS)
                        .set(header(h))
                        .set(SALES_ORDERS.UPDATED_AT, OffsetDateTime.now())
                        .set(SALES_ORDERS.UPDATED_BY, actor)
                        .set(SALES_ORDERS.VERSION, version + 1)
                        .where(SALES_ORDERS.COMPANY_ID.eq(companyId))
                        .and(SALES_ORDERS.ID.eq(id))
                        .and(SALES_ORDERS.VERSION.eq(version))
                        .and(SALES_ORDERS.STATUS.eq("DRAFT"))
                        .execute()
                == 1;
    }

    /** DRAFT → CONFIRMED with the number, rate and credit check result. */
    public boolean confirm(UUID companyId, UUID id, int version, UUID actor, Confirmation c) {
        return dsl.update(SALES_ORDERS)
                        .set(SALES_ORDERS.STATUS, "CONFIRMED")
                        .set(SALES_ORDERS.NUMBER, c.number())
                        .set(SALES_ORDERS.EXCHANGE_RATE, c.exchangeRate())
                        .set(SALES_ORDERS.CREDIT_CHECK_RESULT, c.creditCheckResult())
                        .set(SALES_ORDERS.CREDIT_OVERRIDE_BY, c.creditOverrideBy())
                        .set(SALES_ORDERS.CREDIT_OVERRIDE_REASON, c.creditOverrideReason())
                        .set(SALES_ORDERS.CONFIRMED_AT, OffsetDateTime.now())
                        .set(SALES_ORDERS.CONFIRMED_BY, actor)
                        .set(SALES_ORDERS.UPDATED_AT, OffsetDateTime.now())
                        .set(SALES_ORDERS.UPDATED_BY, actor)
                        .set(SALES_ORDERS.VERSION, version + 1)
                        .where(SALES_ORDERS.COMPANY_ID.eq(companyId))
                        .and(SALES_ORDERS.ID.eq(id))
                        .and(SALES_ORDERS.VERSION.eq(version))
                        .and(SALES_ORDERS.STATUS.eq("DRAFT"))
                        .execute()
                == 1;
    }

    /** State change with the fields that go with it (null leaves a field unchanged). */
    public boolean transition(
            UUID companyId,
            UUID id,
            int version,
            UUID actor,
            String status,
            @Nullable String invoiceStatus,
            @Nullable String cancelReason,
            @Nullable String closeReason) {
        var update = dsl.update(SALES_ORDERS)
                .set(SALES_ORDERS.STATUS, status)
                .set(SALES_ORDERS.UPDATED_AT, OffsetDateTime.now())
                .set(SALES_ORDERS.UPDATED_BY, actor)
                .set(SALES_ORDERS.VERSION, version + 1);
        if (invoiceStatus != null) {
            update = update.set(SALES_ORDERS.INVOICE_STATUS, invoiceStatus);
        }
        if (cancelReason != null) {
            update = update.set(SALES_ORDERS.CANCEL_REASON, cancelReason);
        }
        if (closeReason != null) {
            update = update.set(SALES_ORDERS.CLOSE_REASON, closeReason);
        }
        return update.where(SALES_ORDERS.COMPANY_ID.eq(companyId))
                        .and(SALES_ORDERS.ID.eq(id))
                        .and(SALES_ORDERS.VERSION.eq(version))
                        .execute()
                == 1;
    }

    /** The fulfilment counters and reservation link of a line (any order status). */
    public void updateLine(
            UUID companyId,
            UUID lineId,
            @Nullable UUID reservationId,
            BigDecimal reserved,
            BigDecimal delivered,
            BigDecimal returned,
            BigDecimal invoiced) {
        dsl.update(SALES_ORDER_LINES)
                .set(SALES_ORDER_LINES.RESERVATION_ID, reservationId)
                .set(SALES_ORDER_LINES.RESERVED_QUANTITY_BASE, reserved)
                .set(SALES_ORDER_LINES.DELIVERED_QUANTITY_BASE, delivered)
                .set(SALES_ORDER_LINES.RETURNED_QUANTITY_BASE, returned)
                .set(SALES_ORDER_LINES.INVOICED_QUANTITY_BASE, invoiced)
                .set(SALES_ORDER_LINES.UPDATED_AT, OffsetDateTime.now())
                .where(SALES_ORDER_LINES.COMPANY_ID.eq(companyId))
                .and(SALES_ORDER_LINES.ID.eq(lineId))
                .execute();
    }

    public boolean delete(UUID companyId, UUID id, int version) {
        return dsl.deleteFrom(SALES_ORDERS)
                        .where(SALES_ORDERS.COMPANY_ID.eq(companyId))
                        .and(SALES_ORDERS.ID.eq(id))
                        .and(SALES_ORDERS.VERSION.eq(version))
                        .and(SALES_ORDERS.STATUS.eq("DRAFT"))
                        .execute()
                == 1;
    }

    /**
     * SAL-2 exposure from orders: the uninvoiced part of the customer's other confirmed, open orders
     * (gross, converted at each order's confirmation rate), base currency.
     */
    public BigDecimal uninvoicedOrdersBase(UUID companyId, UUID customerId, UUID exceptOrderId, int scale) {
        Field<BigDecimal> open = DSL.greatest(
                        DSL.inline(BigDecimal.ZERO),
                        SALES_ORDER_LINES.QUANTITY_BASE.sub(SALES_ORDER_LINES.INVOICED_QUANTITY_BASE))
                .div(SALES_ORDER_LINES.QUANTITY_BASE)
                .mul(SALES_ORDER_LINES.TOTAL_AMOUNT)
                .mul(SALES_ORDERS.EXCHANGE_RATE);
        BigDecimal sum = dsl.select(DSL.sum(open))
                .from(SALES_ORDER_LINES)
                .join(SALES_ORDERS)
                .on(SALES_ORDERS.ID.eq(SALES_ORDER_LINES.SALES_ORDER_ID))
                .where(SALES_ORDERS.COMPANY_ID.eq(companyId))
                .and(SALES_ORDERS.CUSTOMER_ID.eq(customerId))
                .and(SALES_ORDERS.ID.ne(exceptOrderId))
                .and(SALES_ORDERS.STATUS.in("CONFIRMED", "PARTIALLY_DELIVERED", "DELIVERED"))
                .fetchOne(0, BigDecimal.class);
        return sum == null ? BigDecimal.ZERO : sum.setScale(scale, RoundingMode.HALF_UP);
    }

    /**
     * Serializes credit checks of one customer (SAL-2) until the transaction ends, so that two orders
     * confirmed at once cannot both fit under the same remaining credit.
     */
    public void lockCustomerCredit(UUID companyId, UUID customerId) {
        dsl.execute(
                "SELECT pg_advisory_xact_lock(hashtext('sales.customer_credit'), hashtext(?))",
                companyId + ":" + customerId);
    }

    public boolean usesBranch(UUID companyId, UUID branchId) {
        return dsl.fetchExists(dsl.selectOne()
                .from(SALES_ORDERS)
                .where(SALES_ORDERS.COMPANY_ID.eq(companyId))
                .and(SALES_ORDERS.BRANCH_ID.eq(branchId))
                .and(SALES_ORDERS.STATUS.notIn("CLOSED", "CANCELLED")));
    }

    public boolean usesTaxCode(UUID companyId, UUID taxCodeId) {
        return dsl.fetchExists(dsl.selectOne()
                .from(SALES_ORDER_LINES)
                .join(SALES_ORDERS)
                .on(SALES_ORDERS.ID.eq(SALES_ORDER_LINES.SALES_ORDER_ID))
                .where(SALES_ORDER_LINES.COMPANY_ID.eq(companyId))
                .and(SALES_ORDER_LINES.TAX_CODE_ID.eq(taxCodeId))
                .and(SALES_ORDERS.STATUS.ne("DRAFT")));
    }

    private Map<Field<?>, Object> header(Header h) {
        Map<Field<?>, Object> values = new LinkedHashMap<>();
        values.put(SALES_ORDERS.CUSTOMER_ID, h.customerId());
        values.put(SALES_ORDERS.QUOTATION_ID, h.quotationId());
        values.put(SALES_ORDERS.BRANCH_ID, h.branchId());
        values.put(SALES_ORDERS.WAREHOUSE_ID, h.warehouseId());
        values.put(SALES_ORDERS.ORDER_DATE, h.orderDate());
        values.put(SALES_ORDERS.REQUESTED_DATE, h.requestedDate());
        values.put(SALES_ORDERS.CUSTOMER_REFERENCE, h.customerReference());
        values.put(SALES_ORDERS.CURRENCY_CODE, h.currencyCode());
        values.put(SALES_ORDERS.PRICE_LIST_ID, h.priceListId());
        values.put(SALES_ORDERS.PRICES_INCLUDE_TAX, h.pricesIncludeTax());
        values.put(SALES_ORDERS.PAYMENT_TERMS_ID, h.paymentTermsId());
        values.put(SALES_ORDERS.INVOICE_POLICY, h.invoicePolicy());
        values.put(SALES_ORDERS.SHIPPING_ADDRESS, addresses.write(h.shippingAddress()));
        values.put(SALES_ORDERS.BILLING_ADDRESS, addresses.write(h.billingAddress()));
        values.put(SALES_ORDERS.SUBTOTAL, h.subtotal());
        values.put(SALES_ORDERS.TAX_TOTAL, h.taxTotal());
        values.put(SALES_ORDERS.TOTAL, h.total());
        values.put(SALES_ORDERS.NOTES, h.notes());
        return values;
    }

    SalesViews.SalesOrder toView(SalesOrdersRecord r) {
        return new SalesViews.SalesOrder(
                r.getId(),
                r.getCompanyId(),
                r.getNumber(),
                r.getCustomerId(),
                r.getQuotationId(),
                r.getBranchId(),
                r.getWarehouseId(),
                r.getOrderDate(),
                r.getRequestedDate(),
                r.getCustomerReference(),
                r.getCurrencyCode(),
                r.getPriceListId(),
                r.getPricesIncludeTax(),
                r.getPaymentTermsId(),
                r.getInvoicePolicy(),
                addresses.read(r.getShippingAddress()),
                addresses.read(r.getBillingAddress()),
                r.getStatus(),
                r.getInvoiceStatus(),
                r.getExchangeRate(),
                r.getCreditCheckResult(),
                r.getCreditOverrideBy(),
                r.getCreditOverrideReason(),
                r.getSubtotal(),
                r.getTaxTotal(),
                r.getTotal(),
                r.getConfirmedAt(),
                r.getConfirmedBy(),
                r.getCancelReason(),
                r.getCloseReason(),
                r.getNotes(),
                r.getCreatedBy(),
                r.getCreatedAt(),
                r.getUpdatedAt(),
                r.getVersion());
    }

    static SalesViews.SalesOrderLine toLine(SalesOrderLinesRecord r) {
        return new SalesViews.SalesOrderLine(
                new SalesViews.Line(
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
                        r.getTotalAmount()),
                r.getIsStockable(),
                r.getReservationId(),
                r.getReservedQuantityBase(),
                r.getDeliveredQuantityBase(),
                r.getReturnedQuantityBase(),
                r.getInvoicedQuantityBase());
    }
}
