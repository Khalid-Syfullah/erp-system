package com.erp.accounting.persistence;

import static com.erp.db.accounting.Tables.JOURNAL_ENTRIES;
import static com.erp.db.accounting.Tables.JOURNAL_LINES;

import com.erp.accounting.application.AccountingListings;
import com.erp.accounting.application.AccountingViews;
import com.erp.db.accounting.tables.records.JournalEntriesRecord;
import com.erp.db.accounting.tables.records.JournalLinesRecord;
import com.erp.platform.jooq.KeysetPaginator;
import com.erp.platform.jooq.ListBinding;
import com.erp.platform.web.paging.ListQuery;
import com.erp.platform.web.paging.PageResponse;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;

/**
 * Journal entries and their lines. Posting follows DATABASE.md §8.1: the header is inserted as
 * DRAFT, the lines are inserted and marked posted, then the header flips to POSTED; the triggers
 * forbid every later change except the link to a reversal.
 */
@Repository
public class EntryRepository {

    private static final ListBinding BINDING = ListBinding.builder(AccountingListings.JOURNAL_ENTRIES)
            .field("entryDate", JOURNAL_ENTRIES.ENTRY_DATE)
            .field("createdAt", JOURNAL_ENTRIES.CREATED_AT)
            .field("status", JOURNAL_ENTRIES.STATUS)
            .field("entryType", JOURNAL_ENTRIES.ENTRY_TYPE)
            .field("journalId", JOURNAL_ENTRIES.JOURNAL_ID)
            .field("periodId", JOURNAL_ENTRIES.PERIOD_ID)
            .field("sourceModule", JOURNAL_ENTRIES.SOURCE_MODULE)
            .field("sourceType", JOURNAL_ENTRIES.SOURCE_TYPE)
            .field("sourceId", JOURNAL_ENTRIES.SOURCE_ID)
            .field("number", JOURNAL_ENTRIES.NUMBER)
            .tiebreaker(JOURNAL_ENTRIES.ID)
            .search(List.of(JOURNAL_ENTRIES.NUMBER, JOURNAL_ENTRIES.DESCRIPTION, JOURNAL_ENTRIES.SOURCE_NUMBER))
            .build();

    /** Header values of an entry. */
    public record Header(
            UUID journalId,
            LocalDate entryDate,
            UUID periodId,
            String entryType,
            String description,
            String currencyCode,
            BigDecimal exchangeRate,
            @Nullable String sourceModule,
            @Nullable String sourceType,
            @Nullable UUID sourceId,
            @Nullable String sourceNumber,
            @Nullable UUID sourceEventId,
            @Nullable UUID reversalOfId) {}

    public record NewLine(
            int lineNo,
            UUID accountId,
            BigDecimal debit,
            BigDecimal credit,
            String currencyCode,
            BigDecimal amountCurrency,
            @Nullable UUID partnerId,
            @Nullable UUID branchId,
            @Nullable UUID departmentId,
            @Nullable UUID taxCodeId,
            @Nullable UUID openItemId,
            @Nullable String description) {}

    private final DSLContext dsl;
    private final KeysetPaginator paginator;

    public EntryRepository(DSLContext dsl, KeysetPaginator paginator) {
        this.dsl = dsl;
        this.paginator = paginator;
    }

    public UUID insertHeader(
            UUID companyId, Header h, BigDecimal totalDebit, BigDecimal totalCredit, @Nullable UUID actor) {
        return dsl.insertInto(JOURNAL_ENTRIES)
                .set(JOURNAL_ENTRIES.COMPANY_ID, companyId)
                .set(JOURNAL_ENTRIES.JOURNAL_ID, h.journalId())
                .set(JOURNAL_ENTRIES.ENTRY_DATE, h.entryDate())
                .set(JOURNAL_ENTRIES.PERIOD_ID, h.periodId())
                .set(JOURNAL_ENTRIES.ENTRY_TYPE, h.entryType())
                .set(JOURNAL_ENTRIES.DESCRIPTION, h.description())
                .set(JOURNAL_ENTRIES.CURRENCY_CODE, h.currencyCode())
                .set(JOURNAL_ENTRIES.EXCHANGE_RATE, h.exchangeRate())
                .set(JOURNAL_ENTRIES.SOURCE_MODULE, h.sourceModule())
                .set(JOURNAL_ENTRIES.SOURCE_TYPE, h.sourceType())
                .set(JOURNAL_ENTRIES.SOURCE_ID, h.sourceId())
                .set(JOURNAL_ENTRIES.SOURCE_NUMBER, h.sourceNumber())
                .set(JOURNAL_ENTRIES.SOURCE_EVENT_ID, h.sourceEventId())
                .set(JOURNAL_ENTRIES.REVERSAL_OF_ID, h.reversalOfId())
                .set(JOURNAL_ENTRIES.TOTAL_DEBIT, totalDebit)
                .set(JOURNAL_ENTRIES.TOTAL_CREDIT, totalCredit)
                .set(JOURNAL_ENTRIES.CREATED_BY, actor)
                .set(JOURNAL_ENTRIES.UPDATED_BY, actor)
                .returning(JOURNAL_ENTRIES.ID)
                .fetchOne(JOURNAL_ENTRIES.ID);
    }

