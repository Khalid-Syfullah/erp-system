package com.erp.reporting.application;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * One page of report rows (column key → value) with, on the first page, the totals of the numeric
 * columns over all rows. {@code lastKey} holds the sort-key values of the last row for the cursor.
 */
public record ReportPage(
        List<Map<String, @Nullable Object>> rows,
        @Nullable Map<String, Object> totals,
        @Nullable List<@Nullable String> lastKey,
        boolean hasMore) {}
