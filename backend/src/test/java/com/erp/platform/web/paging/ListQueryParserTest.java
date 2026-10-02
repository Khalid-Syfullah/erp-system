package com.erp.platform.web.paging;

import static com.erp.platform.web.paging.FilterOperator.EQ;
import static com.erp.platform.web.paging.FilterOperator.GTE;
import static com.erp.platform.web.paging.FilterOperator.IN;
import static com.erp.platform.web.paging.FilterOperator.IS_NULL;
import static com.erp.platform.web.paging.FilterOperator.LIKE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.erp.platform.web.ApiException;
import com.erp.platform.web.FieldViolation;
import com.erp.platform.web.PlatformErrorCode;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

class ListQueryParserTest {

    private static final ListDefinition ORDERS = ListDefinition.builder("test.orders")
            .sortable("orderDate", "number", "total")
            .defaultSort(SortOrder.desc("orderDate"))
            .filter("number", ValueType.STRING, EQ, LIKE)
            .filter("customerId", ValueType.UUID, EQ, IN)
            .filter("total", ValueType.DECIMAL, GTE)
            .filter("orderDate", ValueType.DATE, GTE, IS_NULL)
            .filter("lineCount", ValueType.INTEGER, EQ)
            .filter("archived", ValueType.BOOLEAN, EQ)
            .enumFilter("status", Set.of("DRAFT", "CONFIRMED"), EQ, IN)
            .searchable()
            .build();

    private final CursorCodec codec = new CursorCodec(CursorCodecTest.randomKey());
    private final ListQueryParser parser = new ListQueryParser(codec);

    @Test
    void appliesDefaults() {
        ListQuery query = parser.parse(params(), ORDERS);

        assertThat(query.limit()).isEqualTo(25);
        assertThat(query.sort()).containsExactly(SortOrder.desc("orderDate"));
        assertThat(query.filters()).isEmpty();
        assertThat(query.search()).isNull();
        assertThat(query.after()).isNull();
        assertThat(query.includeTotal()).isFalse();
    }

