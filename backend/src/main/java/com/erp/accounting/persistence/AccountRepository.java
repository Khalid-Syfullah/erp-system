package com.erp.accounting.persistence;

import static com.erp.db.accounting.Tables.ACCOUNTS;
import static com.erp.db.accounting.Tables.JOURNAL_LINES;

import com.erp.accounting.application.AccountingListings;
import com.erp.accounting.application.AccountingViews;
import com.erp.db.accounting.tables.records.AccountsRecord;
import com.erp.platform.jooq.KeysetPaginator;
import com.erp.platform.jooq.ListBinding;
import com.erp.platform.web.paging.ListQuery;
import com.erp.platform.web.paging.PageResponse;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;

/** The chart of accounts. */
@Repository
public class AccountRepository {

    private static final ListBinding BINDING = ListBinding.builder(AccountingListings.ACCOUNTS)
            .field("code", ACCOUNTS.CODE)
            .field("name", ACCOUNTS.NAME)
            .field("accountType", ACCOUNTS.ACCOUNT_TYPE)
            .field("accountSubtype", ACCOUNTS.ACCOUNT_SUBTYPE)
            .field("status", ACCOUNTS.STATUS)
            .field("isControl", ACCOUNTS.IS_CONTROL)
            .field("isPostable", ACCOUNTS.IS_POSTABLE)
            .field("parentId", ACCOUNTS.PARENT_ID)
            .tiebreaker(ACCOUNTS.ID)
            .search(List.of(ACCOUNTS.CODE, ACCOUNTS.NAME))
            .build();

    /** The values an account is created or updated with. */
    public record Values(
            String code,
            String name,
            String accountType,
            String accountSubtype,
            @Nullable UUID parentId,
            boolean postable,
            boolean control,
            boolean system,
            @Nullable String currencyCode,
            @Nullable String description) {}

    private final DSLContext dsl;
    private final KeysetPaginator paginator;

    public AccountRepository(DSLContext dsl, KeysetPaginator paginator) {
        this.dsl = dsl;
        this.paginator = paginator;
    }

    public UUID insert(UUID companyId, Values v, @Nullable UUID actor) {
        return dsl.insertInto(ACCOUNTS)
                .set(ACCOUNTS.COMPANY_ID, companyId)
                .set(ACCOUNTS.CODE, v.code())
                .set(ACCOUNTS.NAME, v.name())
                .set(ACCOUNTS.ACCOUNT_TYPE, v.accountType())
                .set(ACCOUNTS.ACCOUNT_SUBTYPE, v.accountSubtype())
                .set(ACCOUNTS.PARENT_ID, v.parentId())
                .set(ACCOUNTS.IS_POSTABLE, v.postable())
                .set(ACCOUNTS.IS_CONTROL, v.control())
                .set(ACCOUNTS.IS_SYSTEM, v.system())
                .set(ACCOUNTS.CURRENCY_CODE, v.currencyCode())
                .set(ACCOUNTS.DESCRIPTION, v.description())
                .set(ACCOUNTS.CREATED_BY, actor)
                .set(ACCOUNTS.UPDATED_BY, actor)
                .returning(ACCOUNTS.ID)
                .fetchOne(ACCOUNTS.ID);
    }

    public boolean update(UUID companyId, UUID id, int version, Values v, UUID actor) {
        return dsl.update(ACCOUNTS)
                        .set(ACCOUNTS.NAME, v.name())
                        .set(ACCOUNTS.ACCOUNT_TYPE, v.accountType())
                        .set(ACCOUNTS.ACCOUNT_SUBTYPE, v.accountSubtype())
                        .set(ACCOUNTS.PARENT_ID, v.parentId())
                        .set(ACCOUNTS.IS_POSTABLE, v.postable())
                        .set(ACCOUNTS.IS_CONTROL, v.control())
                        .set(ACCOUNTS.CURRENCY_CODE, v.currencyCode())
                        .set(ACCOUNTS.DESCRIPTION, v.description())
                        .set(ACCOUNTS.UPDATED_AT, OffsetDateTime.now())
                        .set(ACCOUNTS.UPDATED_BY, actor)
                        .set(ACCOUNTS.VERSION, version + 1)
                        .where(ACCOUNTS.COMPANY_ID.eq(companyId))
                        .and(ACCOUNTS.ID.eq(id))
                        .and(ACCOUNTS.VERSION.eq(version))
                        .execute()
                == 1;
    }

