package com.erp.partners.persistence;

import static com.erp.db.partners.Tables.PARTNERS_;
import static com.erp.db.partners.Tables.PARTNER_ADDRESSES;
import static com.erp.db.partners.Tables.PARTNER_CONTACTS;
import static com.erp.db.partners.Tables.SUPPLIERS;

import com.erp.partners.application.PartnerCommands;
import com.erp.partners.application.PartnerListings;
import com.erp.partners.application.PartnerViews;
import com.erp.platform.jooq.KeysetPaginator;
import com.erp.platform.jooq.ListBinding;
import com.erp.platform.web.paging.ListQuery;
import com.erp.platform.web.paging.PageResponse;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SelectField;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;

/** Partners with their addresses and contacts. */
@Repository
public class PartnerRepository {

    private static final ListBinding BINDING = ListBinding.builder(PartnerListings.PARTNERS)
            .field("code", PARTNERS_.CODE)
            .field("name", PARTNERS_.NAME)
            .field("createdAt", PARTNERS_.CREATED_AT)
            .field("status", PARTNERS_.STATUS)
            .field("partnerType", PARTNERS_.PARTNER_TYPE)
            .field("taxRegistrationNo", PARTNERS_.TAX_REGISTRATION_NO)
            .tiebreaker(PARTNERS_.ID)
            .search(List.of(PARTNERS_.CODE, PARTNERS_.NAME, PARTNERS_.LEGAL_NAME, PARTNERS_.TAX_REGISTRATION_NO))
            .build();

    /** Partners with their (optional) supplier profile: the profile's key tells whether it exists. */
    private static final Table<Record> WITH_PROFILE =
            PARTNERS_.leftJoin(SUPPLIERS).on(SUPPLIERS.PARTNER_ID.eq(PARTNERS_.ID));

    private final DSLContext dsl;
    private final KeysetPaginator paginator;

    public PartnerRepository(DSLContext dsl, KeysetPaginator paginator) {
        this.dsl = dsl;
        this.paginator = paginator;
    }

    // ---------------------------------------------------------------------------- partners

    public UUID insert(UUID companyId, PartnerCommands.Partner c, UUID actor) {
        return dsl.insertInto(PARTNERS_)
                .set(PARTNERS_.COMPANY_ID, companyId)
                .set(PARTNERS_.CODE, c.code())
                .set(PARTNERS_.NAME, c.name())
                .set(PARTNERS_.LEGAL_NAME, c.legalName())
                .set(PARTNERS_.PARTNER_TYPE, c.partnerType())
                .set(PARTNERS_.TAX_REGISTRATION_NO, c.taxRegistrationNo())
                .set(PARTNERS_.EMAIL, c.email())
                .set(PARTNERS_.PHONE, c.phone())
                .set(PARTNERS_.WEBSITE, c.website())
                .set(PARTNERS_.NOTES, c.notes())
                .set(PARTNERS_.CREATED_BY, actor)
                .set(PARTNERS_.UPDATED_BY, actor)
                .returning(PARTNERS_.ID)
                .fetchOne(PARTNERS_.ID);
    }

    public Optional<PartnerViews.Partner> find(UUID companyId, UUID id) {
        return dsl.select(columns())
                .from(WITH_PROFILE)
                .where(PARTNERS_.COMPANY_ID.eq(companyId))
                .and(PARTNERS_.ID.eq(id))
                .fetchOptional(PartnerRepository::toView);
    }

    /** The partner locked {@code FOR NO KEY UPDATE} (edits and status changes serialize on it). */
    public Optional<PartnerViews.Partner> lockForChange(UUID companyId, UUID id) {
        return dsl.select(columns())
                .from(WITH_PROFILE)
                .where(PARTNERS_.COMPANY_ID.eq(companyId))
                .and(PARTNERS_.ID.eq(id))
                .forNoKeyUpdate()
                .of(PARTNERS_)
                .fetchOptional(PartnerRepository::toView);
    }

