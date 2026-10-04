package com.erp.accounting.persistence;

import static com.erp.db.accounting.Tables.OPEN_ITEMS;
import static com.erp.db.accounting.Tables.PAYMENT_ALLOCATIONS;

import com.erp.accounting.application.AccountingListings;
import com.erp.accounting.application.AccountingViews;
import com.erp.db.accounting.tables.records.OpenItemsRecord;
import com.erp.db.accounting.tables.records.PaymentAllocationsRecord;
import com.erp.platform.jooq.KeysetPaginator;
import com.erp.platform.jooq.ListBinding;
import com.erp.platform.web.paging.ListQuery;
import com.erp.platform.web.paging.PageResponse;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;

/** AR/AP open items (the subledger) and the allocations that settle them. */
@Repository
public class OpenItemRepository {

    private static final ListBinding BINDING = ListBinding.builder(AccountingListings.OPEN_ITEMS)
            .field("documentDate", OPEN_ITEMS.DOCUMENT_DATE)
            .field("dueDate", OPEN_ITEMS.DUE_DATE)
            .field("createdAt", OPEN_ITEMS.CREATED_AT)
            .field("kind", OPEN_ITEMS.KIND)
            .field("status", OPEN_ITEMS.STATUS)
            .field("partnerId", OPEN_ITEMS.PARTNER_ID)
            .field("currencyCode", OPEN_ITEMS.CURRENCY_CODE)
            .field("sourceModule", OPEN_ITEMS.SOURCE_MODULE)
            .field("sourceType", OPEN_ITEMS.SOURCE_TYPE)
            .field("sourceId", OPEN_ITEMS.SOURCE_ID)
            .field("documentNumber", OPEN_ITEMS.DOCUMENT_NUMBER)
            .tiebreaker(OPEN_ITEMS.ID)
            .build();

    /** A new open item; amounts signed (+ raises the control account's balance). */
    public record NewItem(
            String kind,
            UUID partnerId,
            UUID accountId,
            String sourceModule,
            String sourceType,
            UUID sourceId,
            String documentNumber,
            LocalDate documentDate,
            LocalDate dueDate,
            String currencyCode,
            BigDecimal amount,
            BigDecimal amountBase,
            BigDecimal exchangeRate) {}

    public record NewAllocation(
            @Nullable UUID paymentId,
            UUID openItemId,
            UUID counterOpenItemId,
            LocalDate allocationDate,
            BigDecimal amount,
            BigDecimal amountBase,
            BigDecimal fxDifferenceBase,
            @Nullable UUID journalEntryId) {}

    private final DSLContext dsl;
    private final KeysetPaginator paginator;

    public OpenItemRepository(DSLContext dsl, KeysetPaginator paginator) {
        this.dsl = dsl;
        this.paginator = paginator;
    }

    public UUID newId() {
        return dsl.fetchSingle("SELECT uuidv7()").get(0, UUID.class);
    }

    public UUID insert(UUID companyId, NewItem i, UUID journalEntryId, @Nullable UUID actor) {
        return dsl.insertInto(OPEN_ITEMS)
                .set(OPEN_ITEMS.COMPANY_ID, companyId)
                .set(OPEN_ITEMS.KIND, i.kind())
                .set(OPEN_ITEMS.PARTNER_ID, i.partnerId())
                .set(OPEN_ITEMS.ACCOUNT_ID, i.accountId())
                .set(OPEN_ITEMS.SOURCE_MODULE, i.sourceModule())
                .set(OPEN_ITEMS.SOURCE_TYPE, i.sourceType())
                .set(OPEN_ITEMS.SOURCE_ID, i.sourceId())
                .set(OPEN_ITEMS.DOCUMENT_NUMBER, i.documentNumber())
                .set(OPEN_ITEMS.DOCUMENT_DATE, i.documentDate())
                .set(OPEN_ITEMS.DUE_DATE, i.dueDate())
                .set(OPEN_ITEMS.CURRENCY_CODE, i.currencyCode())
                .set(OPEN_ITEMS.ORIGINAL_AMOUNT, i.amount())
                .set(OPEN_ITEMS.OPEN_AMOUNT, i.amount())
                .set(OPEN_ITEMS.ORIGINAL_AMOUNT_BASE, i.amountBase())
                .set(OPEN_ITEMS.OPEN_AMOUNT_BASE, i.amountBase())
                .set(OPEN_ITEMS.EXCHANGE_RATE, i.exchangeRate())
                .set(OPEN_ITEMS.JOURNAL_ENTRY_ID, journalEntryId)
                .set(OPEN_ITEMS.CREATED_BY, actor)
                .set(OPEN_ITEMS.UPDATED_BY, actor)
                .returning(OPEN_ITEMS.ID)
                .fetchOne(OPEN_ITEMS.ID);
    }

