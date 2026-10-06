package com.erp.reporting.domain;

/** One sort key of a report: a column key and its direction. */
public record ReportSort(String key, boolean descending) {

    public static ReportSort asc(String key) {
        return new ReportSort(key, false);
    }

    public static ReportSort desc(String key) {
        return new ReportSort(key, true);
    }

    public String canonical() {
        return (descending ? "-" : "") + key;
    }
}
