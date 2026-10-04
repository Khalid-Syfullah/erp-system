package com.erp.accounting.persistence;

import static com.erp.db.accounting.Tables.PAYMENTS;
import static com.erp.db.accounting.Tables.PAYMENT_ALLOCATIONS;

import com.erp.accounting.application.AccountingListings;
import com.erp.accounting.application.AccountingViews;
import com.erp.db.accounting.tables.records.PaymentsRecord;
import com.erp.platform.jooq.KeysetPaginator;
import com.erp.platform.jooq.ListBinding;
import com.erp.platform.web.paging.ListQuery;
import com.erp.platform.web.paging.PageResponse;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;

/** Payments (customer receipts, supplier payments) and their allocations. */
@Repository
public class PaymentRepository {

    private static final ListBinding BINDING = ListBinding.builder(AccountingListings.PAYMENTS)
            .field("paymentDate", PAYMENTS.PAYMENT_DATE)
            .field("createdAt", PAYMENTS.CREATED_AT)
            .field("status", PAYMENTS.STATUS)
            .field("direction", PAYMENTS.DIRECTION)
            .field("partnerId", PAYMENTS.PARTNER_ID)
            .field("bankAccountId", PAYMENTS.BANK_ACCOUNT_ID)
            .field("number", PAYMENTS.NUMBER)
            .tiebreaker(PAYMENTS.ID)
            .search(List.of(PAYMENTS.NUMBER, PAYMENTS.REFERENCE))
            .build();

    /** Values a draft payment holds. */
    public record Values(
            String direction,
            UUID partnerId,
            String paymentKind,
            UUID bankAccountId,
            LocalDate paymentDate,
            String currencyCode,
            BigDecimal amount,
            BigDecimal exchangeRate,
            BigDecimal amountBase,
            String method,
            @Nullable String reference,
            @Nullable String notes,
            List<AccountingViews.RequestedAllocation> allocations) {}

    private final DSLContext dsl;
    private final KeysetPaginator paginator;
    private final JsonMapper json;

    public PaymentRepository(DSLContext dsl, KeysetPaginator paginator, JsonMapper json) {
        this.dsl = dsl;
        this.paginator = paginator;
        this.json = json;
    }

    public UUID insert(UUID companyId, Values v, UUID actor) {
        return dsl.insertInto(PAYMENTS)
                .set(PAYMENTS.COMPANY_ID, companyId)
                .set(PAYMENTS.DIRECTION, v.direction())
                .set(PAYMENTS.PARTNER_ID, v.partnerId())
                .set(PAYMENTS.PAYMENT_KIND, v.paymentKind())
                .set(PAYMENTS.BANK_ACCOUNT_ID, v.bankAccountId())
                .set(PAYMENTS.PAYMENT_DATE, v.paymentDate())
                .set(PAYMENTS.CURRENCY_CODE, v.currencyCode())
                .set(PAYMENTS.AMOUNT, v.amount())
                .set(PAYMENTS.EXCHANGE_RATE, v.exchangeRate())
                .set(PAYMENTS.AMOUNT_BASE, v.amountBase())
                .set(PAYMENTS.METHOD, v.method())
                .set(PAYMENTS.REFERENCE, v.reference())
                .set(PAYMENTS.NOTES, v.notes())
                .set(PAYMENTS.REQUESTED_ALLOCATIONS, write(v.allocations()))
                .set(PAYMENTS.CREATED_BY, actor)
                .set(PAYMENTS.UPDATED_BY, actor)
                .returning(PAYMENTS.ID)
                .fetchOne(PAYMENTS.ID);
    }

