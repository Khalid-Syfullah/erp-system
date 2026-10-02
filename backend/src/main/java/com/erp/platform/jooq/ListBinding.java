package com.erp.platform.jooq;

import com.erp.platform.web.paging.FilterCriterion;
import com.erp.platform.web.paging.ListDefinition;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jooq.Condition;
import org.jooq.Field;
import org.jooq.impl.DSL;

/**
 * Binds the field names of a {@link ListDefinition} to jOOQ columns. Construction fails fast if the
 * definition names a field that is not bound, if a sortable column is nullable (keyset pagination
 * needs total ordering), or if no unique tiebreaker column is given.
 */
public final class ListBinding {

    private final Map<String, Field<?>> fields;
    private final Field<?> tiebreaker;
    private final List<Field<String>> searchFields;

    private ListBinding(Builder builder) {
        this.fields = Map.copyOf(builder.fields);
        this.tiebreaker = builder.tiebreaker;
        this.searchFields = List.copyOf(builder.searchFields);
        ListDefinition definition = builder.definition;
        if (tiebreaker == null || tiebreaker.getDataType().nullable()) {
            throw new IllegalStateException(definition.name() + ": a NOT NULL unique tiebreaker column is required");
        }
        for (String sortable : definition.sortableFields()) {
            Field<?> field = field(sortable);
            if (field.getDataType().nullable()) {
                throw new IllegalStateException(definition.name() + ": sortable column must be NOT NULL: " + sortable);
            }
        }
        definition.filters().keySet().forEach(this::field);
        if (definition.searchable() && searchFields.isEmpty()) {
            throw new IllegalStateException(definition.name() + ": searchable list needs search columns");
        }
    }

    public static Builder builder(ListDefinition definition) {
        return new Builder(definition);
    }

    Field<?> field(String name) {
        Field<?> field = fields.get(name);
        if (field == null) {
            throw new IllegalStateException("No column bound for list field '" + name + "'");
        }
        return field;
    }

    Field<?> tiebreaker() {
        return tiebreaker;
    }

    @SuppressWarnings("unchecked")
    Condition condition(FilterCriterion criterion) {
        Field<Object> field = (Field<Object>) field(criterion.field());
        Object value = criterion.value();
        return switch (criterion.operator()) {
            case EQ -> field.eq(value);
            case NE -> field.ne(value);
            case GT -> field.gt(value);
            case GTE -> field.ge(value);
            case LT -> field.lt(value);
            case LTE -> field.le(value);
            case IN -> field.in(criterion.values());
            case LIKE -> field.cast(String.class).containsIgnoreCase((String) value);
            case IS_NULL -> Boolean.TRUE.equals(value) ? field.isNull() : field.isNotNull();
        };
    }

    Condition searchCondition(String text) {
        return DSL.or(searchFields.stream().map(f -> f.containsIgnoreCase(text)).toList());
    }

    public static final class Builder {
        private final ListDefinition definition;
        private final Map<String, Field<?>> fields = new LinkedHashMap<>();
        private final List<Field<String>> searchFields = new ArrayList<>();
        private Field<?> tiebreaker;

        private Builder(ListDefinition definition) {
            this.definition = definition;
        }

        public Builder field(String name, Field<?> column) {
            fields.put(name, column);
            return this;
        }

        /** A NOT NULL, unique column appended to every sort so that pagination is deterministic. */
        public Builder tiebreaker(Field<?> column) {
            this.tiebreaker = column;
            return this;
        }

        public Builder search(List<Field<String>> columns) {
            searchFields.addAll(columns);
            return this;
        }

        public ListBinding build() {
            return new ListBinding(this);
        }
    }
}
