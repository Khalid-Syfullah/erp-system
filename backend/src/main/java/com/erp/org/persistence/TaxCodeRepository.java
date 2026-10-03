package com.erp.org.persistence;

import static com.erp.db.org.Tables.TAX_CODES;

import com.erp.db.org.tables.records.TaxCodesRecord;
import com.erp.org.application.OrgListings;
import com.erp.org.application.ReferenceCommands;
import com.erp.org.application.TaxCodeView;
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

/** Tax codes per company (DATABASE.md §5.2). */
@Repository
public class TaxCodeRepository {

    private static final ListBinding BINDING = ListBinding.builder(OrgListings.TAX_CODES)
            .field("code", TAX_CODES.CODE)
            .field("name", TAX_CODES.NAME)
            .field("createdAt", TAX_CODES.CREATED_AT)
            .field("scope", TAX_CODES.SCOPE)
            .field("isActive", TAX_CODES.IS_ACTIVE)
            .field("isExempt", TAX_CODES.IS_EXEMPT)
            .tiebreaker(TAX_CODES.ID)
            .search(List.of(TAX_CODES.CODE, TAX_CODES.NAME))
            .build();

    private final DSLContext dsl;
    private final KeysetPaginator paginator;

    public TaxCodeRepository(DSLContext dsl, KeysetPaginator paginator) {
        this.dsl = dsl;
        this.paginator = paginator;
    }

    public UUID insert(UUID companyId, ReferenceCommands.TaxCode c, UUID actor) {
        return dsl.insertInto(TAX_CODES)
                .set(TAX_CODES.COMPANY_ID, companyId)
                .set(TAX_CODES.CODE, c.code())
                .set(TAX_CODES.NAME, c.name())
                .set(TAX_CODES.SCOPE, c.scope())
                .set(TAX_CODES.RATE_PERCENT, c.ratePercent())
                .set(TAX_CODES.IS_EXEMPT, c.exempt())
                .set(TAX_CODES.VALID_FROM, c.validFrom())
                .set(TAX_CODES.VALID_TO, c.validTo())
                .set(TAX_CODES.CREATED_BY, actor)
                .set(TAX_CODES.UPDATED_BY, actor)
                .returning(TAX_CODES.ID)
                .fetchOne(TAX_CODES.ID);
    }

    public Optional<TaxCodeView> find(UUID companyId, UUID id) {
        return dsl.selectFrom(TAX_CODES)
                .where(TAX_CODES.COMPANY_ID.eq(companyId))
                .and(TAX_CODES.ID.eq(id))
                .fetchOptional()
                .map(TaxCodeRepository::toView);
    }

    public Optional<TaxCodeView> lockForChange(UUID companyId, UUID id) {
        return dsl.selectFrom(TAX_CODES)
                .where(TAX_CODES.COMPANY_ID.eq(companyId))
                .and(TAX_CODES.ID.eq(id))
                .forNoKeyUpdate()
                .fetchOptional()
                .map(TaxCodeRepository::toView);
    }

    public PageResponse<TaxCodeView> list(UUID companyId, ListQuery query) {
        return paginator.fetch(
                dsl, TAX_CODES, TAX_CODES.COMPANY_ID.eq(companyId), query, BINDING, TaxCodeRepository::toView);
    }

    public boolean update(UUID companyId, UUID id, int expectedVersion, UUID actor, ReferenceCommands.TaxCode c) {
        return dsl.update(TAX_CODES)
                        .set(TAX_CODES.NAME, c.name())
                        .set(TAX_CODES.SCOPE, c.scope())
                        .set(TAX_CODES.RATE_PERCENT, c.ratePercent())
                        .set(TAX_CODES.IS_EXEMPT, c.exempt())
                        .set(TAX_CODES.VALID_FROM, c.validFrom())
                        .set(TAX_CODES.VALID_TO, c.validTo())
                        .set(TAX_CODES.IS_ACTIVE, c.active())
                        .set(TAX_CODES.UPDATED_AT, OffsetDateTime.now())
                        .set(TAX_CODES.UPDATED_BY, actor)
                        .set(TAX_CODES.VERSION, expectedVersion + 1)
                        .where(TAX_CODES.COMPANY_ID.eq(companyId))
                        .and(TAX_CODES.ID.eq(id))
                        .and(TAX_CODES.VERSION.eq(expectedVersion))
                        .execute()
                == 1;
    }

    static TaxCodeView toView(TaxCodesRecord r) {
        return new TaxCodeView(
                r.getId(),
                r.getCompanyId(),
                r.getCode(),
                r.getName(),
                r.getScope(),
                r.getRatePercent(),
                r.getIsExempt(),
                r.getValidFrom(),
                r.getValidTo(),
                r.getIsActive(),
                r.getCreatedAt(),
                r.getUpdatedAt(),
                r.getVersion());
    }
}
