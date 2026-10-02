package com.erp.platform.web.paging;

import java.util.Arrays;
import java.util.Optional;

/** Filter operators of the list query syntax {@code filter[field][op]=value} (API.md §8.2). */
public enum FilterOperator {
    EQ("eq"),
    NE("ne"),
    GT("gt"),
    GTE("gte"),
    LT("lt"),
    LTE("lte"),
    IN("in"),
    LIKE("like"),
    IS_NULL("isNull");

    private final String token;

    FilterOperator(String token) {
        this.token = token;
    }

    public String token() {
        return token;
    }

    public static Optional<FilterOperator> fromToken(String token) {
        return Arrays.stream(values()).filter(op -> op.token.equals(token)).findFirst();
    }
}