    /** Locks the partner {@code FOR SHARE}: a new document's use waits for a running status change. */
    public void lockForUse(UUID companyId, UUID id) {
        dsl.select(PARTNERS_.ID)
                .from(PARTNERS_)
                .where(PARTNERS_.COMPANY_ID.eq(companyId))
                .and(PARTNERS_.ID.eq(id))
                .forShare()
                .fetch();
    }

    public PageResponse<PartnerViews.Partner> list(UUID companyId, ListQuery query) {
        return paginator.fetch(
                dsl, WITH_PROFILE, PARTNERS_.COMPANY_ID.eq(companyId), query, BINDING, PartnerRepository::toView);
    }

    public List<PartnerViews.Partner> findAll(UUID companyId, Collection<UUID> ids) {
        return dsl.select(columns())
                .from(WITH_PROFILE)
                .where(PARTNERS_.COMPANY_ID.eq(companyId))
                .and(PARTNERS_.ID.in(ids))
                .fetch(PartnerRepository::toView);
    }

    public boolean update(UUID companyId, UUID id, int version, UUID actor, PartnerCommands.Partner c, String status) {
        return dsl.update(PARTNERS_)
                        .set(PARTNERS_.NAME, c.name())
                        .set(PARTNERS_.LEGAL_NAME, c.legalName())
                        .set(PARTNERS_.PARTNER_TYPE, c.partnerType())
                        .set(PARTNERS_.TAX_REGISTRATION_NO, c.taxRegistrationNo())
                        .set(PARTNERS_.EMAIL, c.email())
                        .set(PARTNERS_.PHONE, c.phone())
                        .set(PARTNERS_.WEBSITE, c.website())
                        .set(PARTNERS_.NOTES, c.notes())
                        .set(PARTNERS_.STATUS, status)
                        .set(PARTNERS_.UPDATED_AT, OffsetDateTime.now())
                        .set(PARTNERS_.UPDATED_BY, actor)
                        .set(PARTNERS_.VERSION, version + 1)
                        .where(PARTNERS_.COMPANY_ID.eq(companyId))
                        .and(PARTNERS_.ID.eq(id))
                        .and(PARTNERS_.VERSION.eq(version))
                        .execute()
                == 1;
    }

    // ---------------------------------------------------------------------------- addresses

    public List<PartnerViews.Address> addresses(UUID companyId, UUID partnerId) {
        return dsl.selectFrom(PARTNER_ADDRESSES)
                .where(PARTNER_ADDRESSES.COMPANY_ID.eq(companyId))
                .and(PARTNER_ADDRESSES.PARTNER_ID.eq(partnerId))
                .orderBy(PARTNER_ADDRESSES.ADDRESS_TYPE, PARTNER_ADDRESSES.ID)
                .fetch(r -> new PartnerViews.Address(
                        r.getId(),
                        r.getPartnerId(),
                        r.getAddressType(),
                        r.getLine1(),
                        r.getLine2(),
                        r.getCity(),
                        r.getRegion(),
                        r.getPostalCode(),
                        r.getCountryCode(),
                        r.getIsDefault(),
                        r.getVersion()));
    }

    public UUID insertAddress(UUID companyId, UUID partnerId, PartnerCommands.Address c, UUID actor) {
        return dsl.insertInto(PARTNER_ADDRESSES)
                .set(PARTNER_ADDRESSES.COMPANY_ID, companyId)
                .set(PARTNER_ADDRESSES.PARTNER_ID, partnerId)
                .set(PARTNER_ADDRESSES.ADDRESS_TYPE, c.addressType())
                .set(PARTNER_ADDRESSES.LINE1, c.line1())
                .set(PARTNER_ADDRESSES.LINE2, c.line2())
                .set(PARTNER_ADDRESSES.CITY, c.city())
                .set(PARTNER_ADDRESSES.REGION, c.region())
                .set(PARTNER_ADDRESSES.POSTAL_CODE, c.postalCode())
                .set(PARTNER_ADDRESSES.COUNTRY_CODE, c.countryCode())
                .set(PARTNER_ADDRESSES.IS_DEFAULT, c.isDefault())
                .set(PARTNER_ADDRESSES.CREATED_BY, actor)
                .set(PARTNER_ADDRESSES.UPDATED_BY, actor)
                .returning(PARTNER_ADDRESSES.ID)
                .fetchOne(PARTNER_ADDRESSES.ID);
    }