    /** Edits a draft's header (and totals). */
    public boolean updateDraft(
            UUID companyId, UUID id, int version, Header h, BigDecimal totalDebit, BigDecimal totalCredit, UUID actor) {
        return dsl.update(JOURNAL_ENTRIES)
                        .set(JOURNAL_ENTRIES.JOURNAL_ID, h.journalId())
                        .set(JOURNAL_ENTRIES.ENTRY_DATE, h.entryDate())
                        .set(JOURNAL_ENTRIES.PERIOD_ID, h.periodId())
                        .set(JOURNAL_ENTRIES.DESCRIPTION, h.description())
                        .set(JOURNAL_ENTRIES.CURRENCY_CODE, h.currencyCode())
                        .set(JOURNAL_ENTRIES.EXCHANGE_RATE, h.exchangeRate())
                        .set(JOURNAL_ENTRIES.TOTAL_DEBIT, totalDebit)
                        .set(JOURNAL_ENTRIES.TOTAL_CREDIT, totalCredit)
                        .set(JOURNAL_ENTRIES.UPDATED_AT, OffsetDateTime.now())
                        .set(JOURNAL_ENTRIES.UPDATED_BY, actor)
                        .set(JOURNAL_ENTRIES.VERSION, version + 1)
                        .where(JOURNAL_ENTRIES.COMPANY_ID.eq(companyId))
                        .and(JOURNAL_ENTRIES.ID.eq(id))
                        .and(JOURNAL_ENTRIES.VERSION.eq(version))
                        .and(JOURNAL_ENTRIES.STATUS.eq("DRAFT"))
                        .execute()
                == 1;
    }

    public void insertLines(
            UUID companyId,
            UUID entryId,
            LocalDate entryDate,
            UUID periodId,
            List<NewLine> lines,
            @Nullable UUID actor) {
        for (NewLine l : lines) {
            dsl.insertInto(JOURNAL_LINES)
                    .set(JOURNAL_LINES.COMPANY_ID, companyId)
                    .set(JOURNAL_LINES.JOURNAL_ENTRY_ID, entryId)
                    .set(JOURNAL_LINES.LINE_NO, l.lineNo())
                    .set(JOURNAL_LINES.ACCOUNT_ID, l.accountId())
                    .set(JOURNAL_LINES.DEBIT, l.debit())
                    .set(JOURNAL_LINES.CREDIT, l.credit())
                    .set(JOURNAL_LINES.CURRENCY_CODE, l.currencyCode())
                    .set(JOURNAL_LINES.AMOUNT_CURRENCY, l.amountCurrency())
                    .set(JOURNAL_LINES.PARTNER_ID, l.partnerId())
                    .set(JOURNAL_LINES.BRANCH_ID, l.branchId())
                    .set(JOURNAL_LINES.DEPARTMENT_ID, l.departmentId())
                    .set(JOURNAL_LINES.TAX_CODE_ID, l.taxCodeId())
                    .set(JOURNAL_LINES.OPEN_ITEM_ID, l.openItemId())
                    .set(JOURNAL_LINES.DESCRIPTION, l.description())
                    .set(JOURNAL_LINES.ENTRY_DATE, entryDate)
                    .set(JOURNAL_LINES.PERIOD_ID, periodId)
                    .set(JOURNAL_LINES.CREATED_BY, actor)
                    .execute();
        }
    }

    /**
     * Sets the transaction-local flags the period trigger honors (DATABASE.md §8.1): posting into a
     * soft-closed period, and the year-end closing entry into a closed one. Only the posting engine
     * sets them, after its own permission checks, and clears them right after the posting.
     */
    public void postingFlags(boolean softClosed, boolean closingEntry) {
        dsl.execute(
                "SELECT set_config('app.allow_soft_closed', ?, true), set_config('app.allow_closing_entry', ?, true)",
                softClosed ? "on" : "",
                closingEntry ? "on" : "");
    }

