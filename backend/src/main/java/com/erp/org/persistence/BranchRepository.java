package com.erp.org.persistence;

import static com.erp.db.org.Tables.BRANCHES;
import static com.erp.db.org.Tables.COUNTRIES;

import com.erp.db.org.tables.records.BranchesRecord;
import com.erp.org.application.BranchView;
import com.erp.org.application.CompanyCommands;
import com.erp.org.application.OrgListings;
import com.erp.platform.jooq.KeysetPaginator;
import com.erp.platform.jooq.ListBinding;
import com.erp.platform.web.paging.ListQuery;
import com.erp.platform.web.paging.PageResponse;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;

/**
 * Branches. Every query is company-scoped explicitly in addition to RLS, and narrowed to the branch
 * scope when one is given (SECURITY.md §4.4 layer 4).
 */
@Repository
public class BranchRepository {

    private static final ListBinding BINDING = ListBinding.builder(OrgListings.BRANCHES)
            .field("code", BRANCHES.CODE)
            .field("name", BRANCHES.NAME)
            .field("createdAt", BRANCHES.CREATED_AT)
            .field("isActive", BRANCHES.IS_ACTIVE)
            .tiebreaker(BRANCHES.ID)
            .search(List.of(BRANCHES.CODE, BRANCHES.NAME))
            .build();

    private final DSLContext dsl;
    private final KeysetPaginator paginator;

    public BranchRepository(DSLContext dsl, KeysetPaginator paginator) {
        this.dsl = dsl;
        this.paginator = paginator;
    }

    public UUID insert(UUID companyId, CompanyCommands.CreateBranch c, UUID actor) {
        return dsl.insertInto(BRANCHES)
                .set(BRANCHES.COMPANY_ID, companyId)
                .set(BRANCHES.CODE, c.code())
                .set(BRANCHES.NAME, c.name())
                .set(BRANCHES.ADDRESS_LINE1, c.addressLine1())
                .set(BRANCHES.ADDRESS_LINE2, c.addressLine2())
                .set(BRANCHES.CITY, c.city())
                .set(BRANCHES.REGION, c.region())
                .set(BRANCHES.POSTAL_CODE, c.postalCode())
                .set(BRANCHES.COUNTRY_CODE, c.countryCode())
                .set(BRANCHES.CREATED_BY, actor)
                .set(BRANCHES.UPDATED_BY, actor)
                .returning(BRANCHES.ID)
                .fetchOne(BRANCHES.ID);
    }

    public Optional<BranchView> find(UUID companyId, UUID id, @Nullable Set<UUID> branchScope) {
        return dsl.selectFrom(BRANCHES)
                .where(BRANCHES.COMPANY_ID.eq(companyId))
                .and(BRANCHES.ID.eq(id))
                .and(scope(branchScope))
                .fetchOptional()
                .map(BranchRepository::toView);
    }

    /** Locks the row against concurrent changes ({@code FOR NO KEY UPDATE}) before a state change. */
    public Optional<BranchView> lockForChange(UUID companyId, UUID id, @Nullable Set<UUID> branchScope) {
        return dsl.selectFrom(BRANCHES)
                .where(BRANCHES.COMPANY_ID.eq(companyId))
                .and(BRANCHES.ID.eq(id))
                .and(scope(branchScope))
                .forNoKeyUpdate()
                .fetchOptional()
                .map(BranchRepository::toView);
    }

    /** Locks the row {@code FOR SHARE}: a concurrent deactivation waits until the caller commits. */
    public Optional<BranchView> lockForUse(UUID companyId, UUID id, @Nullable Set<UUID> branchScope) {
        return dsl.selectFrom(BRANCHES)
                .where(BRANCHES.COMPANY_ID.eq(companyId))
                .and(BRANCHES.ID.eq(id))
                .and(scope(branchScope))
                .forShare()
                .fetchOptional()
                .map(BranchRepository::toView);
    }

    public PageResponse<BranchView> list(UUID companyId, @Nullable Set<UUID> branchScope, ListQuery query) {
        return paginator.fetch(
                dsl,
                BRANCHES,
                BRANCHES.COMPANY_ID.eq(companyId).and(scope(branchScope)),
                query,
                BINDING,
                BranchRepository::toView);
    }

    public boolean update(UUID companyId, UUID id, int expectedVersion, UUID actor, CompanyCommands.UpdateBranch c) {
        return dsl.update(BRANCHES)
                        .set(BRANCHES.NAME, c.name())
                        .set(BRANCHES.ADDRESS_LINE1, c.addressLine1())
                        .set(BRANCHES.ADDRESS_LINE2, c.addressLine2())
                        .set(BRANCHES.CITY, c.city())
                        .set(BRANCHES.REGION, c.region())
                        .set(BRANCHES.POSTAL_CODE, c.postalCode())
                        .set(BRANCHES.COUNTRY_CODE, c.countryCode())
                        .set(BRANCHES.IS_ACTIVE, c.active())
                        .set(BRANCHES.UPDATED_AT, OffsetDateTime.now())
                        .set(BRANCHES.UPDATED_BY, actor)
                        .set(BRANCHES.VERSION, expectedVersion + 1)
                        .where(BRANCHES.COMPANY_ID.eq(companyId))
                        .and(BRANCHES.ID.eq(id))
                        .and(BRANCHES.VERSION.eq(expectedVersion))
                        .execute()
                == 1;
    }

    public List<BranchView> findAll(UUID companyId, java.util.Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return dsl.selectFrom(BRANCHES)
                .where(BRANCHES.COMPANY_ID.eq(companyId))
                .and(BRANCHES.ID.in(ids))
                .fetch(BranchRepository::toView);
    }

    public boolean countryExists(String code) {
        return dsl.fetchExists(COUNTRIES, COUNTRIES.CODE.eq(code));
    }

    private static Condition scope(@Nullable Set<UUID> branchScope) {
        return branchScope == null ? org.jooq.impl.DSL.noCondition() : BRANCHES.ID.in(branchScope);
    }

    static BranchView toView(BranchesRecord r) {
        return new BranchView(
                r.getId(),
                r.getCompanyId(),
                r.getCode(),
                r.getName(),
                r.getAddressLine1(),
                r.getAddressLine2(),
                r.getCity(),
                r.getRegion(),
                r.getPostalCode(),
                r.getCountryCode(),
                r.getIsActive(),
                r.getCreatedAt(),
                r.getUpdatedAt(),
                r.getVersion());
    }
}