    public boolean updateDraft(UUID companyId, UUID id, int version, Values v, UUID actor) {
        return dsl.update(PAYMENTS)
                        .set(PAYMENTS.PARTNER_ID, v.partnerId())
                        .set(PAYMENTS.BANK_ACCOUNT_ID, v.bankAccountId())
                        .set(PAYMENTS.PAYMENT_DATE, v.paymentDate())
                        .set(PAYMENTS.CURRENCY_CODE, v.currencyCode())
                        .set(PAYMENTS.AMOUNT, v.amount())
                        .set(PAYMENTS.EXCHANGE_RATE, v.exchangeRate())
                        .set(PAYMENTS.AMOUNT_BASE, v.amountBase())
                        .set(PAYMENTS.METHOD, v.method())
                        .set(PAYMENTS.REFERENCE, v.reference())
                        .set(PAYMENTS.NOTES, v.notes())
                        .set(PAYMENTS.REQUESTED_ALLOCATIONS, write(v.allocations()))
                        .set(PAYMENTS.UPDATED_AT, OffsetDateTime.now())
                        .set(PAYMENTS.UPDATED_BY, actor)
                        .set(PAYMENTS.VERSION, version + 1)
                        .where(PAYMENTS.COMPANY_ID.eq(companyId))
                        .and(PAYMENTS.ID.eq(id))
                        .and(PAYMENTS.VERSION.eq(version))
                        .and(PAYMENTS.STATUS.eq("DRAFT"))
                        .execute()
                == 1;
    }

    /** DRAFT → POSTED with everything the posting produced (one update while still a draft). */
    public boolean markPosted(
            UUID companyId,
            UUID id,
            int version,
            String number,
            BigDecimal exchangeRate,
            BigDecimal amountBase,
            UUID journalEntryId,
            UUID openItemId,
            UUID actor) {
        return dsl.update(PAYMENTS)
                        .set(PAYMENTS.STATUS, "POSTED")
                        .set(PAYMENTS.NUMBER, number)
                        .set(PAYMENTS.EXCHANGE_RATE, exchangeRate)
                        .set(PAYMENTS.AMOUNT_BASE, amountBase)
                        .set(PAYMENTS.JOURNAL_ENTRY_ID, journalEntryId)
                        .set(PAYMENTS.OPEN_ITEM_ID, openItemId)
                        .set(PAYMENTS.POSTED_AT, OffsetDateTime.now())
                        .set(PAYMENTS.POSTED_BY, actor)
                        .set(PAYMENTS.UPDATED_AT, OffsetDateTime.now())
                        .set(PAYMENTS.UPDATED_BY, actor)
                        .set(PAYMENTS.VERSION, version + 1)
                        .where(PAYMENTS.COMPANY_ID.eq(companyId))
                        .and(PAYMENTS.ID.eq(id))
                        .and(PAYMENTS.VERSION.eq(version))
                        .and(PAYMENTS.STATUS.eq("DRAFT"))
                        .execute()
                == 1;
    }

    public boolean markVoided(UUID companyId, UUID id, int version, UUID voidEntryId, String reason, UUID actor) {
        return dsl.update(PAYMENTS)
                        .set(PAYMENTS.STATUS, "VOIDED")
                        .set(PAYMENTS.VOID_JOURNAL_ENTRY_ID, voidEntryId)
                        .set(PAYMENTS.VOIDED_REASON, reason)
                        .set(PAYMENTS.VOIDED_AT, OffsetDateTime.now())
                        .set(PAYMENTS.VOIDED_BY, actor)
                        .set(PAYMENTS.UPDATED_AT, OffsetDateTime.now())
                        .set(PAYMENTS.UPDATED_BY, actor)
                        .set(PAYMENTS.VERSION, version + 1)
                        .where(PAYMENTS.COMPANY_ID.eq(companyId))
                        .and(PAYMENTS.ID.eq(id))
                        .and(PAYMENTS.VERSION.eq(version))
                        .and(PAYMENTS.STATUS.eq("POSTED"))
                        .execute()
                == 1;
    }

    /** Bumps the version of a posted payment whose allocations changed (its ETag changes). */
    public boolean touch(UUID companyId, UUID id, int version, UUID actor) {
        return dsl.update(PAYMENTS)
                        .set(PAYMENTS.UPDATED_AT, OffsetDateTime.now())
                        .set(PAYMENTS.UPDATED_BY, actor)
                        .set(PAYMENTS.VERSION, version + 1)
                        .where(PAYMENTS.COMPANY_ID.eq(companyId))
                        .and(PAYMENTS.ID.eq(id))
                        .and(PAYMENTS.VERSION.eq(version))
                        .execute()
                == 1;
    }

