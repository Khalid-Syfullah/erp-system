package com.erp.reporting.persistence;

import com.erp.reporting.application.ReportScope;
import com.erp.reporting.domain.ReportParameters;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;
import org.jooq.Condition;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Select;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.jspecify.annotations.Nullable;

/**
 * The queries of one module's reports over its {@code v_rpt_*} views. Every query is set-based,
 * names its output columns after the report's column keys, filters the company explicitly (besides
 * RLS) and applies the branch scope where the data is branch-scoped (SECURITY.md §4.4).
 */
interface ViewQueries {

    /** Report code → query builder. */
    Map<String, BiFunction<ReportParameters, ReportScope, Select<? extends Record>>> queries();

    // ------------------------------------------------------------------------------ helpers

    static Condition inRange(Field<LocalDate> field, ReportParameters p) {
        LocalDate from = p.date("from");
        LocalDate to = p.date("to");
        return (from == null ? DSL.noCondition() : field.ge(from)).and(to == null ? DSL.noCondition() : field.le(to));
    }

    static Condition eq(Field<UUID> field, @Nullable UUID value) {
        return value == null ? DSL.noCondition() : field.eq(value);
    }

    static Condition eq(Field<String> field, @Nullable String value) {
        return value == null ? DSL.noCondition() : field.eq(value);
    }

    /** Rows of the caller's branches only when the scope is restricted (rows without a branch are hidden then). */
    static Condition branchScope(ReportScope scope, Field<UUID> branch) {
        return scope.branches() == null ? DSL.noCondition() : branch.in(scope.branches());
    }

    static Field<BigDecimal> sum(Field<BigDecimal> field) {
        return DSL.coalesce(DSL.sum(field), BigDecimal.ZERO);
    }

    static Field<BigDecimal> sumIf(Field<BigDecimal> field, Condition condition) {
        return DSL.coalesce(DSL.sum(field).filterWhere(condition), BigDecimal.ZERO);
    }

    static Field<Integer> countIf(Condition condition) {
        return DSL.count().filterWhere(condition);
    }

    /** {@code part / whole × 100} rounded to 2 decimals; null when {@code whole} is zero. */
    static Field<BigDecimal> percent(Field<BigDecimal> part, Field<BigDecimal> whole) {
        return DSL.when(whole.ne(BigDecimal.ZERO), DSL.round(part.mul(100).div(whole), 2))
                .else_(DSL.castNull(SQLDataType.NUMERIC));
    }

    /** {@code yyyy-MM} of a date, the code of a month group. */
    static Field<String> month(Field<LocalDate> date) {
        return DSL.field("to_char({0}, 'YYYY-MM')", SQLDataType.VARCHAR, date);
    }

    static Field<Integer> daysBetween(Field<LocalDate> later, Field<LocalDate> earlier) {
        return DSL.field("({0} - {1})", SQLDataType.INTEGER, later, earlier);
    }

    static Field<UUID> nullId() {
        return DSL.castNull(SQLDataType.UUID);
    }
}
