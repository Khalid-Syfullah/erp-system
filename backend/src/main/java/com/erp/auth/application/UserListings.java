package com.erp.auth.application;

import static com.erp.platform.web.paging.FilterOperator.EQ;
import static com.erp.platform.web.paging.FilterOperator.IN;
import static com.erp.platform.web.paging.FilterOperator.LIKE;

import com.erp.platform.web.paging.ListDefinition;
import com.erp.platform.web.paging.SortOrder;
import com.erp.platform.web.paging.ValueType;
import java.util.Set;

/** List contract of {@code GET /admin/users} and {@code GET /admin/service-accounts} (API.md §17.2). */
public final class UserListings {

    public static final ListDefinition USERS = ListDefinition.builder("auth.users")
            .sortable("email", "displayName", "createdAt")
            .defaultSort(SortOrder.asc("email"))
            .filter("email", ValueType.STRING, EQ, LIKE)
            .enumFilter("status", Set.of("INVITED", "ACTIVE", "LOCKED", "DISABLED"), EQ, IN)
            .enumFilter("userType", Set.of("HUMAN", "SERVICE"), EQ)
            .filter("isSystemAdmin", ValueType.BOOLEAN, EQ)
            .searchable()
            .build();

    private UserListings() {}
}
