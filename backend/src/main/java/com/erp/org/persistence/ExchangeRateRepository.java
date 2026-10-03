package com.erp.org.persistence;

import static com.erp.db.org.Tables.EXCHANGE_RATES;

import com.erp.db.org.tables.records.ExchangeRatesRecord;
import com.erp.org.application.ExchangeRateView;
import com.erp.org.application.OrgListings;
import com.erp.platform.jooq.KeysetPaginator;
import com.erp.platform.jooq.ListBinding;
import com.erp.platform.web.paging.ListQuery;
import com.erp.platform.web.paging.PageResponse;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

/** Exchange rates per company, currency and date (DATABASE.md §5.2). */
@Repository
public class ExchangeRateRepository {

    private static final ListBinding BINDING = ListBinding.builder(OrgListings.EXCHANGE_RATES)
            .field("rateDate", EXCHANGE_RATES.RATE_DATE)
            .field("currencyCode", EXCHANGE_RATES.CURRENCY_CODE)
            .field("createdAt", EXCHANGE_RATES.CREATED_AT)
            .field("source", EXCHANGE_RATES.SOURCE)
            .tiebreaker(EXCHANGE_RATES.ID)
            .build();

    private final DSLContext dsl;
    private final KeysetPaginator paginator;

    public ExchangeRateRepository(DSLContext dsl, KeysetPaginator paginator) {
        this.dsl = dsl;
        this.paginator = paginator;
    }

    public UUID insert(UUID companyId, String currencyCode, LocalDate rateDate, BigDecimal rate, UUID actor) {
        return dsl.insertInto(EXCHANGE_RATES)
                .set(EXCHANGE_RATES.COMPANY_ID, companyId)
                .set(EXCHANGE_RATES.CURRENCY_CODE, currencyCode)
                .set(EXCHANGE_RATES.RATE_DATE, rateDate)
                .set(EXCHANGE_RATES.RATE, rate)
                .set(EXCHANGE_RATES.CREATED_BY, actor)
                .set(EXCHANGE_RATES.UPDATED_BY, actor)
                .returning(EXCHANGE_RATES.ID)
                .fetchOne(EXCHANGE_RATES.ID);
    }

    public Optional<ExchangeRateView> find(UUID companyId, UUID id) {
        return dsl.selectFrom(EXCHANGE_RATES)
                .where(EXCHANGE_RATES.COMPANY_ID.eq(companyId))
                .and(EXCHANGE_RATES.ID.eq(id))
                .fetchOptional()
                .map(ExchangeRateRepository::toView);
    }

    public Optional<ExchangeRateView> lockForChange(UUID companyId, UUID id) {
        return dsl.selectFrom(EXCHANGE_RATES)
                .where(EXCHANGE_RATES.COMPANY_ID.eq(companyId))
                .and(EXCHANGE_RATES.ID.eq(id))
                .forNoKeyUpdate()
                .fetchOptional()
                .map(ExchangeRateRepository::toView);
    }

    public PageResponse<ExchangeRateView> list(UUID companyId, ListQuery query) {
        return paginator.fetch(
                dsl,
                EXCHANGE_RATES,
                EXCHANGE_RATES.COMPANY_ID.eq(companyId),
                query,
                BINDING,
                ExchangeRateRepository::toView);
    }

    /** The rate with the latest {@code rate_date} on or before {@code date}. */
    public Optional<ExchangeRateView> latestOnOrBefore(UUID companyId, String currencyCode, LocalDate date) {
        return dsl.selectFrom(EXCHANGE_RATES)
                .where(EXCHANGE_RATES.COMPANY_ID.eq(companyId))
                .and(EXCHANGE_RATES.CURRENCY_CODE.eq(currencyCode))
                .and(EXCHANGE_RATES.RATE_DATE.le(date))
                .orderBy(EXCHANGE_RATES.RATE_DATE.desc())
                .limit(1)
                .fetchOptional()
                .map(ExchangeRateRepository::toView);
    }

    public boolean updateRate(UUID companyId, UUID id, int expectedVersion, UUID actor, BigDecimal rate) {
        return dsl.update(EXCHANGE_RATES)
                        .set(EXCHANGE_RATES.RATE, rate)
                        .set(EXCHANGE_RATES.UPDATED_AT, OffsetDateTime.now())
                        .set(EXCHANGE_RATES.UPDATED_BY, actor)
                        .set(EXCHANGE_RATES.VERSION, expectedVersion + 1)
                        .where(EXCHANGE_RATES.COMPANY_ID.eq(companyId))
                        .and(EXCHANGE_RATES.ID.eq(id))
                        .and(EXCHANGE_RATES.VERSION.eq(expectedVersion))
                        .execute()
                == 1;
    }

    public boolean delete(UUID companyId, UUID id, int expectedVersion) {
        return dsl.deleteFrom(EXCHANGE_RATES)
                        .where(EXCHANGE_RATES.COMPANY_ID.eq(companyId))
                        .and(EXCHANGE_RATES.ID.eq(id))
                        .and(EXCHANGE_RATES.VERSION.eq(expectedVersion))
                        .execute()
                == 1;
    }

    static ExchangeRateView toView(ExchangeRatesRecord r) {
        return new ExchangeRateView(
                r.getId(),
                r.getCompanyId(),
                r.getCurrencyCode(),
                r.getRateDate(),
                r.getRate(),
                r.getSource(),
                r.getCreatedAt(),
                r.getUpdatedAt(),
                r.getVersion());
    }
}
