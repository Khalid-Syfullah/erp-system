package com.erp.reporting.persistence;

import com.erp.reporting.application.ReportPage;
import com.erp.reporting.application.ReportScope;
import com.erp.reporting.application.RowSink;
import com.erp.reporting.domain.ReportDefinition;
import com.erp.reporting.domain.ReportParameters;
import com.erp.reporting.domain.ReportSort;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Select;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/** The view-backed reports by code: builds a report's query and pages or streams it. */
@Component
public class ViewReports {

    private final Map<String, BiFunction<ReportParameters, ReportScope, Select<? extends Record>>> queries =
            new HashMap<>();
    private final ReportQueryExecutor executor;

    ViewReports(List<ViewQueries> modules, ReportQueryExecutor executor) {
        this.executor = executor;
        for (ViewQueries module : modules) {
            module.queries().forEach((code, query) -> {
                if (queries.put(code, query) != null) {
                    throw new IllegalStateException("Two queries for report " + code);
                }
            });
        }
    }

    public Set<String> codes() {
        return Set.copyOf(queries.keySet());
    }

    public ReportPage page(
            ReportDefinition definition,
            ReportParameters parameters,
            ReportScope scope,
            List<ReportSort> sort,
            @Nullable List<@Nullable String> after,
            int limit,
            boolean withTotals,
            Duration timeout) {
        return executor.page(definition, query(definition, parameters, scope), sort, after, limit, withTotals, timeout);
    }

    public long stream(
            ReportDefinition definition,
            ReportParameters parameters,
            ReportScope scope,
            List<ReportSort> sort,
            long maxRows,
            Duration timeout,
            RowSink sink) {
        return executor.stream(definition, query(definition, parameters, scope), sort, maxRows, timeout, sink);
    }

    /** The column names the query produces (for the catalogue consistency test). */
    public List<String> columnNames(ReportDefinition definition, ReportParameters parameters, ReportScope scope) {
        return Arrays.stream(query(definition, parameters, scope).fields())
                .map(Field::getName)
                .toList();
    }

    private Select<? extends Record> query(
            ReportDefinition definition, ReportParameters parameters, ReportScope scope) {
        var query = queries.get(definition.code());
        if (query == null) {
            throw new IllegalStateException("No query for report " + definition.code());
        }
        return query.apply(parameters, scope);
    }
}