    public Optional<AccountingViews.OpenItem> find(UUID companyId, UUID id) {
        return dsl.selectFrom(OPEN_ITEMS)
                .where(OPEN_ITEMS.COMPANY_ID.eq(companyId))
                .and(OPEN_ITEMS.ID.eq(id))
                .fetchOptional(OpenItemRepository::toView);
    }

    public Optional<AccountingViews.OpenItem> bySource(UUID companyId, String module, String type, UUID sourceId) {
        return dsl.selectFrom(OPEN_ITEMS)
                .where(OPEN_ITEMS.COMPANY_ID.eq(companyId))
                .and(OPEN_ITEMS.SOURCE_MODULE.eq(module))
                .and(OPEN_ITEMS.SOURCE_TYPE.eq(type))
                .and(OPEN_ITEMS.SOURCE_ID.eq(sourceId))
                .fetchOptional(OpenItemRepository::toView);
    }

    /** Items {@code FOR UPDATE} in ID order (DATABASE.md §9: allocations lock items by ID). */
    public Map<UUID, AccountingViews.OpenItem> lock(UUID companyId, Collection<UUID> ids) {
        Map<UUID, AccountingViews.OpenItem> result = new LinkedHashMap<>();
        if (ids.isEmpty()) {
            return result;
        }
        dsl.selectFrom(OPEN_ITEMS)
                .where(OPEN_ITEMS.COMPANY_ID.eq(companyId))
                .and(OPEN_ITEMS.ID.in(ids))
                .orderBy(OPEN_ITEMS.ID)
                .forUpdate()
                .fetch(OpenItemRepository::toView)
                .forEach(i -> result.put(i.id(), i));
        return result;
    }

    /** New open amounts of an item; the status follows from them. */
    public void updateOpen(
            UUID companyId, UUID id, BigDecimal open, BigDecimal openBase, String status, @Nullable UUID actor) {
        dsl.update(OPEN_ITEMS)
                .set(OPEN_ITEMS.OPEN_AMOUNT, open)
                .set(OPEN_ITEMS.OPEN_AMOUNT_BASE, openBase)
                .set(OPEN_ITEMS.STATUS, status)
                .set(
                        OPEN_ITEMS.SETTLED_AT,
                        "SETTLED".equals(status) || "VOIDED".equals(status) ? OffsetDateTime.now() : null)
                .set(OPEN_ITEMS.UPDATED_AT, OffsetDateTime.now())
                .set(OPEN_ITEMS.UPDATED_BY, actor)
                .set(OPEN_ITEMS.VERSION, OPEN_ITEMS.VERSION.add(1))
                .where(OPEN_ITEMS.COMPANY_ID.eq(companyId))
                .and(OPEN_ITEMS.ID.eq(id))
                .execute();
    }

    public PageResponse<AccountingViews.OpenItem> list(UUID companyId, @Nullable String kind, ListQuery query) {
        Condition scope =
                OPEN_ITEMS.COMPANY_ID.eq(companyId).and(kind == null ? DSL.noCondition() : OPEN_ITEMS.KIND.eq(kind));
        return paginator.fetch(dsl, OPEN_ITEMS, scope, query, BINDING, OpenItemRepository::toView);
    }

    /** Σ open base amounts of a partner's items of a kind (signed). */
    public BigDecimal openBase(UUID companyId, String kind, UUID partnerId) {
        BigDecimal sum = dsl.select(DSL.sum(OPEN_ITEMS.OPEN_AMOUNT_BASE))
                .from(OPEN_ITEMS)
                .where(OPEN_ITEMS.COMPANY_ID.eq(companyId))
                .and(OPEN_ITEMS.KIND.eq(kind))
                .and(OPEN_ITEMS.PARTNER_ID.eq(partnerId))
                .fetchOne(0, BigDecimal.class);
        return sum == null ? BigDecimal.ZERO : sum;
    }