    public boolean delete(UUID companyId, UUID id, int version) {
        return dsl.deleteFrom(PAYMENTS)
                        .where(PAYMENTS.COMPANY_ID.eq(companyId))
                        .and(PAYMENTS.ID.eq(id))
                        .and(PAYMENTS.VERSION.eq(version))
                        .and(PAYMENTS.STATUS.eq("DRAFT"))
                        .execute()
                == 1;
    }

    public Optional<AccountingViews.Payment> find(UUID companyId, UUID id) {
        return dsl.selectFrom(PAYMENTS)
                .where(PAYMENTS.COMPANY_ID.eq(companyId))
                .and(PAYMENTS.ID.eq(id))
                .fetchOptional(this::toView);
    }

    /** The payment {@code FOR UPDATE}: posting, voiding and allocating it serialize here. */
    public Optional<AccountingViews.Payment> lock(UUID companyId, UUID id) {
        return dsl.selectFrom(PAYMENTS)
                .where(PAYMENTS.COMPANY_ID.eq(companyId))
                .and(PAYMENTS.ID.eq(id))
                .forUpdate()
                .fetchOptional(this::toView);
    }

    public Optional<AccountingViews.Payment> byOpenItem(UUID companyId, UUID openItemId) {
        return dsl.selectFrom(PAYMENTS)
                .where(PAYMENTS.COMPANY_ID.eq(companyId))
                .and(PAYMENTS.OPEN_ITEM_ID.eq(openItemId))
                .fetchOptional(this::toView);
    }

    public PageResponse<AccountingViews.Payment> list(UUID companyId, ListQuery query) {
        return paginator.fetch(dsl, PAYMENTS, PAYMENTS.COMPANY_ID.eq(companyId), query, BINDING, this::toView);
    }

    public List<AccountingViews.Allocation> allocations(UUID companyId, UUID paymentId) {
        return dsl.selectFrom(PAYMENT_ALLOCATIONS)
                .where(PAYMENT_ALLOCATIONS.COMPANY_ID.eq(companyId))
                .and(PAYMENT_ALLOCATIONS.PAYMENT_ID.eq(paymentId))
                .orderBy(PAYMENT_ALLOCATIONS.CREATED_AT, PAYMENT_ALLOCATIONS.ID)
                .fetch(OpenItemRepository::toAllocation);
    }

    private JSONB write(List<AccountingViews.RequestedAllocation> allocations) {
        ArrayNode array = json.createArrayNode();
        allocations.forEach(a -> array.addObject()
                .put("openItemId", a.openItemId().toString())
                .put("amount", a.amount().toPlainString()));
        return JSONB.jsonb(json.writeValueAsString(array));
    }

    private List<AccountingViews.RequestedAllocation> read(@Nullable JSONB value) {
        List<AccountingViews.RequestedAllocation> result = new ArrayList<>();
        if (value == null) {
            return result;
        }
        for (JsonNode node : json.readTree(value.data())) {
            result.add(new AccountingViews.RequestedAllocation(
                    UUID.fromString(node.get("openItemId").asString()),
                    new BigDecimal(node.get("amount").asString())));
        }
        return result;
    }

    AccountingViews.Payment toView(PaymentsRecord r) {
        return new AccountingViews.Payment(
                r.getId(),
                r.getNumber(),
                r.getDirection(),
                r.getPartnerId(),
                r.getPaymentKind(),
                r.getBankAccountId(),
                r.getPaymentDate(),
                r.getCurrencyCode(),
                r.getAmount(),
                r.getExchangeRate(),
                r.getAmountBase(),
                r.getMethod(),
                r.getReference(),
                r.getNotes(),
                r.getStatus(),
                read(r.getRequestedAllocations()),
                r.getOpenItemId(),
                r.getJournalEntryId(),
                r.getVoidJournalEntryId(),
                r.getVoidedReason(),
                r.getPostedAt(),
                r.getCreatedBy(),
                r.getCreatedAt(),
                r.getUpdatedAt(),
                r.getVersion());
    }
}
