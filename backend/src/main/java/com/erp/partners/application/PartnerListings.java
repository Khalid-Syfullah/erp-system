package com.erp.partners.application;

import static com.erp.platform.web.paging.FilterOperator.EQ;
import static com.erp.platform.web.paging.FilterOperator.IN;
import static com.erp.platform.web.paging.FilterOperator.LIKE;

import com.erp.platform.web.paging.ListDefinition;
import com.erp.platform.web.paging.SortOrder;
import com.erp.platform.web.paging.ValueType;
import java.util.Set;

/** List contracts of the Partners endpoints (API.md §17.4). */
public final class PartnerListings {

    public static final ListDefinition PARTNERS = ListDefinition.builder("partners.partners")
            .sortable("code", "name", "createdAt")
            .defaultSort(SortOrder.asc("code"))
            .filter("code", ValueType.STRING, EQ, IN, LIKE)
            .enumFilter("status", Set.of("ACTIVE", "INACTIVE", "BLOCKED"), EQ, IN)
            .enumFilter("partnerType", Set.of("ORGANIZATION", "INDIVIDUAL"), EQ)
            .filter("taxRegistrationNo", ValueType.STRING, EQ)
            .searchable()
            .build();

    public static final ListDefinition SUPPLIERS = ListDefinition.builder("partners.suppliers")
            .sortable("code", "name", "createdAt")
            .defaultSort(SortOrder.asc("code"))
            .filter("code", ValueType.STRING, EQ, IN, LIKE)
            .enumFilter("status", Set.of("ACTIVE", "INACTIVE", "BLOCKED"), EQ, IN)
            .filter("supplierGroupId", ValueType.UUID, EQ, IN)
            .filter("currencyCode", ValueType.STRING, EQ, IN)
            .searchable()
            .build();

    public static final ListDefinition GROUPS = ListDefinition.builder("partners.partner_groups")
            .sortable("code", "name")
            .defaultSort(SortOrder.asc("code"))
            .enumFilter("appliesTo", Set.of("CUSTOMER", "SUPPLIER"), EQ)
            .filter("isActive", ValueType.BOOLEAN, EQ)
            .searchable()
            .build();

    private PartnerListings() {}
}
