package com.erp.accounting.persistence;

import static com.erp.db.accounting.Tables.JOURNALS;

import com.erp.accounting.application.AccountingListings;
import com.erp.accounting.application.AccountingViews;
import com.erp.db.accounting.tables.records.JournalsRecord;
import com.erp.platform.jooq.KeysetPaginator;
import com.erp.platform.jooq.ListBinding;
import com.erp.platform.web.paging.ListQuery;
import com.erp.platform.web.paging.PageResponse;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;

/** Journals: the books entries are numbered in. */
@Repository
public class JournalRepository {

    private static final ListBinding BINDING = ListBinding.builder(AccountingListings.JOURNALS)
            .field("code", JOURNALS.CODE)
            .field("journalType", JOURNALS.JOURNAL_TYPE)
            .field("isActive", JOURNALS.IS_ACTIVE)
            .tiebreaker(JOURNALS.ID)
            .build();

    private final DSLContext dsl;
    private final KeysetPaginator paginator;

    public JournalRepository(DSLContext dsl, KeysetPaginator paginator) {
        this.dsl = dsl;
        this.paginator = paginator;
    }

    public UUID insert(UUID companyId, String code, String name, String type, boolean system, @Nullable UUID actor) {
        return dsl.insertInto(JOURNALS)
                .set(JOURNALS.COMPANY_ID, companyId)
                .set(JOURNALS.CODE, code)
                .set(JOURNALS.NAME, name)
                .set(JOURNALS.JOURNAL_TYPE, type)
                .set(JOURNALS.IS_SYSTEM, system)
                .set(JOURNALS.CREATED_BY, actor)
                .set(JOURNALS.UPDATED_BY, actor)
                .returning(JOURNALS.ID)
                .fetchOne(JOURNALS.ID);
    }

    public boolean update(UUID companyId, UUID id, int version, String name, boolean active, UUID actor) {
        return dsl.update(JOURNALS)
                        .set(JOURNALS.NAME, name)
                        .set(JOURNALS.IS_ACTIVE, active)
                        .set(JOURNALS.UPDATED_AT, OffsetDateTime.now())
                        .set(JOURNALS.UPDATED_BY, actor)
                        .set(JOURNALS.VERSION, version + 1)
                        .where(JOURNALS.COMPANY_ID.eq(companyId))
                        .and(JOURNALS.ID.eq(id))
                        .and(JOURNALS.VERSION.eq(version))
                        .execute()
                == 1;
    }

    public Optional<AccountingViews.Journal> find(UUID companyId, UUID id) {
        return dsl.selectFrom(JOURNALS)
                .where(JOURNALS.COMPANY_ID.eq(companyId))
                .and(JOURNALS.ID.eq(id))
                .fetchOptional(JournalRepository::toView);
    }

    public Optional<AccountingViews.Journal> findByCode(UUID companyId, String code) {
        return dsl.selectFrom(JOURNALS)
                .where(JOURNALS.COMPANY_ID.eq(companyId))
                .and(JOURNALS.CODE.eq(code))
                .fetchOptional(JournalRepository::toView);
    }

    public PageResponse<AccountingViews.Journal> list(UUID companyId, ListQuery query) {
        return paginator.fetch(
                dsl, JOURNALS, JOURNALS.COMPANY_ID.eq(companyId), query, BINDING, JournalRepository::toView);
    }

    static AccountingViews.Journal toView(JournalsRecord r) {
        return new AccountingViews.Journal(
                r.getId(),
                r.getCode(),
                r.getName(),
                r.getJournalType(),
                r.getIsSystem(),
                r.getIsActive(),
                r.getVersion());
    }
}
