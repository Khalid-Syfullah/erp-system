package com.erp.platform.web.paging;

import java.util.Set;

/** An allowlisted filterable field: its value type, permitted operators and, for enums, values. */
public record FilterSpec(String field, ValueType type, Set<FilterOperator> operators, Set<String> enumValues) {

    public FilterSpec {
        operators = Set.copyOf(operators);
        enumValues = Set.copyOf(enumValues);
        if (operators.contains(FilterOperator.LIKE) && type != ValueType.STRING) {
            throw new IllegalArgumentException("LIKE is only supported for STRING filters: " + field);
        }
        if (type == ValueType.ENUM && enumValues.isEmpty()) {
            throw new IllegalArgumentException("ENUM filter needs its values: " + field);
        }
    }
}
