package com.erp.org.application;

import com.erp.org.persistence.ReferenceDataRepository;
import com.erp.platform.web.paging.ListQuery;
import com.erp.platform.web.paging.PageResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read use cases for global reference data (currencies and countries). */
@Service
public class ReferenceDataQueries {

    private final ReferenceDataRepository repository;

    public ReferenceDataQueries(ReferenceDataRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public PageResponse<CurrencyView> listCurrencies(ListQuery query) {
        return repository.findCurrencies(query);
    }

    @Transactional(readOnly = true)
    public PageResponse<CountryView> listCountries(ListQuery query) {
        return repository.findCountries(query);
    }
}
