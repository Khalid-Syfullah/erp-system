package com.erp.partners.persistence;

import static com.erp.db.partners.Tables.PARTNER_GROUPS;

import com.erp.db.partners.tables.records.PartnerGroupsRecord;
import com.erp.partners.application.PartnerCommands;
import com.erp.partners.application.PartnerListings;
import com.erp.partners.application.PartnerViews;
import com.erp.platform.jooq.KeysetPaginator;
import com.erp.platform.jooq.ListBinding;
import com.erp.platform.web.paging.ListQuery;
import com.erp.platform.web.paging.PageResponse;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

/** Partner groups (customer or supplier groups). */
@Repository
public class GroupRepository {

    private static final ListBinding BINDING = ListBinding.builder(PartnerListings.GROUPS)
            .field("code", PARTNER_GROUPS.CODE)
            .field("name", PARTNER_GROUPS.NAME)
            .field("appliesTo", PARTNER_GROUPS.APPLIES_TO)
            .field("isActive", PARTNER_GROUPS.IS_ACTIVE)
            .tiebreaker(PARTNER_GROUPS.ID)
            .search(List.of(PARTNER_GROUPS.CODE, PARTNER_GROUPS.NAME))
            .build();

    private final DSLContext dsl;
    private final KeysetPaginator paginator;

    public GroupRepository(DSLContext dsl, KeysetPaginator paginator) {
        this.dsl = dsl;
        this.paginator = paginator;
    }

    public UUID insert(UUID companyId, PartnerCommands.Group c, UUID actor) {
        return dsl.insertInto(PARTNER_GROUPS)
                .set(PARTNER_GROUPS.COMPANY_ID, companyId)
                .set(PARTNER_GROUPS.CODE, c.code())
                .set(PARTNER_GROUPS.NAME, c.name())
                .set(PARTNER_GROUPS.APPLIES_TO, c.appliesTo())
                .set(PARTNER_GROUPS.CREATED_BY, actor)
                .set(PARTNER_GROUPS.UPDATED_BY, actor)
                .returning(PARTNER_GROUPS.ID)
                .fetchOne(PARTNER_GROUPS.ID);
    }

    public Optional<PartnerViews.Group> find(UUID companyId, UUID id) {
        return dsl.selectFrom(PARTNER_GROUPS)
                .where(PARTNER_GROUPS.COMPANY_ID.eq(companyId))
                .and(PARTNER_GROUPS.ID.eq(id))
                .fetchOptional(GroupRepository::toView);
    }

    /** The group locked {@code FOR SHARE}: a profile naming it waits for a running deactivation. */
    public Optional<PartnerViews.Group> findForUse(UUID companyId, UUID id) {
        return dsl.selectFrom(PARTNER_GROUPS)
                .where(PARTNER_GROUPS.COMPANY_ID.eq(companyId))
                .and(PARTNER_GROUPS.ID.eq(id))
                .forShare()
                .fetchOptional(GroupRepository::toView);
    }

    public Optional<PartnerViews.Group> lockForChange(UUID companyId, UUID id) {
        return dsl.selectFrom(PARTNER_GROUPS)
                .where(PARTNER_GROUPS.COMPANY_ID.eq(companyId))
                .and(PARTNER_GROUPS.ID.eq(id))
                .forNoKeyUpdate()
                .fetchOptional(GroupRepository::toView);
    }

    public PageResponse<PartnerViews.Group> list(UUID companyId, ListQuery query) {
        return paginator.fetch(
                dsl, PARTNER_GROUPS, PARTNER_GROUPS.COMPANY_ID.eq(companyId), query, BINDING, GroupRepository::toView);
    }

    public boolean update(UUID companyId, UUID id, int version, UUID actor, String name, boolean active) {
        return dsl.update(PARTNER_GROUPS)
                        .set(PARTNER_GROUPS.NAME, name)
                        .set(PARTNER_GROUPS.IS_ACTIVE, active)
                        .set(PARTNER_GROUPS.UPDATED_AT, OffsetDateTime.now())
                        .set(PARTNER_GROUPS.UPDATED_BY, actor)
                        .set(PARTNER_GROUPS.VERSION, version + 1)
                        .where(PARTNER_GROUPS.COMPANY_ID.eq(companyId))
                        .and(PARTNER_GROUPS.ID.eq(id))
                        .and(PARTNER_GROUPS.VERSION.eq(version))
                        .execute()
                == 1;
    }

    private static PartnerViews.Group toView(PartnerGroupsRecord r) {
        return new PartnerViews.Group(
                r.getId(),
                r.getCode(),
                r.getName(),
                r.getAppliesTo(),
                r.getIsActive(),
                r.getCreatedAt(),
                r.getUpdatedAt(),
                r.getVersion());
    }
}
