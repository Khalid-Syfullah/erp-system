package com.erp.reporting.domain;

import java.util.List;
import java.util.Optional;

/**
 * A catalogued report (PRODUCT_SPEC.md §13): its parameters, columns, default order and the
 * permissions a caller needs (all of them).
 *
 * @param keys the columns that identify a row; appended to every sort as the keyset tiebreaker
 */
public record ReportDefinition(
        String code,
        String name,
        String module,
        String description,
        List<String> permissions,
        ReportSourceKind source,
        List<ReportParameter> parameters,
        List<ReportColumn> columns,
        List<ReportSort> defaultSort,
        List<String> keys) {

    public ReportDefinition {
        permissions = List.copyOf(permissions);
        parameters = List.copyOf(parameters);
        columns = List.copyOf(columns);
        defaultSort = List.copyOf(defaultSort);
        keys = List.copyOf(keys);
        for (String key : keys) {
            if (columns.stream().noneMatch(c -> c.key().equals(key))) {
                throw new IllegalArgumentException(code + ": unknown key column " + key);
            }
        }
        for (ReportSort sort : defaultSort) {
            if (columns.stream().noneMatch(c -> c.key().equals(sort.key()))) {
                throw new IllegalArgumentException(code + ": unknown sort column " + sort.key());
            }
        }
    }

    public Optional<ReportColumn> column(String key) {
        return columns.stream().filter(c -> c.key().equals(key)).findFirst();
    }

    public Optional<ReportParameter> parameter(String name) {
        return parameters.stream().filter(p -> p.name().equals(name)).findFirst();
    }

    public boolean hasTotals() {
        return columns.stream().anyMatch(ReportColumn::total);
    }
}
