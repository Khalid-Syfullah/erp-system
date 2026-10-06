package com.erp.reporting.domain;

/** The life cycle of an export job: QUEUED → RUNNING → SUCCEEDED (→ EXPIRED) or FAILED. */
public enum ExportStatus {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    EXPIRED
}
