package com.erp.reporting.domain;

/** The value type of a report column; decides JSON, CSV, XLSX and PDF formatting. */
public enum ColumnType {
    TEXT,
    ID,
    DATE,
    INTEGER,
    /** Money in base currency (or the document currency where the column says so), scale 4. */
    AMOUNT,
    /** Quantity in the base unit, scale 6. */
    QUANTITY,
    /** A ratio in percent, scale 2. */
    PERCENT,
    /** Other decimals (days, FTE), scale 2 or as stored. */
    DECIMAL,
    BOOLEAN;

    public boolean numeric() {
        return this == INTEGER || this == AMOUNT || this == QUANTITY || this == PERCENT || this == DECIMAL;
    }
}
