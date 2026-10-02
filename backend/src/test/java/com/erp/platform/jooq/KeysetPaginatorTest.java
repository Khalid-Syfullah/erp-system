package com.erp.platform.jooq;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.jooq.Condition;
import org.jooq.Field;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.junit.jupiter.api.Test;

class KeysetPaginatorTest {

    private static final Field<String> NAME = DSL.field(DSL.name("name"), SQLDataType.VARCHAR.notNull());
    private static final Field<Short> UNITS = DSL.field(DSL.name("minor_units"), SQLDataType.SMALLINT.notNull());
    private static final Field<String> CODE =
            DSL.field(DSL.name("code"), SQLDataType.CHAR(3).notNull());

    @Test
    void buildsSeekPredicateForMixedDirectionsWithTypedValues() {
        Condition seek = KeysetPaginator.seek(
                List.of(UNITS, NAME, CODE), List.of(true, false, false), List.of("2", "Euro", "EUR"));

        String sql = DSL.using(SQLDialect.POSTGRES).renderInlined(seek);

        assertThat(sql)
                .isEqualTo("(\"minor_units\" < 2 or (\"minor_units\" = 2 and \"name\" > 'Euro') "
                        + "or (\"minor_units\" = 2 and \"name\" = 'Euro' and \"code\" > 'EUR'))");
        assertThat(DSL.using(SQLDialect.POSTGRES).render(seek)).doesNotContain("Euro");
    }
}