    public void deleteLines(UUID companyId, UUID entryId) {
        dsl.deleteFrom(JOURNAL_LINES)
                .where(JOURNAL_LINES.COMPANY_ID.eq(companyId))
                .and(JOURNAL_LINES.JOURNAL_ENTRY_ID.eq(entryId))
                .execute();
    }

    /** Step 6 of the posting sequence: the lines are marked posted while the header is a draft. */
    public void markLinesPosted(UUID companyId, UUID entryId, LocalDate entryDate, UUID periodId) {
        dsl.update(JOURNAL_LINES)
                .set(JOURNAL_LINES.IS_POSTED, true)
                .set(JOURNAL_LINES.ENTRY_DATE, entryDate)
                .set(JOURNAL_LINES.PERIOD_ID, periodId)
                .where(JOURNAL_LINES.COMPANY_ID.eq(companyId))
                .and(JOURNAL_LINES.JOURNAL_ENTRY_ID.eq(entryId))
                .execute();
    }

    /** Step 7: the header flips to POSTED with its number; from here on the triggers freeze it. */
    public boolean markPosted(
            UUID companyId,
            UUID id,
            String number,
            BigDecimal totalDebit,
            BigDecimal totalCredit,
            @Nullable UUID actor) {
        return dsl.update(JOURNAL_ENTRIES)
                        .set(JOURNAL_ENTRIES.STATUS, "POSTED")
                        .set(JOURNAL_ENTRIES.NUMBER, number)
                        .set(JOURNAL_ENTRIES.TOTAL_DEBIT, totalDebit)
                        .set(JOURNAL_ENTRIES.TOTAL_CREDIT, totalCredit)
                        .set(JOURNAL_ENTRIES.POSTED_AT, OffsetDateTime.now())
                        .set(JOURNAL_ENTRIES.POSTED_BY, actor)
                        .set(JOURNAL_ENTRIES.UPDATED_AT, OffsetDateTime.now())
                        .set(JOURNAL_ENTRIES.UPDATED_BY, actor)
                        .set(JOURNAL_ENTRIES.VERSION, JOURNAL_ENTRIES.VERSION.add(1))
                        .where(JOURNAL_ENTRIES.COMPANY_ID.eq(companyId))
                        .and(JOURNAL_ENTRIES.ID.eq(id))
                        .and(JOURNAL_ENTRIES.STATUS.eq("DRAFT"))
                        .execute()
                == 1;
    }

    /** Links a posted entry to its reversal: the only change a posted entry allows. */
    public boolean setReversedBy(UUID companyId, UUID id, UUID reversalId, @Nullable UUID actor) {
        return dsl.update(JOURNAL_ENTRIES)
                        .set(JOURNAL_ENTRIES.REVERSED_BY_ID, reversalId)
                        .set(JOURNAL_ENTRIES.UPDATED_AT, OffsetDateTime.now())
                        .set(JOURNAL_ENTRIES.UPDATED_BY, actor)
                        .set(JOURNAL_ENTRIES.VERSION, JOURNAL_ENTRIES.VERSION.add(1))
                        .where(JOURNAL_ENTRIES.COMPANY_ID.eq(companyId))
                        .and(JOURNAL_ENTRIES.ID.eq(id))
                        .and(JOURNAL_ENTRIES.REVERSED_BY_ID.isNull())
                        .execute()
                == 1;
    }

    public boolean deleteDraft(UUID companyId, UUID id, int version) {
        return dsl.deleteFrom(JOURNAL_ENTRIES)
                        .where(JOURNAL_ENTRIES.COMPANY_ID.eq(companyId))
                        .and(JOURNAL_ENTRIES.ID.eq(id))
                        .and(JOURNAL_ENTRIES.VERSION.eq(version))
                        .and(JOURNAL_ENTRIES.STATUS.eq("DRAFT"))
                        .execute()
                == 1;
    }

    public Optional<AccountingViews.JournalEntry> find(UUID companyId, UUID id) {
        return dsl.selectFrom(JOURNAL_ENTRIES)
                .where(JOURNAL_ENTRIES.COMPANY_ID.eq(companyId))
                .and(JOURNAL_ENTRIES.ID.eq(id))
                .fetchOptional(EntryRepository::toView);
    }

    public Optional<AccountingViews.JournalEntry> lock(UUID companyId, UUID id) {
        return dsl.selectFrom(JOURNAL_ENTRIES)
                .where(JOURNAL_ENTRIES.COMPANY_ID.eq(companyId))
                .and(JOURNAL_ENTRIES.ID.eq(id))
                .forUpdate()
                .fetchOptional(EntryRepository::toView);
    }

