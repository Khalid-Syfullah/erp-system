package com.erp.accounting.persistence;

import static com.erp.db.accounting.Tables.ACCOUNT_MAPPINGS;

import com.erp.accounting.application.AccountingViews;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;

/** Account mappings: which account a mapping key resolves to for a scope (DATABASE.md §5.8). */
@Repository
public class MappingRepository {

    private final DSLContext dsl;

    public MappingRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public List<AccountingViews.Mapping> all(UUID companyId) {
        return dsl.selectFrom(ACCOUNT_MAPPINGS)
                .where(ACCOUNT_MAPPINGS.COMPANY_ID.eq(companyId))
                .orderBy(ACCOUNT_MAPPINGS.MAPPING_KEY, ACCOUNT_MAPPINGS.SCOPE_TYPE, ACCOUNT_MAPPINGS.SCOPE_ID)
                .fetch(r -> new AccountingViews.Mapping(
                        r.getId(), r.getMappingKey(), r.getScopeType(), r.getScopeId(), r.getAccountId()));
    }

    /** The account of a mapping key for one scope ({@code scopeId == null}: DEFAULT). */
    public Optional<UUID> account(UUID companyId, String key, String scopeType, @Nullable UUID scopeId) {
        return dsl.select(ACCOUNT_MAPPINGS.ACCOUNT_ID)
                .from(ACCOUNT_MAPPINGS)
                .where(ACCOUNT_MAPPINGS.COMPANY_ID.eq(companyId))
                .and(ACCOUNT_MAPPINGS.MAPPING_KEY.eq(key))
                .and(ACCOUNT_MAPPINGS.SCOPE_TYPE.eq(scopeType))
                .and(scopeId == null ? ACCOUNT_MAPPINGS.SCOPE_ID.isNull() : ACCOUNT_MAPPINGS.SCOPE_ID.eq(scopeId))
                .fetchOptional(ACCOUNT_MAPPINGS.ACCOUNT_ID);
    }

    /** Inserts or re-points a mapping. */
    public void upsert(
            UUID companyId,
            String key,
            String scopeType,
            @Nullable UUID scopeId,
            UUID accountId,
            @Nullable UUID actor) {
        dsl.insertInto(ACCOUNT_MAPPINGS)
                .set(ACCOUNT_MAPPINGS.COMPANY_ID, companyId)
                .set(ACCOUNT_MAPPINGS.MAPPING_KEY, key)
                .set(ACCOUNT_MAPPINGS.SCOPE_TYPE, scopeType)
                .set(ACCOUNT_MAPPINGS.SCOPE_ID, scopeId)
                .set(ACCOUNT_MAPPINGS.ACCOUNT_ID, accountId)
                .set(ACCOUNT_MAPPINGS.CREATED_BY, actor)
                .set(ACCOUNT_MAPPINGS.UPDATED_BY, actor)
                .onConflict(
                        ACCOUNT_MAPPINGS.COMPANY_ID,
                        ACCOUNT_MAPPINGS.MAPPING_KEY,
                        ACCOUNT_MAPPINGS.SCOPE_TYPE,
                        ACCOUNT_MAPPINGS.SCOPE_ID)
                .doUpdate()
                .set(ACCOUNT_MAPPINGS.ACCOUNT_ID, accountId)
                .set(ACCOUNT_MAPPINGS.UPDATED_AT, OffsetDateTime.now())
                .set(ACCOUNT_MAPPINGS.UPDATED_BY, actor)
                .set(ACCOUNT_MAPPINGS.VERSION, ACCOUNT_MAPPINGS.VERSION.add(1))
                .execute();
    }

    public boolean delete(UUID companyId, String key, String scopeType, @Nullable UUID scopeId) {
        return dsl.deleteFrom(ACCOUNT_MAPPINGS)
                        .where(ACCOUNT_MAPPINGS.COMPANY_ID.eq(companyId))
                        .and(ACCOUNT_MAPPINGS.MAPPING_KEY.eq(key))
                        .and(ACCOUNT_MAPPINGS.SCOPE_TYPE.eq(scopeType))
                        .and(
                                scopeId == null
                                        ? ACCOUNT_MAPPINGS.SCOPE_ID.isNull()
                                        : ACCOUNT_MAPPINGS.SCOPE_ID.eq(scopeId))
                        .execute()
                == 1;
    }

    public boolean usesAccount(UUID companyId, UUID accountId) {
        return dsl.fetchExists(dsl.selectOne()
                .from(ACCOUNT_MAPPINGS)
                .where(ACCOUNT_MAPPINGS.COMPANY_ID.eq(companyId))
                .and(ACCOUNT_MAPPINGS.ACCOUNT_ID.eq(accountId)));
    }

    /** Serializes bulk changes of the company's mappings (the PUT replaces the whole set). */
    public void lockMappings(UUID companyId) {
        dsl.execute(
                "SELECT pg_advisory_xact_lock(hashtext('accounting.account_mappings'), hashtext(?))",
                companyId.toString());
    }
}