    /** Σ open base amounts per control account of a kind (signed). */
    public Map<UUID, BigDecimal> openBaseByAccount(UUID companyId, String kind) {
        Map<UUID, BigDecimal> result = new LinkedHashMap<>();
        dsl.select(OPEN_ITEMS.ACCOUNT_ID, DSL.sum(OPEN_ITEMS.OPEN_AMOUNT_BASE))
                .from(OPEN_ITEMS)
                .where(OPEN_ITEMS.COMPANY_ID.eq(companyId))
                .and(OPEN_ITEMS.KIND.eq(kind))
                .groupBy(OPEN_ITEMS.ACCOUNT_ID)
                .fetch()
                .forEach(r -> result.put(r.value1(), r.value2()));
        return result;
    }

    /** Items of a kind with their open amount as of a date (document date ≤ as of), for ageing. */
    public List<AgedItem> asOf(UUID companyId, String kind, LocalDate asOf, @Nullable UUID partnerId) {
        var a = PAYMENT_ALLOCATIONS;
        // Allocations effective on the date: made by then and not undone by then.
        Condition effective = a.ALLOCATION_DATE
                .le(asOf)
                .and(a.REVERSED_AT
                        .isNull()
                        .or(DSL.cast(a.REVERSED_AT, java.time.LocalDate.class).gt(asOf)));
        var settledAsTarget = DSL.select(DSL.coalesce(DSL.sum(a.AMOUNT), BigDecimal.ZERO))
                .from(a)
                .where(a.OPEN_ITEM_ID.eq(OPEN_ITEMS.ID))
                .and(effective)
                .asField("settled_target");
        var settledAsCounter = DSL.select(DSL.coalesce(DSL.sum(a.AMOUNT), BigDecimal.ZERO))
                .from(a)
                .where(a.COUNTER_OPEN_ITEM_ID.eq(OPEN_ITEMS.ID))
                .and(effective)
                .asField("settled_counter");
        return dsl.select(OPEN_ITEMS.asterisk(), settledAsTarget, settledAsCounter)
                .from(OPEN_ITEMS)
                .where(OPEN_ITEMS.COMPANY_ID.eq(companyId))
                .and(OPEN_ITEMS.KIND.eq(kind))
                .and(OPEN_ITEMS.DOCUMENT_DATE.le(asOf))
                .and(OPEN_ITEMS.STATUS.ne("VOIDED"))
                .and(partnerId == null ? DSL.noCondition() : OPEN_ITEMS.PARTNER_ID.eq(partnerId))
                .orderBy(OPEN_ITEMS.PARTNER_ID, OPEN_ITEMS.DUE_DATE, OPEN_ITEMS.ID)
                .fetch(r -> {
                    AccountingViews.OpenItem item = toView(r.into(OPEN_ITEMS));
                    BigDecimal settled =
                            r.get("settled_target", BigDecimal.class).add(r.get("settled_counter", BigDecimal.class));
                    BigDecimal open = item.originalAmount().signum() >= 0
                            ? item.originalAmount().subtract(settled)
                            : item.originalAmount().add(settled);
                    return new AgedItem(item, open);
                });
    }

    /** An item with its open amount (signed, document currency) as of a date. */
    public record AgedItem(AccountingViews.OpenItem item, BigDecimal openAsOf) {}

    // ---------------------------------------------------------------------------- allocations

    public UUID insertAllocation(UUID companyId, UUID id, NewAllocation a, @Nullable UUID actor) {
        return dsl.insertInto(PAYMENT_ALLOCATIONS)
                .set(PAYMENT_ALLOCATIONS.ID, id)
                .set(PAYMENT_ALLOCATIONS.COMPANY_ID, companyId)
                .set(PAYMENT_ALLOCATIONS.PAYMENT_ID, a.paymentId())
                .set(PAYMENT_ALLOCATIONS.OPEN_ITEM_ID, a.openItemId())
                .set(PAYMENT_ALLOCATIONS.COUNTER_OPEN_ITEM_ID, a.counterOpenItemId())
                .set(PAYMENT_ALLOCATIONS.ALLOCATION_DATE, a.allocationDate())
                .set(PAYMENT_ALLOCATIONS.AMOUNT, a.amount())
                .set(PAYMENT_ALLOCATIONS.AMOUNT_BASE, a.amountBase())
                .set(PAYMENT_ALLOCATIONS.FX_DIFFERENCE_BASE, a.fxDifferenceBase())
                .set(PAYMENT_ALLOCATIONS.JOURNAL_ENTRY_ID, a.journalEntryId())
                .set(PAYMENT_ALLOCATIONS.CREATED_BY, actor)
                .set(PAYMENT_ALLOCATIONS.UPDATED_BY, actor)
                .returning(PAYMENT_ALLOCATIONS.ID)
                .fetchOne(PAYMENT_ALLOCATIONS.ID);
    }

