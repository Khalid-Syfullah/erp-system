package com.erp.admin.application;

import static com.erp.platform.web.paging.FilterOperator.EQ;
import static com.erp.platform.web.paging.FilterOperator.GTE;
import static com.erp.platform.web.paging.FilterOperator.IN;
import static com.erp.platform.web.paging.FilterOperator.LT;

import com.erp.platform.web.paging.ListDefinition;
import com.erp.platform.web.paging.SortOrder;
import com.erp.platform.web.paging.ValueType;

/** List contract of the audit log endpoints (API.md §17.2–§17.3). */
public final class AuditListings {

    public static final ListDefinition AUDIT_LOG = ListDefinition.builder("admin.audit_log")
            .sortable("occurredAt")
            .defaultSort(SortOrder.desc("occurredAt"))
            .filter("action", ValueType.STRING, EQ, IN)
            .filter("module", ValueType.STRING, EQ, IN)
            .filter("entityType", ValueType.STRING, EQ)
            .filter("entityId", ValueType.UUID, EQ)
            .filter("actorUserId", ValueType.UUID, EQ)
            .filter("companyId", ValueType.UUID, EQ)
            .filter("occurredAt", ValueType.DATE, GTE, LT)
            .build();

    private AuditListings() {}
}
