package com.erp.platform.web.paging;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The list contract of one endpoint: which fields can be sorted and filtered (and how), whether
 * {@code q} search is offered, and the default sort (API.md §8). Anything not declared here is
 * rejected with 400. It contains no persistence details; the persistence layer binds these names to
 * columns.
 */
public final class ListDefinition {

    public static final int DEFAULT_LIMIT = 25;
    public static final int MAX_LIMIT = 200;

    private final String name;
    private final Set<String> sortableFields;
    private final List<SortOrder> defaultSort;
    private final Map<String, FilterSpec> filters;
    private final boolean searchable;

    private ListDefinition(Builder builder) {
        this.name = builder.name;
        this.sortableFields = Collections.unmodifiableSet(new LinkedHashSet<>(builder.sortableFields));
        this.defaultSort = List.copyOf(builder.defaultSort);
        this.filters = Collections.unmodifiableMap(new LinkedHashMap<>(builder.filters));
        this.searchable = builder.searchable;
        if (defaultSort.isEmpty()) {
            throw new IllegalStateException("List " + name + " needs a default sort");
        }
        for (SortOrder order : defaultSort) {
            if (!sortableFields.contains(order.field())) {
                throw new IllegalStateException("Default sort field is not sortable: " + order.field());
            }
        }
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    public String name() {
        return name;
    }

    public Set<String> sortableFields() {
        return sortableFields;
    }

    public List<SortOrder> defaultSort() {
        return defaultSort;
    }

    public Map<String, FilterSpec> filters() {
        return filters;
    }

    public boolean searchable() {
        return searchable;
    }

    public static final class Builder {
        private final String name;
        private final List<String> sortableFields = new ArrayList<>();
        private final List<SortOrder> defaultSort = new ArrayList<>();
        private final Map<String, FilterSpec> filters = new LinkedHashMap<>();
        private boolean searchable;

        private Builder(String name) {
            this.name = Objects.requireNonNull(name);
        }

        public Builder sortable(String... fields) {
            sortableFields.addAll(List.of(fields));
            return this;
        }

        public Builder defaultSort(SortOrder... orders) {
            defaultSort.addAll(List.of(orders));
            return this;
        }

        public Builder filter(String field, ValueType type, FilterOperator... operators) {
            filters.put(field, new FilterSpec(field, type, EnumSet.copyOf(List.of(operators)), Set.of()));
            return this;
        }

        public Builder enumFilter(String field, Set<String> values, FilterOperator... operators) {
            filters.put(field, new FilterSpec(field, ValueType.ENUM, EnumSet.copyOf(List.of(operators)), values));
            return this;
        }

        public Builder searchable() {
            this.searchable = true;
            return this;
        }

        public ListDefinition build() {
            return new ListDefinition(this);
        }
    }
}
