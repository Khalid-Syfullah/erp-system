package com.erp.platform.web.paging;

/** Value types accepted in list filters; values are parsed strictly. */
public enum ValueType {
    STRING,
    UUID,
    INTEGER,
    DECIMAL,
    BOOLEAN,
    DATE,
    ENUM
}
