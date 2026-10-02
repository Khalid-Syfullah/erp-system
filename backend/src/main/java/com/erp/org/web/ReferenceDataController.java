package com.erp.org.web;

import com.erp.org.application.CountryView;
import com.erp.org.application.CurrencyView;
import com.erp.org.application.ReferenceDataListings;
import com.erp.org.application.ReferenceDataQueries;
import com.erp.platform.security.AuthenticatedEndpoint;
import com.erp.platform.web.ApiPaths;
import com.erp.platform.web.paging.ListQueryParser;
import com.erp.platform.web.paging.PageResponse;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Global reference data (API.md §17.3). Readable by any authenticated principal. */
@RestController
@RequestMapping(ApiPaths.V1 + "/reference")
class ReferenceDataController {

    private final ReferenceDataQueries queries;
    private final ListQueryParser listQueryParser;

    ReferenceDataController(ReferenceDataQueries queries, ListQueryParser listQueryParser) {
        this.queries = queries;
        this.listQueryParser = listQueryParser;
    }

    @AuthenticatedEndpoint
    @GetMapping("/currencies")
    PageResponse<CurrencyResponse> currencies(@RequestParam MultiValueMap<String, String> parameters) {
        return queries.listCurrencies(listQueryParser.parse(parameters, ReferenceDataListings.CURRENCIES))
                .map(CurrencyResponse::from);
    }

    @AuthenticatedEndpoint
    @GetMapping("/countries")
    PageResponse<CountryResponse> countries(@RequestParam MultiValueMap<String, String> parameters) {
        return queries.listCountries(listQueryParser.parse(parameters, ReferenceDataListings.COUNTRIES))
                .map(CountryResponse::from);
    }

    record CurrencyResponse(String code, String name, int minorUnits, boolean isActive) {
        static CurrencyResponse from(CurrencyView view) {
            return new CurrencyResponse(view.code(), view.name(), view.minorUnits(), view.active());
        }
    }

    record CountryResponse(String code, String name) {
        static CountryResponse from(CountryView view) {
            return new CountryResponse(view.code(), view.name());
        }
    }
}
