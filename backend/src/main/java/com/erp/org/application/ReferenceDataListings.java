package com.erp.org.application;

import static com.erp.platform.web.paging.FilterOperator.EQ;
import static com.erp.platform.web.paging.FilterOperator.GT;
import static com.erp.platform.web.paging.FilterOperator.GTE;
import static com.erp.platform.web.paging.FilterOperator.IN;
import static com.erp.platform.web.paging.FilterOperator.LIKE;
import static com.erp.platform.web.paging.FilterOperator.LT;
import static com.erp.platform.web.paging.FilterOperator.LTE;

import com.erp.platform.web.paging.ListDefinition;
import com.erp.platform.web.paging.SortOrder;
import com.erp.platform.web.paging.ValueType;

/** List contracts of the reference-data endpoints (API.md §17.3). */
public final class ReferenceDataListings {

    public static final ListDefinition CURRENCIES = ListDefinition.builder("org.currencies")
            .sortable("code", "name", "minorUnits")
            .defaultSort(SortOrder.asc("code"))
            .filter("code", ValueType.STRING, EQ, IN, LIKE)
            .filter("isActive", ValueType.BOOLEAN, EQ)
            .filter("minorUnits", ValueType.INTEGER, EQ, IN, GT, GTE, LT, LTE)
            .searchable()
            .build();

    public static final ListDefinition COUNTRIES = ListDefinition.builder("org.countries")
            .sortable("code", "name")
            .defaultSort(SortOrder.asc("code"))
            .filter("code", ValueType.STRING, EQ, IN, LIKE)
            .searchable()
            .build();

    private ReferenceDataListings() {}
}
