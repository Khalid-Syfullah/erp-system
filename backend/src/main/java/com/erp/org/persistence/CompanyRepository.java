package com.erp.org.persistence;

import static com.erp.db.org.Tables.COMPANIES;
import static com.erp.db.org.Tables.COUNTRIES;
import static com.erp.db.org.Tables.CURRENCIES;

import com.erp.db.org.tables.records.CompaniesRecord;
import com.erp.org.api.CompanyProfile;
import com.erp.org.api.CompanySummary;
import com.erp.org.api.CurrencyInfo;
import com.erp.org.application.CompanyCommands;
import com.erp.org.application.CompanyView;
import com.erp.org.application.OrgListings;
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
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

@Repository
public class CompanyRepository {

    private static final ListBinding BINDING = ListBinding.builder(OrgListings.COMPANIES)
            .field("code", COMPANIES.CODE)
            .field("displayName", COMPANIES.DISPLAY_NAME)
            .field("createdAt", COMPANIES.CREATED_AT)
            .field("status", COMPANIES.STATUS)
            .field("countryCode", COMPANIES.COUNTRY_CODE)
            .tiebreaker(COMPANIES.ID)
            .search(List.of(COMPANIES.CODE, COMPANIES.DISPLAY_NAME, COMPANIES.LEGAL_NAME))
            .build();

    private final DSLContext dsl;
    private final KeysetPaginator paginator;

    public CompanyRepository(DSLContext dsl, KeysetPaginator paginator) {
        this.dsl = dsl;
        this.paginator = paginator;
    }

    public UUID insert(CompanyCommands.Create c, UUID actor) {
        return dsl.insertInto(COMPANIES)
                .set(COMPANIES.CODE, c.code())
                .set(COMPANIES.LEGAL_NAME, c.legalName())
                .set(COMPANIES.DISPLAY_NAME, c.displayName())
                .set(COMPANIES.TAX_REGISTRATION_NO, c.taxRegistrationNo())
                .set(COMPANIES.REGISTRATION_NO, c.registrationNo())
                .set(COMPANIES.COUNTRY_CODE, c.countryCode())
                .set(COMPANIES.BASE_CURRENCY, c.baseCurrency())
                .set(COMPANIES.TIMEZONE, c.timezone())
                .set(COMPANIES.FISCAL_YEAR_START_MONTH, (short) c.fiscalYearStartMonth())
                .set(COMPANIES.ADDRESS_LINE1, c.addressLine1())
                .set(COMPANIES.ADDRESS_LINE2, c.addressLine2())
                .set(COMPANIES.CITY, c.city())
                .set(COMPANIES.REGION, c.region())
                .set(COMPANIES.POSTAL_CODE, c.postalCode())
                .set(COMPANIES.CREATED_BY, actor)
                .set(COMPANIES.UPDATED_BY, actor)
                .returning(COMPANIES.ID)
                .fetchOne(COMPANIES.ID);
    }

    public Optional<CompanyView> find(UUID id) {
        return dsl.selectFrom(COMPANIES)
                .where(COMPANIES.ID.eq(id))
                .fetchOptional()
                .map(CompanyRepository::toView);
    }

    /** Optimistic update: false if the version no longer matches. */
    public boolean update(UUID id, int expectedVersion, UUID actor, CompanyCommands.UpdateCompany c) {
        return dsl.update(COMPANIES)
                        .set(COMPANIES.LEGAL_NAME, c.legalName())
                        .set(COMPANIES.DISPLAY_NAME, c.displayName())
                        .set(COMPANIES.TAX_REGISTRATION_NO, c.taxRegistrationNo())
                        .set(COMPANIES.REGISTRATION_NO, c.registrationNo())
                        .set(COMPANIES.TIMEZONE, c.timezone())
                        .set(COMPANIES.FISCAL_YEAR_START_MONTH, (short) c.fiscalYearStartMonth())
                        .set(COMPANIES.ADDRESS_LINE1, c.addressLine1())
                        .set(COMPANIES.ADDRESS_LINE2, c.addressLine2())
                        .set(COMPANIES.CITY, c.city())
                        .set(COMPANIES.REGION, c.region())
                        .set(COMPANIES.POSTAL_CODE, c.postalCode())
                        .set(COMPANIES.ROUNDING_MODE, c.roundingMode())
                        .set(COMPANIES.TAX_ROUNDING, c.taxRounding())
                        .set(COMPANIES.STATUS, c.status())
                        .set(COMPANIES.UPDATED_AT, OffsetDateTime.now())
                        .set(COMPANIES.UPDATED_BY, actor)
                        .set(COMPANIES.VERSION, expectedVersion + 1)
                        .where(COMPANIES.ID.eq(id))
                        .and(COMPANIES.VERSION.eq(expectedVersion))
                        .execute()
                == 1;
    }

