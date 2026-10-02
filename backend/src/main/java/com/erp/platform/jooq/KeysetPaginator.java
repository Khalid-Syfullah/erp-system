package com.erp.platform.jooq;

import com.erp.platform.web.paging.CursorCodec;
import com.erp.platform.web.paging.FilterCriterion;
import com.erp.platform.web.paging.ListQuery;
import com.erp.platform.web.paging.PageResponse;
import com.erp.platform.web.paging.SortOrder;
import java.util.ArrayList;
import java.util.List;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.RecordMapper;
import org.jooq.SortField;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Executes list queries with keyset ("seek") pagination (API.md §8.1): stable under concurrent
 * inserts and free of deep OFFSET scans. Fetches {@code limit + 1} rows to know whether another page
 * exists, and encodes the sort-key values of the last row into the next cursor.
 */
@Component
public class KeysetPaginator {

    private final CursorCodec cursorCodec;

    public KeysetPaginator(CursorCodec cursorCodec) {
        this.cursorCodec = cursorCodec;
    }

    /**
     * @param scope mandatory conditions applied before user filters (e.g. branch scope); use
     *     {@link DSL#noCondition()} when there are none. Company isolation is enforced by RLS and by
     *     callers adding an explicit company condition here.
     */
    public <R extends Record, T> PageResponse<T> fetch(
            DSLContext dsl,
            Table<R> table,
            Condition scope,
            ListQuery query,
            ListBinding binding,
            RecordMapper<? super R, T> mapper) {
        List<Condition> conditions = new ArrayList<>();
        conditions.add(scope);
        for (FilterCriterion criterion : query.filters()) {
            conditions.add(binding.condition(criterion));
        }
        if (query.search() != null) {
            conditions.add(binding.searchCondition(query.search()));
        }

        List<Field<?>> keyFields = new ArrayList<>();
        List<Boolean> descending = new ArrayList<>();
        for (SortOrder order : query.sort()) {
            keyFields.add(binding.field(order.field()));
            descending.add(order.descending());
        }
        keyFields.add(binding.tiebreaker());
        descending.add(false);

        List<SortField<?>> orderBy = new ArrayList<>();
        for (int i = 0; i < keyFields.size(); i++) {
            orderBy.add(
                    descending.get(i)
                            ? keyFields.get(i).desc()
                            : keyFields.get(i).asc());
        }

        Condition seek = query.after() == null ? DSL.noCondition() : seek(keyFields, descending, query.after());
        List<R> rows = dsl.selectFrom(table)
                .where(DSL.and(conditions))
                .and(seek)
                .orderBy(orderBy)
                .limit(query.limit() + 1)
                .fetch();

        boolean hasMore = rows.size() > query.limit();
        List<R> page = hasMore ? rows.subList(0, query.limit()) : rows;
        String nextCursor =
                hasMore ? cursorCodec.encode(query.fingerprint(), keyValues(page.getLast(), keyFields)) : null;
        Long total = query.includeTotal()
                ? (long) dsl.fetchCount(dsl.selectFrom(table).where(DSL.and(conditions)))
                : null;

        List<T> data = page.stream().map(mapper::map).toList();
        return new PageResponse<>(
                data, new PageResponse.Page(query.limit(), nextCursor, hasMore), new PageResponse.Meta(total));
    }

    /**
     * {@code (k1 > v1) OR (k1 = v1 AND k2 > v2) OR …} with {@code <} for descending keys. Mixed sort
     * directions rule out a single row-value comparison.
     */
    @SuppressWarnings("unchecked")
    static Condition seek(List<Field<?>> keys, List<Boolean> descending, List<@Nullable String> after) {
        List<Condition> alternatives = new ArrayList<>();
        for (int i = 0; i < keys.size(); i++) {
            List<Condition> conjunction = new ArrayList<>();
            for (int j = 0; j < i; j++) {
                Field<Object> previous = (Field<Object>) keys.get(j);
                conjunction.add(previous.eq(DSL.val(after.get(j), previous)));
            }
            Field<Object> key = (Field<Object>) keys.get(i);
            Field<Object> value = DSL.val(after.get(i), key);
            conjunction.add(descending.get(i) ? key.lt(value) : key.gt(value));
            alternatives.add(DSL.and(conjunction));
        }
        return DSL.or(alternatives);
    }

    private static List<@Nullable String> keyValues(Record row, List<Field<?>> keyFields) {
        List<String> values = new ArrayList<>(keyFields.size());
        for (Field<?> field : keyFields) {
            Object value = row.get(field);
            values.add(value == null ? null : value.toString());
        }
        return values;
    }
}