    public boolean updateAddress(
            UUID companyId, UUID partnerId, UUID id, int version, UUID actor, PartnerCommands.Address c) {
        return dsl.update(PARTNER_ADDRESSES)
                        .set(PARTNER_ADDRESSES.ADDRESS_TYPE, c.addressType())
                        .set(PARTNER_ADDRESSES.LINE1, c.line1())
                        .set(PARTNER_ADDRESSES.LINE2, c.line2())
                        .set(PARTNER_ADDRESSES.CITY, c.city())
                        .set(PARTNER_ADDRESSES.REGION, c.region())
                        .set(PARTNER_ADDRESSES.POSTAL_CODE, c.postalCode())
                        .set(PARTNER_ADDRESSES.COUNTRY_CODE, c.countryCode())
                        .set(PARTNER_ADDRESSES.IS_DEFAULT, c.isDefault())
                        .set(PARTNER_ADDRESSES.UPDATED_AT, OffsetDateTime.now())
                        .set(PARTNER_ADDRESSES.UPDATED_BY, actor)
                        .set(PARTNER_ADDRESSES.VERSION, version + 1)
                        .where(PARTNER_ADDRESSES.COMPANY_ID.eq(companyId))
                        .and(PARTNER_ADDRESSES.PARTNER_ID.eq(partnerId))
                        .and(PARTNER_ADDRESSES.ID.eq(id))
                        .and(PARTNER_ADDRESSES.VERSION.eq(version))
                        .execute()
                == 1;
    }

    /** Clears the default flag of the partner's other addresses of the type (one default per type). */
    public void clearDefaultAddress(UUID companyId, UUID partnerId, String addressType, @Nullable UUID except) {
        dsl.update(PARTNER_ADDRESSES)
                .set(PARTNER_ADDRESSES.IS_DEFAULT, false)
                .where(PARTNER_ADDRESSES.COMPANY_ID.eq(companyId))
                .and(PARTNER_ADDRESSES.PARTNER_ID.eq(partnerId))
                .and(PARTNER_ADDRESSES.ADDRESS_TYPE.eq(addressType))
                .and(PARTNER_ADDRESSES.IS_DEFAULT.isTrue())
                .and(except == null ? DSL.noCondition() : PARTNER_ADDRESSES.ID.ne(except))
                .execute();
    }

    public boolean deleteAddress(UUID companyId, UUID partnerId, UUID id) {
        return dsl.deleteFrom(PARTNER_ADDRESSES)
                        .where(PARTNER_ADDRESSES.COMPANY_ID.eq(companyId))
                        .and(PARTNER_ADDRESSES.PARTNER_ID.eq(partnerId))
                        .and(PARTNER_ADDRESSES.ID.eq(id))
                        .execute()
                == 1;
    }

    // ----------------------------------------------------------------------------- contacts

    public List<PartnerViews.Contact> contacts(UUID companyId, UUID partnerId) {
        return dsl.selectFrom(PARTNER_CONTACTS)
                .where(PARTNER_CONTACTS.COMPANY_ID.eq(companyId))
                .and(PARTNER_CONTACTS.PARTNER_ID.eq(partnerId))
                .orderBy(PARTNER_CONTACTS.NAME, PARTNER_CONTACTS.ID)
                .fetch(r -> new PartnerViews.Contact(
                        r.getId(),
                        r.getPartnerId(),
                        r.getName(),
                        r.getEmail(),
                        r.getPhone(),
                        r.getRoleTitle(),
                        r.getIsPrimary(),
                        r.getVersion()));
    }