    public boolean setStatus(UUID companyId, UUID id, int version, String status, UUID actor) {
        return dsl.update(ACCOUNTS)
                        .set(ACCOUNTS.STATUS, status)
                        .set(ACCOUNTS.UPDATED_AT, OffsetDateTime.now())
                        .set(ACCOUNTS.UPDATED_BY, actor)
                        .set(ACCOUNTS.VERSION, version + 1)
                        .where(ACCOUNTS.COMPANY_ID.eq(companyId))
                        .and(ACCOUNTS.ID.eq(id))
                        .and(ACCOUNTS.VERSION.eq(version))
                        .execute()
                == 1;
    }

    public Optional<AccountingViews.Account> find(UUID companyId, UUID id) {
        return dsl.selectFrom(ACCOUNTS)
                .where(ACCOUNTS.COMPANY_ID.eq(companyId))
                .and(ACCOUNTS.ID.eq(id))
                .fetchOptional(AccountRepository::toView);
    }

    /** The account {@code FOR NO KEY UPDATE}: a change serializes with postings that read it. */
    public Optional<AccountingViews.Account> lock(UUID companyId, UUID id) {
        return dsl.selectFrom(ACCOUNTS)
                .where(ACCOUNTS.COMPANY_ID.eq(companyId))
                .and(ACCOUNTS.ID.eq(id))
                .forNoKeyUpdate()
                .fetchOptional(AccountRepository::toView);
    }

    public Optional<AccountingViews.Account> findByCode(UUID companyId, String code) {
        return dsl.selectFrom(ACCOUNTS)
                .where(ACCOUNTS.COMPANY_ID.eq(companyId))
                .and(ACCOUNTS.CODE.eq(code))
                .fetchOptional(AccountRepository::toView);
    }

    /** Accounts by ID, {@code FOR SHARE} so that a concurrent deactivation waits for the posting. */
    public Map<UUID, AccountingViews.Account> forPosting(UUID companyId, Collection<UUID> ids) {
        Map<UUID, AccountingViews.Account> result = new LinkedHashMap<>();
        if (ids.isEmpty()) {
            return result;
        }
        dsl.selectFrom(ACCOUNTS)
                .where(ACCOUNTS.COMPANY_ID.eq(companyId))
                .and(ACCOUNTS.ID.in(ids))
                .orderBy(ACCOUNTS.ID)
                .forShare()
                .fetch(AccountRepository::toView)
                .forEach(a -> result.put(a.id(), a));
        return result;
    }

    public List<AccountingViews.Account> all(UUID companyId) {
        return dsl.selectFrom(ACCOUNTS)
                .where(ACCOUNTS.COMPANY_ID.eq(companyId))
                .orderBy(ACCOUNTS.CODE)
                .fetch(AccountRepository::toView);
    }

    public PageResponse<AccountingViews.Account> list(UUID companyId, ListQuery query) {
        return paginator.fetch(
                dsl, ACCOUNTS, ACCOUNTS.COMPANY_ID.eq(companyId), query, BINDING, AccountRepository::toView);
    }

    public boolean hasPostings(UUID companyId, UUID accountId) {
        return dsl.fetchExists(dsl.selectOne()
                .from(JOURNAL_LINES)
                .where(JOURNAL_LINES.COMPANY_ID.eq(companyId))
                .and(JOURNAL_LINES.ACCOUNT_ID.eq(accountId)));
    }

    public boolean hasChildren(UUID companyId, UUID accountId) {
        return dsl.fetchExists(dsl.selectOne()
                .from(ACCOUNTS)
                .where(ACCOUNTS.COMPANY_ID.eq(companyId))
                .and(ACCOUNTS.PARENT_ID.eq(accountId)));
    }

    static AccountingViews.Account toView(AccountsRecord r) {
        return new AccountingViews.Account(
                r.getId(),
                r.getCode(),
                r.getName(),
                r.getAccountType(),
                r.getAccountSubtype(),
                r.getParentId(),
                r.getIsPostable(),
                r.getIsControl(),
                r.getIsSystem(),
                r.getCurrencyCode(),
                r.getStatus(),
                r.getDescription(),
                r.getCreatedAt(),
                r.getUpdatedAt(),
                r.getVersion());
    }
}
