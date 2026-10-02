package com.erp.platform.web.paging;

import java.util.List;

/**
 * A parsed, typed filter. {@code values} holds one value except for {@link FilterOperator#IN}.
 * Value classes: String, java.util.UUID, Long, java.math.BigDecimal, Boolean, java.time.LocalDate.
 */
public record FilterCriterion(String field, FilterOperator operator, List<Object> values) {

    public FilterCriterion {
        values = List.copyOf(values);
    }

    public Object value() {
        return values.getFirst();
    }

    String canonical() {
        return field + ":" + operator.token() + "=" + values;
    }
}