    public UUID insertContact(UUID companyId, UUID partnerId, PartnerCommands.Contact c, UUID actor) {
        return dsl.insertInto(PARTNER_CONTACTS)
                .set(PARTNER_CONTACTS.COMPANY_ID, companyId)
                .set(PARTNER_CONTACTS.PARTNER_ID, partnerId)
                .set(PARTNER_CONTACTS.NAME, c.name())
                .set(PARTNER_CONTACTS.EMAIL, c.email())
                .set(PARTNER_CONTACTS.PHONE, c.phone())
                .set(PARTNER_CONTACTS.ROLE_TITLE, c.roleTitle())
                .set(PARTNER_CONTACTS.IS_PRIMARY, c.isPrimary())
                .set(PARTNER_CONTACTS.CREATED_BY, actor)
                .set(PARTNER_CONTACTS.UPDATED_BY, actor)
                .returning(PARTNER_CONTACTS.ID)
                .fetchOne(PARTNER_CONTACTS.ID);
    }

    public boolean updateContact(
            UUID companyId, UUID partnerId, UUID id, int version, UUID actor, PartnerCommands.Contact c) {
        return dsl.update(PARTNER_CONTACTS)
                        .set(PARTNER_CONTACTS.NAME, c.name())
                        .set(PARTNER_CONTACTS.EMAIL, c.email())
                        .set(PARTNER_CONTACTS.PHONE, c.phone())
                        .set(PARTNER_CONTACTS.ROLE_TITLE, c.roleTitle())
                        .set(PARTNER_CONTACTS.IS_PRIMARY, c.isPrimary())
                        .set(PARTNER_CONTACTS.UPDATED_AT, OffsetDateTime.now())
                        .set(PARTNER_CONTACTS.UPDATED_BY, actor)
                        .set(PARTNER_CONTACTS.VERSION, version + 1)
                        .where(PARTNER_CONTACTS.COMPANY_ID.eq(companyId))
                        .and(PARTNER_CONTACTS.PARTNER_ID.eq(partnerId))
                        .and(PARTNER_CONTACTS.ID.eq(id))
                        .and(PARTNER_CONTACTS.VERSION.eq(version))
                        .execute()
                == 1;
    }

    public void clearPrimaryContact(UUID companyId, UUID partnerId, @Nullable UUID except) {
        dsl.update(PARTNER_CONTACTS)
                .set(PARTNER_CONTACTS.IS_PRIMARY, false)
                .where(PARTNER_CONTACTS.COMPANY_ID.eq(companyId))
                .and(PARTNER_CONTACTS.PARTNER_ID.eq(partnerId))
                .and(PARTNER_CONTACTS.IS_PRIMARY.isTrue())
                .and(except == null ? DSL.noCondition() : PARTNER_CONTACTS.ID.ne(except))
                .execute();
    }

    public boolean deleteContact(UUID companyId, UUID partnerId, UUID id) {
        return dsl.deleteFrom(PARTNER_CONTACTS)
                        .where(PARTNER_CONTACTS.COMPANY_ID.eq(companyId))
                        .and(PARTNER_CONTACTS.PARTNER_ID.eq(partnerId))
                        .and(PARTNER_CONTACTS.ID.eq(id))
                        .execute()
                == 1;
    }

    // ------------------------------------------------------------------------------ mapping

    private static List<SelectField<?>> columns() {
        List<SelectField<?>> fields = new java.util.ArrayList<>(List.of(PARTNERS_.fields()));
        fields.add(SUPPLIERS.PARTNER_ID);
        return fields;
    }

    static PartnerViews.Partner toView(Record r) {
        return new PartnerViews.Partner(
                r.get(PARTNERS_.ID),
                r.get(PARTNERS_.COMPANY_ID),
                r.get(PARTNERS_.CODE),
                r.get(PARTNERS_.NAME),
                r.get(PARTNERS_.LEGAL_NAME),
                r.get(PARTNERS_.PARTNER_TYPE),
                r.get(PARTNERS_.TAX_REGISTRATION_NO),
                r.get(PARTNERS_.EMAIL),
                r.get(PARTNERS_.PHONE),
                r.get(PARTNERS_.WEBSITE),
                r.get(PARTNERS_.STATUS),
                r.get(PARTNERS_.NOTES),
                r.get(SUPPLIERS.PARTNER_ID) != null,
                r.get(PARTNERS_.CREATED_AT),
                r.get(PARTNERS_.UPDATED_AT),
                r.get(PARTNERS_.VERSION));
    }
}
