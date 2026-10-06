package com.erp.reporting.domain;

/** Where a report's rows come from (ADR-040). */
public enum ReportSourceKind {
    /** A set-based query over the modules' {@code v_rpt_*} views, keyset-paginated and streamed. */
    VIEWS,
    /** A report computed by its owning module (Accounting's financial statements), bounded in size. */
    MODULE
}
