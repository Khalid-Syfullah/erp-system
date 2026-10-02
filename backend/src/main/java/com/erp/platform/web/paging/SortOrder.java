package com.erp.platform.web.paging;

/** One sort key of a list query; {@code -field} in the query string means descending. */
public record SortOrder(String field, boolean descending) {

    public static SortOrder asc(String field) {
        return new SortOrder(field, false);
    }

    public static SortOrder desc(String field) {
        return new SortOrder(field, true);
    }

    String canonical() {
        return (descending ? "-" : "") + field;
    }
}
