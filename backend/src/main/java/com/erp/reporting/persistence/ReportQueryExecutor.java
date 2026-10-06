package com.erp.reporting.persistence;

import com.erp.platform.jooq.KeysetPaginator;
import com.erp.reporting.application.ReportPage;
import com.erp.reporting.application.RowSink;
import com.erp.reporting.domain.ColumnType;
import com.erp.reporting.domain.ReportColumn;
import com.erp.reporting.domain.ReportDefinition;
import com.erp.reporting.domain.ReportSort;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jooq.Condition;
import org.jooq.Cursor;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Select;
import org.jooq.SortField;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Runs a report query on the reporting database as a derived table {@code r}: keyset pages (sort keys
 * plus the report's key columns as tiebreaker), the totals of the numeric columns in the same
 * snapshot, and streamed exports with a server-side cursor. Nothing is loaded beyond one page or one
 * fetch batch.
 */
@Component
public class ReportQueryExecutor {

    static final int FETCH_SIZE = 1000;

    private final ReportingDatabase database;

    ReportQueryExecutor(ReportingDatabase database) {
        this.database = database;
    }

    public ReportPage page(
            ReportDefinition definition,
            Select<? extends Record> query,
            List<ReportSort> sort,
            @Nullable List<@Nullable String> after,
            int limit,
            boolean withTotals,
            Duration timeout) {
        return database.read(timeout, dsl -> {
            Table<?> r = query.asTable("r");
            Order order = order(definition, r, sort);
            Condition seek =
                    after == null ? DSL.noCondition() : KeysetPaginator.seek(order.keys, order.descending, after);
            List<Field<?>> fields = fields(definition, r);
            List<? extends Record> rows = dsl.select(fields)
                    .from(r)
                    .where(seek)
                    .orderBy(order.orderBy)
                    .limit(limit + 1)
                    .fetch();
            boolean hasMore = rows.size() > limit;
            List<? extends Record> page = hasMore ? rows.subList(0, limit) : rows;
            List<Map<String, @Nullable Object>> data = new ArrayList<>(page.size());
            for (Record record : page) {
                Map<String, @Nullable Object> row = new LinkedHashMap<>();
                for (ReportColumn column : definition.columns()) {
                    row.put(column.key(), record.get(column.key()));
                }
                data.add(row);
            }
            List<@Nullable String> lastKey = null;
            if (hasMore) {
                lastKey = new ArrayList<>();
                Record last = page.getLast();
                for (Field<?> key : order.keys) {
                    Object value = last.get(key.getName());
                    lastKey.add(value == null ? null : value.toString());
                }
            }
            Map<String, Object> totals = null;
            if (withTotals && definition.hasTotals()) {
                List<Field<?>> sums = new ArrayList<>();
                for (ReportColumn column : definition.columns()) {
                    if (column.total()) {
                        sums.add(DSL.coalesce(DSL.sum(r.field(column.key(), BigDecimal.class)), BigDecimal.ZERO)
                                .as(column.key()));
                    }
                }
                Record sum = dsl.select(sums).from(r).fetchSingle();
                totals = new LinkedHashMap<>();
                for (ReportColumn column : definition.columns()) {
                    if (column.total()) {
                        BigDecimal value = sum.get(column.key(), BigDecimal.class);
                        totals.put(column.key(), column.type() == ColumnType.INTEGER ? value.longValueExact() : value);
                    }
                }
            }
            return new ReportPage(data, totals, lastKey, hasMore);
        });
    }

    /**
     * Streams the rows in report order to {@code sink}; stops after {@code maxRows + 1} rows and
     * returns the number of rows read (more than {@code maxRows} means the limit was exceeded).
     */
    public long stream(
            ReportDefinition definition,
            Select<? extends Record> query,
            List<ReportSort> sort,
            long maxRows,
            Duration timeout,
            RowSink sink) {
        return database.read(timeout, dsl -> {
            Table<?> r = query.asTable("r");
            Order order = order(definition, r, sort);
            List<Field<?>> fields = fields(definition, r);
            long count = 0;
            try (Cursor<? extends Record> cursor = dsl.select(fields)
                    .from(r)
                    .orderBy(order.orderBy)
                    .limit(maxRows + 1)
                    .fetchSize(FETCH_SIZE)
                    .fetchLazy()) {
                for (Record record : cursor) {
                    count++;
                    if (count > maxRows) {
                        break;
                    }
                    Object[] values = new Object[fields.size()];
                    for (int i = 0; i < values.length; i++) {
                        values[i] = record.get(i);
                    }
                    sink.row(values);
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return count;
        });
    }

    private static List<Field<?>> fields(ReportDefinition definition, Table<?> r) {
        List<Field<?>> fields = new ArrayList<>();
        for (ReportColumn column : definition.columns()) {
            Field<?> field = r.field(column.key());
            if (field == null) {
                throw new IllegalStateException(definition.code() + ": the query has no column " + column.key());
            }
            fields.add(field);
        }
        return fields;
    }

    private static Order order(ReportDefinition definition, Table<?> r, List<ReportSort> sort) {
        List<Field<?>> keys = new ArrayList<>();
        List<Boolean> descending = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (ReportSort s : sort) {
            keys.add(r.field(s.key()));
            descending.add(s.descending());
            names.add(s.key());
        }
        for (String key : definition.keys()) {
            if (!names.contains(key)) {
                keys.add(r.field(key));
                descending.add(false);
                names.add(key);
            }
        }
        List<SortField<?>> orderBy = new ArrayList<>();
        for (int i = 0; i < keys.size(); i++) {
            orderBy.add(descending.get(i) ? keys.get(i).desc() : keys.get(i).asc());
        }
        return new Order(keys, descending, orderBy);
    }

    private record Order(List<Field<?>> keys, List<Boolean> descending, List<SortField<?>> orderBy) {}
}
