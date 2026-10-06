package com.erp.reporting.domain;

/**
 * A column of a report.
 *
 * @param key the JSON property and query alias (camelCase)
 * @param total whether the report sums the column over all rows (numeric columns only)
 * @param sortable whether {@code sort} may name it (never-null columns only: keyset pagination)
 */
public record ReportColumn(String key, String label, ColumnType type, boolean total, boolean sortable) {

    public ReportColumn {
        if (total && !type.numeric()) {
            throw new IllegalArgumentException("Only numeric columns have totals: " + key);
        }
    }

    public static ReportColumn text(String key, String label) {
        return new ReportColumn(key, label, ColumnType.TEXT, false, false);
    }

    public static ReportColumn id(String key, String label) {
        return new ReportColumn(key, label, ColumnType.ID, false, false);
    }

    public static ReportColumn date(String key, String label) {
        return new ReportColumn(key, label, ColumnType.DATE, false, false);
    }

    public static ReportColumn integer(String key, String label) {
        return new ReportColumn(key, label, ColumnType.INTEGER, true, true);
    }

    public static ReportColumn amount(String key, String label) {
        return new ReportColumn(key, label, ColumnType.AMOUNT, true, true);
    }

    public static ReportColumn quantity(String key, String label) {
        return new ReportColumn(key, label, ColumnType.QUANTITY, true, true);
    }

    public static ReportColumn percent(String key, String label) {
        return new ReportColumn(key, label, ColumnType.PERCENT, false, false);
    }

    public static ReportColumn decimal(String key, String label) {
        return new ReportColumn(key, label, ColumnType.DECIMAL, false, false);
    }

    public static ReportColumn bool(String key, String label) {
        return new ReportColumn(key, label, ColumnType.BOOLEAN, false, false);
    }

    /** The same column without a total (e.g. balances that must not be added up). */
    public ReportColumn noTotal() {
        return new ReportColumn(key, label, type, false, sortable);
    }

    public ReportColumn withTotal() {
        return new ReportColumn(key, label, type, true, sortable);
    }

    public ReportColumn asSortable() {
        return new ReportColumn(key, label, type, total, true);
    }
}
