package com.erp.partners.persistence;

import static com.erp.db.partners.Tables.CUSTOMERS;
import static com.erp.db.partners.Tables.PARTNERS_;

import com.erp.db.partners.tables.records.CustomersRecord;
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

/** Customer profiles (one per partner). */
@Repository
public class CustomerRepository {

    private static final Table<Record> CUSTOMER_PARTNERS = CUSTOMERS
            .join(PARTNERS_)
            .on(PARTNERS_.ID.eq(CUSTOMERS.PARTNER_ID))
            .and(PARTNERS_.COMPANY_ID.eq(CUSTOMERS.COMPANY_ID));

    private static final ListBinding BINDING = ListBinding.builder(PartnerListings.CUSTOMERS)
            .field("code", PARTNERS_.CODE)
            .field("name", PARTNERS_.NAME)
            .field("createdAt", PARTNERS_.CREATED_AT)
            .field("status", PARTNERS_.STATUS)
            .field("customerGroupId", CUSTOMERS.CUSTOMER_GROUP_ID)
            .field("currencyCode", CUSTOMERS.CURRENCY_CODE)
            .field("isOnHold", CUSTOMERS.IS_ON_HOLD)
            .tiebreaker(PARTNERS_.ID)
            .search(List.of(PARTNERS_.CODE, PARTNERS_.NAME, PARTNERS_.LEGAL_NAME, PARTNERS_.TAX_REGISTRATION_NO))
            .build();

    private final DSLContext dsl;
    private final KeysetPaginator paginator;

    public CustomerRepository(DSLContext dsl, KeysetPaginator paginator) {
        this.dsl = dsl;
        this.paginator = paginator;
    }

    public Optional<PartnerViews.Customer> find(UUID companyId, UUID partnerId) {
        return dsl.selectFrom(CUSTOMERS)
                .where(CUSTOMERS.COMPANY_ID.eq(companyId))
                .and(CUSTOMERS.PARTNER_ID.eq(partnerId))
                .fetchOptional(CustomerRepository::toView);
    }

    /** Inserts the profile (version 1) or updates it if it has the expected version. */
    public boolean save(UUID companyId, UUID partnerId, int expectedVersion, PartnerCommands.Customer c, UUID actor) {
        if (expectedVersion == 0) {
            return dsl.insertInto(CUSTOMERS)
                            .set(CUSTOMERS.PARTNER_ID, partnerId)
                            .set(CUSTOMERS.COMPANY_ID, companyId)
                            .set(CUSTOMERS.CUSTOMER_GROUP_ID, c.customerGroupId())
                            .set(CUSTOMERS.CURRENCY_CODE, c.currencyCode())
                            .set(CUSTOMERS.PAYMENT_TERMS_ID, c.paymentTermsId())
                            .set(CUSTOMERS.DEFAULT_TAX_CODE_ID, c.defaultTaxCodeId())
                            .set(CUSTOMERS.CREDIT_LIMIT, c.creditLimit())
                            .set(CUSTOMERS.IS_ON_HOLD, c.onHold())
                            .set(CUSTOMERS.CREATED_BY, actor)
                            .set(CUSTOMERS.UPDATED_BY, actor)
                            .set(CUSTOMERS.VERSION, 1)
                            .onConflictDoNothing()
                            .execute()
                    == 1;
        }
        return dsl.update(CUSTOMERS)
                        .set(CUSTOMERS.CUSTOMER_GROUP_ID, c.customerGroupId())
                        .set(CUSTOMERS.CURRENCY_CODE, c.currencyCode())
                        .set(CUSTOMERS.PAYMENT_TERMS_ID, c.paymentTermsId())
                        .set(CUSTOMERS.DEFAULT_TAX_CODE_ID, c.defaultTaxCodeId())
                        .set(CUSTOMERS.CREDIT_LIMIT, c.creditLimit())
                        .set(CUSTOMERS.IS_ON_HOLD, c.onHold())
                        .set(CUSTOMERS.UPDATED_AT, OffsetDateTime.now())
                        .set(CUSTOMERS.UPDATED_BY, actor)
                        .set(CUSTOMERS.VERSION, expectedVersion + 1)
                        .where(CUSTOMERS.COMPANY_ID.eq(companyId))
                        .and(CUSTOMERS.PARTNER_ID.eq(partnerId))
                        .and(CUSTOMERS.VERSION.eq(expectedVersion))
                        .execute()
                == 1;
    }

    public PageResponse<PartnerViews.CustomerListItem> list(UUID companyId, ListQuery query) {
        return paginator.fetch(
                dsl,
                CUSTOMER_PARTNERS,
                CUSTOMERS.COMPANY_ID.eq(companyId),
                query,
                BINDING,
                r -> new PartnerViews.CustomerListItem(PartnerRepository.toView(r), toView(r.into(CUSTOMERS))));
    }

    /** Customer (with partner) for the facade; {@code lock} takes the partner row {@code FOR SHARE}. */
    public Optional<PartnerViews.CustomerListItem> findWithPartner(UUID companyId, UUID partnerId, boolean lock) {
        var query = dsl.select()
                .from(CUSTOMER_PARTNERS)
                .where(CUSTOMERS.COMPANY_ID.eq(companyId))
                .and(CUSTOMERS.PARTNER_ID.eq(partnerId));
        return (lock ? query.forShare().of(PARTNERS_).fetchOptional() : query.fetchOptional())
                .map(r -> new PartnerViews.CustomerListItem(PartnerRepository.toView(r), toView(r.into(CUSTOMERS))));
    }

    static PartnerViews.Customer toView(CustomersRecord r) {
        return new PartnerViews.Customer(
                r.getPartnerId(),
                r.getCustomerGroupId(),
                r.getCurrencyCode(),
                r.getPaymentTermsId(),
                r.getDefaultTaxCodeId(),
                r.getCreditLimit(),
                r.getIsOnHold(),
                r.getUpdatedAt(),
                r.getVersion());
    }
}