    public Optional<AccountingViews.JournalEntry> findByEvent(UUID companyId, UUID eventId) {
        return dsl.selectFrom(JOURNAL_ENTRIES)
                .where(JOURNAL_ENTRIES.COMPANY_ID.eq(companyId))
                .and(JOURNAL_ENTRIES.SOURCE_EVENT_ID.eq(eventId))
                .fetchOptional(EntryRepository::toView);
    }

    /** The system entry booked for a source document, locked for a reversal. */
    public Optional<AccountingViews.JournalEntry> lockSystemEntry(
            UUID companyId, String module, String type, UUID sourceId) {
        return dsl.selectFrom(JOURNAL_ENTRIES)
                .where(JOURNAL_ENTRIES.COMPANY_ID.eq(companyId))
                .and(JOURNAL_ENTRIES.SOURCE_MODULE.eq(module))
                .and(JOURNAL_ENTRIES.SOURCE_TYPE.eq(type))
                .and(JOURNAL_ENTRIES.SOURCE_ID.eq(sourceId))
                .and(JOURNAL_ENTRIES.ENTRY_TYPE.eq("SYSTEM"))
                .forUpdate()
                .fetchOptional(EntryRepository::toView);
    }

    public List<AccountingViews.JournalEntry> bySource(UUID companyId, String module, String type, UUID sourceId) {
        return dsl.selectFrom(JOURNAL_ENTRIES)
                .where(JOURNAL_ENTRIES.COMPANY_ID.eq(companyId))
                .and(JOURNAL_ENTRIES.SOURCE_MODULE.eq(module))
                .and(JOURNAL_ENTRIES.SOURCE_TYPE.eq(type))
                .and(JOURNAL_ENTRIES.SOURCE_ID.eq(sourceId))
                .orderBy(JOURNAL_ENTRIES.CREATED_AT, JOURNAL_ENTRIES.ID)
                .fetch(EntryRepository::toView);
    }

    public List<AccountingViews.JournalLine> lines(UUID companyId, UUID entryId) {
        return dsl.selectFrom(JOURNAL_LINES)
                .where(JOURNAL_LINES.COMPANY_ID.eq(companyId))
                .and(JOURNAL_LINES.JOURNAL_ENTRY_ID.eq(entryId))
                .orderBy(JOURNAL_LINES.LINE_NO)
                .fetch(EntryRepository::toLine);
    }

    public PageResponse<AccountingViews.JournalEntry> list(UUID companyId, ListQuery query) {
        return paginator.fetch(
                dsl,
                JOURNAL_ENTRIES,
                JOURNAL_ENTRIES.COMPANY_ID.eq(companyId),
                query,
                BINDING,
                EntryRepository::toView);
    }

    public int draftsInPeriod(UUID companyId, UUID periodId) {
        return dsl.fetchCount(
                JOURNAL_ENTRIES,
                JOURNAL_ENTRIES
                        .COMPANY_ID
                        .eq(companyId)
                        .and(JOURNAL_ENTRIES.PERIOD_ID.eq(periodId))
                        .and(JOURNAL_ENTRIES.STATUS.eq("DRAFT")));
    }

    static AccountingViews.JournalEntry toView(JournalEntriesRecord r) {
        return new AccountingViews.JournalEntry(
                r.getId(),
                r.getJournalId(),
                r.getNumber(),
                r.getEntryDate(),
                r.getPeriodId(),
                r.getEntryType(),
                r.getStatus(),
                r.getDescription(),
                r.getCurrencyCode(),
                r.getExchangeRate(),
                r.getSourceModule(),
                r.getSourceType(),
                r.getSourceId(),
                r.getSourceNumber(),
                r.getSourceEventId(),
                r.getReversalOfId(),
                r.getReversedById(),
                r.getTotalDebit(),
                r.getTotalCredit(),
                r.getPostedAt(),
                r.getPostedBy(),
                r.getCreatedBy(),
                r.getCreatedAt(),
                r.getUpdatedAt(),
                r.getVersion());
    }

    static AccountingViews.JournalLine toLine(JournalLinesRecord r) {
        return new AccountingViews.JournalLine(
                r.getId(),
                r.getJournalEntryId(),
                r.getLineNo(),
                r.getAccountId(),
                r.getDebit(),
                r.getCredit(),
                r.getCurrencyCode(),
                r.getAmountCurrency(),
                r.getPartnerId(),
                r.getBranchId(),
                r.getDepartmentId(),
                r.getTaxCodeId(),
                r.getOpenItemId(),
                r.getDescription());
    }
}
