package com.erp.admin.persistence;

import static com.erp.db.admin.Tables.AUDIT_LOG;

import com.erp.admin.application.AuditEntryView;
import com.erp.admin.application.AuditListings;
import com.erp.platform.jooq.Inets;
import com.erp.platform.jooq.KeysetPaginator;
import com.erp.platform.jooq.ListBinding;
import com.erp.platform.web.paging.ListQuery;
import com.erp.platform.web.paging.PageResponse;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;

@Repository
public class AuditLogRepository {

    private static final ListBinding BINDING = ListBinding.builder(AuditListings.AUDIT_LOG)
            .field("occurredAt", AUDIT_LOG.OCCURRED_AT)
            .field("action", AUDIT_LOG.ACTION)
            .field("module", AUDIT_LOG.MODULE)
            .field("entityType", AUDIT_LOG.ENTITY_TYPE)
            .field("entityId", AUDIT_LOG.ENTITY_ID)
            .field("actorUserId", AUDIT_LOG.ACTOR_USER_ID)
            .field("companyId", AUDIT_LOG.COMPANY_ID)
            .tiebreaker(AUDIT_LOG.ID)
            .build();

    /** Row to insert; mirrors admin.audit_log. */
    public record NewEntry(
            @Nullable UUID companyId,
            @Nullable UUID actorUserId,
            String actorType,
            @Nullable UUID apiTokenId,
            String action,
            String module,
            @Nullable String entityType,
            @Nullable UUID entityId,
            @Nullable String entityLabel,
            @Nullable String fromState,
            @Nullable String toState,
            @Nullable String changesJson,
            @Nullable String requestId,
            @Nullable String ip,
            @Nullable String userAgent) {}

    private final DSLContext dsl;
    private final KeysetPaginator paginator;

    public AuditLogRepository(DSLContext dsl, KeysetPaginator paginator) {
        this.dsl = dsl;
        this.paginator = paginator;
    }

    public void insert(NewEntry entry) {
        dsl.insertInto(AUDIT_LOG)
                .set(AUDIT_LOG.COMPANY_ID, entry.companyId())
                .set(AUDIT_LOG.ACTOR_USER_ID, entry.actorUserId())
                .set(AUDIT_LOG.ACTOR_TYPE, entry.actorType())
                .set(AUDIT_LOG.API_TOKEN_ID, entry.apiTokenId())
                .set(AUDIT_LOG.ACTION, entry.action())
                .set(AUDIT_LOG.MODULE, entry.module())
                .set(AUDIT_LOG.ENTITY_TYPE, entry.entityType())
                .set(AUDIT_LOG.ENTITY_ID, entry.entityId())
                .set(AUDIT_LOG.ENTITY_LABEL, entry.entityLabel())
                .set(AUDIT_LOG.FROM_STATE, entry.fromState())
                .set(AUDIT_LOG.TO_STATE, entry.toState())
                .set(AUDIT_LOG.CHANGES, entry.changesJson() == null ? null : JSONB.valueOf(entry.changesJson()))
                .set(AUDIT_LOG.REQUEST_ID, entry.requestId())
                .set(AUDIT_LOG.IP, Inets.of(entry.ip()))
                .set(AUDIT_LOG.USER_AGENT, entry.userAgent())
                .execute();
    }

    /**
     * Lists entries visible under the current RLS context; {@code companyScope} additionally restricts
     * to one company (null = whatever RLS allows, i.e. global access).
     */
    public PageResponse<AuditEntryView> find(ListQuery query, @Nullable UUID companyScope) {
        Condition scope =
                companyScope == null ? org.jooq.impl.DSL.noCondition() : AUDIT_LOG.COMPANY_ID.eq(companyScope);
        return paginator.fetch(
                dsl,
                AUDIT_LOG,
                scope,
                query,
                BINDING,
                r -> new AuditEntryView(
                        r.getId(),
                        r.getOccurredAt(),
                        r.getCompanyId(),
                        r.getActorUserId(),
                        r.getActorType(),
                        r.getApiTokenId(),
                        r.getAction(),
                        r.getModule(),
                        r.getEntityType(),
                        r.getEntityId(),
                        r.getEntityLabel(),
                        r.getFromState(),
                        r.getToState(),
                        r.getChanges() == null ? null : r.getChanges().data(),
                        r.getRequestId()));
    }

    /** Creates missing monthly partitions (admin.ensure_audit_partitions, SECURITY DEFINER). */
    public int ensurePartitions(int monthsAhead) {
        return dsl.select(org.jooq.impl.DSL.field("admin.ensure_audit_partitions({0})", Integer.class, monthsAhead))
                .fetchOne(0, Integer.class);
    }
}