    @Test
    void parsesTypedFiltersSortSearchAndLimit() {
        UUID customer = UUID.randomUUID();
        ListQuery query = parser.parse(
                params(
                        "limit", "50",
                        "sort", "-total,number",
                        "q", "  acme ",
                        "includeTotal", "true",
                        "filter[customerId][in]", customer + "," + UUID.randomUUID(),
                        "filter[total][gte]", "100.50",
                        "filter[orderDate][isNull]", "false",
                        "filter[lineCount]", "3",
                        "filter[archived]", "false",
                        "filter[status][in]", "DRAFT,CONFIRMED"),
                ORDERS);

        assertThat(query.limit()).isEqualTo(50);
        assertThat(query.sort()).containsExactly(SortOrder.desc("total"), SortOrder.asc("number"));
        assertThat(query.search()).isEqualTo("acme");
        assertThat(query.includeTotal()).isTrue();
        assertThat(query.filters())
                .extracting(FilterCriterion::field, FilterCriterion::operator)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("customerId", IN),
                        org.assertj.core.groups.Tuple.tuple("total", GTE),
                        org.assertj.core.groups.Tuple.tuple("orderDate", IS_NULL),
                        org.assertj.core.groups.Tuple.tuple("lineCount", EQ),
                        org.assertj.core.groups.Tuple.tuple("archived", EQ),
                        org.assertj.core.groups.Tuple.tuple("status", IN));
        assertThat(filter(query, "customerId").values()).first().isEqualTo(customer);
        assertThat(filter(query, "total").value()).isEqualTo(new BigDecimal("100.50"));
        assertThat(filter(query, "lineCount").value()).isEqualTo(3L);
        assertThat(filter(query, "archived").value()).isEqualTo(false);
    }

    @Test
    void reportsEveryProblemAtOnce() {
        ApiException ex = catchThrowableOfType(
                ApiException.class,
                () -> parser.parse(
                        params(
                                "limit", "500",
                                "sort", "password",
                                "filter[secret]", "x",
                                "filter[total][like]", "1",
                                "filter[total][gte]", "1e5",
                                "filter[orderDate][gte]", "2026-13-01",
                                "filter[status]", "SHIPPED",
                                "q", "a",
                                "foo", "bar"),
                        ORDERS));

        assertThat(ex.errorCode()).isEqualTo(PlatformErrorCode.BAD_REQUEST);
        assertThat(ex.violations())
                .extracting(FieldViolation::parameter, FieldViolation::code)
                .contains(
                        org.assertj.core.groups.Tuple.tuple("limit", "OUT_OF_RANGE"),
                        org.assertj.core.groups.Tuple.tuple("sort", "UNSUPPORTED_SORT"),
                        org.assertj.core.groups.Tuple.tuple("filter[secret]", "UNSUPPORTED_FILTER"),
                        org.assertj.core.groups.Tuple.tuple("filter[total][like]", "UNSUPPORTED_OPERATOR"),
                        org.assertj.core.groups.Tuple.tuple("filter[total][gte]", "INVALID_VALUE"),
                        org.assertj.core.groups.Tuple.tuple("filter[orderDate][gte]", "INVALID_VALUE"),
                        org.assertj.core.groups.Tuple.tuple("filter[status]", "INVALID_VALUE"),
                        org.assertj.core.groups.Tuple.tuple("q", "INVALID_VALUE"),
                        org.assertj.core.groups.Tuple.tuple("foo", "UNKNOWN_PARAMETER"));
    }

    @Test
    void rejectsRepeatedParameters() {
        MultiValueMap<String, String> params = params("limit", "10");
        params.add("limit", "20");

        ApiException ex = catchThrowableOfType(ApiException.class, () -> parser.parse(params, ORDERS));

        assertThat(ex.violations()).extracting(FieldViolation::code).containsExactly("DUPLICATE_PARAMETER");
    }

    @Test
    void acceptsCursorIssuedForTheSameQuery() {
        ListQuery first = parser.parse(params("filter[number]", "SO-1"), ORDERS);
        String cursor = codec.encode(first.fingerprint(), java.util.Arrays.asList("2026-10-01", "0190"));

        ListQuery next = parser.parse(params("filter[number]", "SO-1", "cursor", cursor, "limit", "5"), ORDERS);

        assertThat(next.after()).containsExactly("2026-10-01", "0190");
        assertThat(next.limit()).isEqualTo(5);
    }

    @Test
    void rejectsCursorFromADifferentQueryOrForgery() {
        ListQuery first = parser.parse(params("filter[number]", "SO-1"), ORDERS);
        String cursor = codec.encode(first.fingerprint(), java.util.Arrays.asList("2026-10-01", "0190"));

        for (MultiValueMap<String, String> attempt : List.of(
                params("filter[number]", "SO-2", "cursor", cursor),
                params("filter[number]", "SO-1", "sort", "number", "cursor", cursor),
                params("filter[number]", "SO-1", "cursor", cursor + "x"))) {
            ApiException ex = catchThrowableOfType(ApiException.class, () -> parser.parse(attempt, ORDERS));
            assertThat(ex.violations()).extracting(FieldViolation::code).containsExactly("INVALID_CURSOR");
        }
    }

    @Test
    void fingerprintIgnoresFilterOrderButNotValues() {
        ListQuery a = parser.parse(params("filter[number]", "X", "filter[lineCount]", "2"), ORDERS);
        ListQuery b = parser.parse(params("filter[lineCount]", "2", "filter[number]", "X"), ORDERS);
        ListQuery c = parser.parse(params("filter[lineCount]", "3", "filter[number]", "X"), ORDERS);

        assertThat(a.fingerprint()).isEqualTo(b.fingerprint()).isNotEqualTo(c.fingerprint());
    }

    private static FilterCriterion filter(ListQuery query, String field) {
        return query.filters().stream()
                .filter(f -> f.field().equals(field))
                .findFirst()
                .orElseThrow();
    }

    private static MultiValueMap<String, String> params(String... pairs) {
        MultiValueMap<String, String> map = new LinkedMultiValueMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.add(pairs[i], pairs[i + 1]);
        }
        return map;
    }
}