    public Optional<AccountingViews.Allocation> findAllocation(UUID companyId, UUID id) {
        return dsl.selectFrom(PAYMENT_ALLOCATIONS)
                .where(PAYMENT_ALLOCATIONS.COMPANY_ID.eq(companyId))
                .and(PAYMENT_ALLOCATIONS.ID.eq(id))
                .fetchOptional(OpenItemRepository::toAllocation);
    }

    public Optional<AccountingViews.Allocation> lockAllocation(UUID companyId, UUID id) {
        return dsl.selectFrom(PAYMENT_ALLOCATIONS)
                .where(PAYMENT_ALLOCATIONS.COMPANY_ID.eq(companyId))
                .and(PAYMENT_ALLOCATIONS.ID.eq(id))
                .forUpdate()
                .fetchOptional(OpenItemRepository::toAllocation);
    }

    public void markAllocationReversed(UUID companyId, UUID id, @Nullable UUID reversalEntryId, UUID actor) {
        dsl.update(PAYMENT_ALLOCATIONS)
                .set(PAYMENT_ALLOCATIONS.REVERSED_AT, OffsetDateTime.now())
                .set(PAYMENT_ALLOCATIONS.REVERSED_BY, actor)
                .set(PAYMENT_ALLOCATIONS.REVERSAL_JOURNAL_ENTRY_ID, reversalEntryId)
                .set(PAYMENT_ALLOCATIONS.UPDATED_AT, OffsetDateTime.now())
                .set(PAYMENT_ALLOCATIONS.UPDATED_BY, actor)
                .set(PAYMENT_ALLOCATIONS.VERSION, PAYMENT_ALLOCATIONS.VERSION.add(1))
                .where(PAYMENT_ALLOCATIONS.COMPANY_ID.eq(companyId))
                .and(PAYMENT_ALLOCATIONS.ID.eq(id))
                .and(PAYMENT_ALLOCATIONS.REVERSED_AT.isNull())
                .execute();
    }

    /** Allocations that involve an item on either side, oldest first. */
    public List<AccountingViews.Allocation> allocationsOf(UUID companyId, UUID itemId) {
        return dsl.selectFrom(PAYMENT_ALLOCATIONS)
                .where(PAYMENT_ALLOCATIONS.COMPANY_ID.eq(companyId))
                .and(PAYMENT_ALLOCATIONS
                        .OPEN_ITEM_ID
                        .eq(itemId)
                        .or(PAYMENT_ALLOCATIONS.COUNTER_OPEN_ITEM_ID.eq(itemId)))
                .orderBy(PAYMENT_ALLOCATIONS.CREATED_AT, PAYMENT_ALLOCATIONS.ID)
                .fetch(OpenItemRepository::toAllocation);
    }

    static AccountingViews.OpenItem toView(OpenItemsRecord r) {
        return new AccountingViews.OpenItem(
                r.getId(),
                r.getKind(),
                r.getPartnerId(),
                r.getAccountId(),
                r.getSourceModule(),
                r.getSourceType(),
                r.getSourceId(),
                r.getDocumentNumber(),
                r.getDocumentDate(),
                r.getDueDate(),
                r.getCurrencyCode(),
                r.getOriginalAmount(),
                r.getOpenAmount(),
                r.getOriginalAmountBase(),
                r.getOpenAmountBase(),
                r.getExchangeRate(),
                r.getJournalEntryId(),
                r.getStatus(),
                r.getSettledAt(),
                r.getCreatedAt(),
                r.getVersion());
    }

    static AccountingViews.Allocation toAllocation(PaymentAllocationsRecord r) {
        return new AccountingViews.Allocation(
                r.getId(),
                r.getPaymentId(),
                r.getOpenItemId(),
                r.getCounterOpenItemId(),
                r.getAllocationDate(),
                r.getAmount(),
                r.getAmountBase(),
                r.getFxDifferenceBase(),
                r.getJournalEntryId(),
                r.getReversedAt(),
                r.getReversalJournalEntryId(),
                r.getCreatedBy(),
                r.getCreatedAt());
    }
}
