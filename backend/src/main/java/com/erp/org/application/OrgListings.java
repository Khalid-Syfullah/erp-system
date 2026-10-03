package com.erp.org.application;

import static com.erp.platform.web.paging.FilterOperator.EQ;
import static com.erp.platform.web.paging.FilterOperator.IN;
import static com.erp.platform.web.paging.FilterOperator.LIKE;

import com.erp.platform.web.paging.ListDefinition;
import com.erp.platform.web.paging.SortOrder;
import com.erp.platform.web.paging.ValueType;
import java.util.Set;

/** List contracts of the company and branch endpoints (API.md §17.3). */
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

    private OrgListings() {}
}
