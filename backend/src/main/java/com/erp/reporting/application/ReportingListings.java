package com.erp.reporting.application;

import static com.erp.platform.web.paging.FilterOperator.EQ;
import static com.erp.platform.web.paging.FilterOperator.IN;

import com.erp.platform.web.paging.ListDefinition;
import com.erp.platform.web.paging.SortOrder;
import com.erp.platform.web.paging.ValueType;
import java.util.Set;

/** List contracts of the Reporting endpoints (API.md §8, §17.11). */
public final class ReportingListings {

    public static final ListDefinition SAVED_REPORTS = ListDefinition.builder("reporting.saved_reports")
            .sortable("name", "createdAt")
            .defaultSort(SortOrder.asc("name"))
            .filter("reportCode", ValueType.STRING, EQ, IN)
            .filter("isShared", ValueType.BOOLEAN, EQ)
            .searchable()
            .build();

    public static final ListDefinition EXPORT_JOBS = ListDefinition.builder("reporting.export_jobs")
            .sortable("requestedAt")
            .defaultSort(SortOrder.desc("requestedAt"))
            .enumFilter("status", Set.of("QUEUED", "RUNNING", "SUCCEEDED", "FAILED", "EXPIRED"), EQ, IN)
            .filter("reportCode", ValueType.STRING, EQ, IN)
            .build();

    private ReportingListings() {}
}
