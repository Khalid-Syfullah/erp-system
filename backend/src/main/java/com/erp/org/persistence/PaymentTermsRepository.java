package com.erp.org.persistence;

import static com.erp.db.org.Tables.PAYMENT_TERMS;

import com.erp.db.org.tables.records.PaymentTermsRecord;
import com.erp.org.application.OrgListings;
import com.erp.org.application.PaymentTermsView;
import com.erp.org.application.ReferenceCommands;
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

/** Payment terms per company (DATABASE.md §5.2). */
@Repository
public class PaymentTermsRepository {

    private static final ListBinding BINDING = ListBinding.builder(OrgListings.PAYMENT_TERMS)
            .field("code", PAYMENT_TERMS.CODE)
            .field("name", PAYMENT_TERMS.NAME)
            .field("dueDays", PAYMENT_TERMS.DUE_DAYS)
            .field("createdAt", PAYMENT_TERMS.CREATED_AT)
            .field("dueBasis", PAYMENT_TERMS.DUE_BASIS)
            .field("isActive", PAYMENT_TERMS.IS_ACTIVE)
            .tiebreaker(PAYMENT_TERMS.ID)
            .search(List.of(PAYMENT_TERMS.CODE, PAYMENT_TERMS.NAME))
            .build();

    private final DSLContext dsl;
    private final KeysetPaginator paginator;

    public PaymentTermsRepository(DSLContext dsl, KeysetPaginator paginator) {
        this.dsl = dsl;
        this.paginator = paginator;
    }

    public UUID insert(UUID companyId, ReferenceCommands.PaymentTerms c, UUID actor) {
        return dsl.insertInto(PAYMENT_TERMS)
                .set(PAYMENT_TERMS.COMPANY_ID, companyId)
                .set(PAYMENT_TERMS.CODE, c.code())
                .set(PAYMENT_TERMS.NAME, c.name())
                .set(PAYMENT_TERMS.DUE_DAYS, c.dueDays())
                .set(PAYMENT_TERMS.DUE_BASIS, c.dueBasis())
                .set(PAYMENT_TERMS.CREATED_BY, actor)
                .set(PAYMENT_TERMS.UPDATED_BY, actor)
                .returning(PAYMENT_TERMS.ID)
                .fetchOne(PAYMENT_TERMS.ID);
    }

    public Optional<PaymentTermsView> find(UUID companyId, UUID id) {
        return dsl.selectFrom(PAYMENT_TERMS)
                .where(PAYMENT_TERMS.COMPANY_ID.eq(companyId))
                .and(PAYMENT_TERMS.ID.eq(id))
                .fetchOptional()
                .map(PaymentTermsRepository::toView);
    }

    public Optional<PaymentTermsView> lockForChange(UUID companyId, UUID id) {
        return dsl.selectFrom(PAYMENT_TERMS)
                .where(PAYMENT_TERMS.COMPANY_ID.eq(companyId))
                .and(PAYMENT_TERMS.ID.eq(id))
                .forNoKeyUpdate()
                .fetchOptional()
                .map(PaymentTermsRepository::toView);
    }

    public PageResponse<PaymentTermsView> list(UUID companyId, ListQuery query) {
        return paginator.fetch(
                dsl,
                PAYMENT_TERMS,
                PAYMENT_TERMS.COMPANY_ID.eq(companyId),
                query,
                BINDING,
                PaymentTermsRepository::toView);
    }

    public boolean update(UUID companyId, UUID id, int expectedVersion, UUID actor, ReferenceCommands.PaymentTerms c) {
        return dsl.update(PAYMENT_TERMS)
                        .set(PAYMENT_TERMS.NAME, c.name())
                        .set(PAYMENT_TERMS.DUE_DAYS, c.dueDays())
                        .set(PAYMENT_TERMS.DUE_BASIS, c.dueBasis())
                        .set(PAYMENT_TERMS.IS_ACTIVE, c.active())
                        .set(PAYMENT_TERMS.UPDATED_AT, OffsetDateTime.now())
                        .set(PAYMENT_TERMS.UPDATED_BY, actor)
                        .set(PAYMENT_TERMS.VERSION, expectedVersion + 1)
                        .where(PAYMENT_TERMS.COMPANY_ID.eq(companyId))
                        .and(PAYMENT_TERMS.ID.eq(id))
                        .and(PAYMENT_TERMS.VERSION.eq(expectedVersion))
                        .execute()
                == 1;
    }

    static PaymentTermsView toView(PaymentTermsRecord r) {
        return new PaymentTermsView(
                r.getId(),
                r.getCompanyId(),
                r.getCode(),
                r.getName(),
                r.getDueDays(),
                r.getDueBasis(),
                r.getIsActive(),
                r.getCreatedAt(),
                r.getUpdatedAt(),
                r.getVersion());
    }
}
