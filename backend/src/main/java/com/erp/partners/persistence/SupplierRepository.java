package com.erp.partners.persistence;

import static com.erp.db.partners.Tables.PARTNERS_;
import static com.erp.db.partners.Tables.SUPPLIERS;

import com.erp.db.partners.tables.records.SuppliersRecord;
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
import org.jooq.Record;
import org.jooq.Table;
import org.springframework.stereotype.Repository;

/** Supplier profiles (one per partner). */
@Repository
public class SupplierRepository {

    private static final Table<Record> SUPPLIER_PARTNERS = SUPPLIERS
            .join(PARTNERS_)
            .on(PARTNERS_.ID.eq(SUPPLIERS.PARTNER_ID))
            .and(PARTNERS_.COMPANY_ID.eq(SUPPLIERS.COMPANY_ID));

    private static final ListBinding BINDING = ListBinding.builder(PartnerListings.SUPPLIERS)
            .field("code", PARTNERS_.CODE)
            .field("name", PARTNERS_.NAME)
            .field("createdAt", PARTNERS_.CREATED_AT)
            .field("status", PARTNERS_.STATUS)
            .field("supplierGroupId", SUPPLIERS.SUPPLIER_GROUP_ID)
            .field("currencyCode", SUPPLIERS.CURRENCY_CODE)
            .tiebreaker(PARTNERS_.ID)
            .search(List.of(PARTNERS_.CODE, PARTNERS_.NAME, PARTNERS_.LEGAL_NAME, PARTNERS_.TAX_REGISTRATION_NO))
            .build();

    private final DSLContext dsl;
    private final KeysetPaginator paginator;

    public SupplierRepository(DSLContext dsl, KeysetPaginator paginator) {
        this.dsl = dsl;
        this.paginator = paginator;
    }

    public Optional<PartnerViews.Supplier> find(UUID companyId, UUID partnerId) {
        return dsl.selectFrom(SUPPLIERS)
                .where(SUPPLIERS.COMPANY_ID.eq(companyId))
                .and(SUPPLIERS.PARTNER_ID.eq(partnerId))
                .fetchOptional(SupplierRepository::toView);
    }

    /** Inserts the profile (version 1) or updates it if it has the expected version. */
    public boolean save(UUID companyId, UUID partnerId, int expectedVersion, PartnerCommands.Supplier c, UUID actor) {
        if (expectedVersion == 0) {
            return dsl.insertInto(SUPPLIERS)
                            .set(SUPPLIERS.PARTNER_ID, partnerId)
                            .set(SUPPLIERS.COMPANY_ID, companyId)
                            .set(SUPPLIERS.SUPPLIER_GROUP_ID, c.supplierGroupId())
                            .set(SUPPLIERS.CURRENCY_CODE, c.currencyCode())
                            .set(SUPPLIERS.PAYMENT_TERMS_ID, c.paymentTermsId())
                            .set(SUPPLIERS.DEFAULT_TAX_CODE_ID, c.defaultTaxCodeId())
                            .set(SUPPLIERS.LEAD_TIME_DAYS, c.leadTimeDays())
                            .set(SUPPLIERS.CREATED_BY, actor)
                            .set(SUPPLIERS.UPDATED_BY, actor)
                            .set(SUPPLIERS.VERSION, 1)
                            .onConflictDoNothing()
                            .execute()
                    == 1;
        }
        return dsl.update(SUPPLIERS)
                        .set(SUPPLIERS.SUPPLIER_GROUP_ID, c.supplierGroupId())
                        .set(SUPPLIERS.CURRENCY_CODE, c.currencyCode())
                        .set(SUPPLIERS.PAYMENT_TERMS_ID, c.paymentTermsId())
                        .set(SUPPLIERS.DEFAULT_TAX_CODE_ID, c.defaultTaxCodeId())
                        .set(SUPPLIERS.LEAD_TIME_DAYS, c.leadTimeDays())
                        .set(SUPPLIERS.UPDATED_AT, OffsetDateTime.now())
                        .set(SUPPLIERS.UPDATED_BY, actor)
                        .set(SUPPLIERS.VERSION, expectedVersion + 1)
                        .where(SUPPLIERS.COMPANY_ID.eq(companyId))
                        .and(SUPPLIERS.PARTNER_ID.eq(partnerId))
                        .and(SUPPLIERS.VERSION.eq(expectedVersion))
                        .execute()
                == 1;
    }

    public PageResponse<PartnerViews.SupplierListItem> list(UUID companyId, ListQuery query) {
        return paginator.fetch(
                dsl,
                SUPPLIER_PARTNERS,
                SUPPLIERS.COMPANY_ID.eq(companyId),
                query,
                BINDING,
                r -> new PartnerViews.SupplierListItem(PartnerRepository.toView(r), toView(r.into(SUPPLIERS))));
    }

    /** Supplier (with partner) for the facade; {@code lock} takes the partner row {@code FOR SHARE}. */
    public Optional<PartnerViews.SupplierListItem> findWithPartner(UUID companyId, UUID partnerId, boolean lock) {
        var query = dsl.select()
                .from(SUPPLIER_PARTNERS)
                .where(SUPPLIERS.COMPANY_ID.eq(companyId))
                .and(SUPPLIERS.PARTNER_ID.eq(partnerId));
        return (lock ? query.forShare().of(PARTNERS_).fetchOptional() : query.fetchOptional())
                .map(r -> new PartnerViews.SupplierListItem(PartnerRepository.toView(r), toView(r.into(SUPPLIERS))));
    }

    static PartnerViews.Supplier toView(SuppliersRecord r) {
        return new PartnerViews.Supplier(
                r.getPartnerId(),
                r.getSupplierGroupId(),
                r.getCurrencyCode(),
                r.getPaymentTermsId(),
                r.getDefaultTaxCodeId(),
                r.getLeadTimeDays(),
                r.getUpdatedAt(),
                r.getVersion());
    }
}