    public PageResponse<CompanyView> list(ListQuery query) {
        return paginator.fetch(dsl, COMPANIES, DSL.noCondition(), query, BINDING, CompanyRepository::toView);
    }

    public List<CompanySummary> summaries(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return dsl.selectFrom(COMPANIES)
                .where(COMPANIES.ID.in(ids))
                .orderBy(COMPANIES.CODE)
                .fetch(r -> new CompanySummary(
                        r.getId(),
                        r.getCode(),
                        r.getDisplayName(),
                        r.getBaseCurrency(),
                        r.getTimezone(),
                        "ACTIVE".equals(r.getStatus())));
    }

    public Optional<CompanyProfile> profile(UUID id) {
        return dsl.select(
                        COMPANIES.ID,
                        COMPANIES.BASE_CURRENCY,
                        CURRENCIES.MINOR_UNITS,
                        COMPANIES.ROUNDING_MODE,
                        COMPANIES.TAX_ROUNDING,
                        COMPANIES.FISCAL_YEAR_START_MONTH,
                        COMPANIES.TIMEZONE,
                        COMPANIES.STATUS,
                        COMPANIES.COUNTRY_CODE)
                .from(COMPANIES)
                .join(CURRENCIES)
                .on(CURRENCIES.CODE.eq(COMPANIES.BASE_CURRENCY))
                .where(COMPANIES.ID.eq(id))
                .fetchOptional(r -> new CompanyProfile(
                        r.value1(),
                        r.value2(),
                        r.value3(),
                        r.value4(),
                        r.value5(),
                        r.value6(),
                        r.value7(),
                        "ACTIVE".equals(r.value8()),
                        r.value9()));
    }

    public List<UUID> allIds() {
        return dsl.select(COMPANIES.ID).from(COMPANIES).orderBy(COMPANIES.CODE).fetch(COMPANIES.ID);
    }

    public boolean countryExists(String code) {
        return dsl.fetchExists(COUNTRIES, COUNTRIES.CODE.eq(code));
    }

    public Optional<CurrencyInfo> currency(String code) {
        return dsl.selectFrom(CURRENCIES)
                .where(CURRENCIES.CODE.eq(code))
                .fetchOptional(r -> new CurrencyInfo(r.getCode(), r.getMinorUnits(), r.getIsActive()));
    }

    public boolean activeCurrencyExists(String code) {
        return dsl.fetchExists(CURRENCIES, CURRENCIES.CODE.eq(code).and(CURRENCIES.IS_ACTIVE.isTrue()));
    }

    static CompanyView toView(CompaniesRecord r) {
        return new CompanyView(
                r.getId(),
                r.getCode(),
                r.getLegalName(),
                r.getDisplayName(),
                r.getTaxRegistrationNo(),
                r.getRegistrationNo(),
                r.getCountryCode(),
                r.getBaseCurrency(),
                r.getTimezone(),
                r.getFiscalYearStartMonth(),
                r.getAddressLine1(),
                r.getAddressLine2(),
                r.getCity(),
                r.getRegion(),
                r.getPostalCode(),
                r.getRoundingMode(),
                r.getTaxRounding(),
                r.getStatus(),
                r.getCreatedAt(),
                r.getUpdatedAt(),
                r.getVersion());
    }
}
