package com.erp.org.persistence;

import static com.erp.db.org.Tables.COUNTRIES;
import static com.erp.db.org.Tables.CURRENCIES;

import com.erp.org.application.CountryView;
import com.erp.org.application.CurrencyView;
import com.erp.org.application.ReferenceDataListings;
import com.erp.platform.jooq.KeysetPaginator;
import com.erp.platform.jooq.ListBinding;
import com.erp.platform.web.paging.ListQuery;
import com.erp.platform.web.paging.PageResponse;
import java.util.List;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

@Repository
public class ReferenceDataRepository {

    private static final ListBinding CURRENCY_BINDING = ListBinding.builder(ReferenceDataListings.CURRENCIES)
            .field("code", CURRENCIES.CODE)
            .field("name", CURRENCIES.NAME)
            .field("minorUnits", CURRENCIES.MINOR_UNITS)
            .field("isActive", CURRENCIES.IS_ACTIVE)
            .tiebreaker(CURRENCIES.CODE)
            .search(List.of(CURRENCIES.CODE, CURRENCIES.NAME))
            .build();

    private static final ListBinding COUNTRY_BINDING = ListBinding.builder(ReferenceDataListings.COUNTRIES)
            .field("code", COUNTRIES.CODE)
            .field("name", COUNTRIES.NAME)
            .tiebreaker(COUNTRIES.CODE)
            .search(List.of(COUNTRIES.CODE, COUNTRIES.NAME))
            .build();

    private final DSLContext dsl;
    private final KeysetPaginator paginator;

    public ReferenceDataRepository(DSLContext dsl, KeysetPaginator paginator) {
        this.dsl = dsl;
        this.paginator = paginator;
    }

    public PageResponse<CurrencyView> findCurrencies(ListQuery query) {
        return paginator.fetch(
                dsl,
                CURRENCIES,
                DSL.noCondition(),
                query,
                CURRENCY_BINDING,
                r -> new CurrencyView(r.getCode(), r.getName(), r.getMinorUnits(), r.getIsActive()));
    }

    public PageResponse<CountryView> findCountries(ListQuery query) {
        return paginator.fetch(
                dsl,
                COUNTRIES,
                DSL.noCondition(),
                query,
                COUNTRY_BINDING,
                r -> new CountryView(r.getCode(), r.getName()));
    }
}
