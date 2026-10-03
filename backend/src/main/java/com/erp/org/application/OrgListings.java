package com.erp.org.application;

import static com.erp.platform.web.paging.FilterOperator.EQ;
import static com.erp.platform.web.paging.FilterOperator.GT;
import static com.erp.platform.web.paging.FilterOperator.GTE;
import static com.erp.platform.web.paging.FilterOperator.IN;
import static com.erp.platform.web.paging.FilterOperator.IS_NULL;
import static com.erp.platform.web.paging.FilterOperator.LIKE;
import static com.erp.platform.web.paging.FilterOperator.LT;
import static com.erp.platform.web.paging.FilterOperator.LTE;

import com.erp.platform.web.paging.ListDefinition;
import com.erp.platform.web.paging.SortOrder;
import com.erp.platform.web.paging.ValueType;
import java.util.Set;

/** List contracts of the Organization endpoints (API.md §17.3). */
public final class OrgListings {

    public static final ListDefinition COMPANIES = ListDefinition.builder("org.companies")
            .sortable("code", "displayName", "createdAt")
            .defaultSort(SortOrder.asc("code"))
            .filter("code", ValueType.STRING, EQ, IN, LIKE)
            .enumFilter("status", Set.of("ACTIVE", "INACTIVE"), EQ)
            .filter("countryCode", ValueType.STRING, EQ, IN)
            .searchable()
            .build();

    public static final ListDefinition BRANCHES = ListDefinition.builder("org.branches")
            .sortable("code", "name", "createdAt")
            .defaultSort(SortOrder.asc("code"))
            .filter("code", ValueType.STRING, EQ, IN, LIKE)
            .filter("isActive", ValueType.BOOLEAN, EQ)
            .searchable()
            .build();

    public static final ListDefinition DEPARTMENTS = ListDefinition.builder("org.departments")
            .sortable("code", "name", "createdAt")
            .defaultSort(SortOrder.asc("code"))
            .filter("code", ValueType.STRING, EQ, IN, LIKE)
            .filter("isActive", ValueType.BOOLEAN, EQ)
            .filter("parentId", ValueType.UUID, EQ, IN, IS_NULL)
            .filter("branchId", ValueType.UUID, EQ, IN, IS_NULL)
            .searchable()
            .build();

    public static final ListDefinition EXCHANGE_RATES = ListDefinition.builder("org.exchange_rates")
            .sortable("rateDate", "currencyCode", "createdAt")
            .defaultSort(SortOrder.desc("rateDate"), SortOrder.asc("currencyCode"))
            .filter("currencyCode", ValueType.STRING, EQ, IN)
            .filter("rateDate", ValueType.DATE, EQ, GTE, LTE, GT, LT)
            .enumFilter("source", Set.of("MANUAL", "IMPORT"), EQ)
            .build();

    public static final ListDefinition TAX_CODES = ListDefinition.builder("org.tax_codes")
            .sortable("code", "name", "createdAt")
            .defaultSort(SortOrder.asc("code"))
            .filter("code", ValueType.STRING, EQ, IN, LIKE)
            .enumFilter("scope", Set.of("SALES", "PURCHASE", "BOTH"), EQ, IN)
            .filter("isActive", ValueType.BOOLEAN, EQ)
            .filter("isExempt", ValueType.BOOLEAN, EQ)
            .searchable()
            .build();

    public static final ListDefinition PAYMENT_TERMS = ListDefinition.builder("org.payment_terms")
            .sortable("code", "name", "dueDays", "createdAt")
            .defaultSort(SortOrder.asc("code"))
            .filter("code", ValueType.STRING, EQ, IN, LIKE)
            .enumFilter("dueBasis", Set.of("DOCUMENT_DATE", "END_OF_MONTH"), EQ)
            .filter("isActive", ValueType.BOOLEAN, EQ)
            .searchable()
            .build();

    private OrgListings() {}
}
