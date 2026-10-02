package com.erp.platform.db;

import static com.erp.db.org.Tables.BRANCHES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.platform.context.CurrentContext;
import com.erp.platform.context.RequestContext;
import com.erp.support.IntegrationTest;
import com.erp.support.TestCompanies;
import com.erp.support.TestDatabase;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Company isolation through PostgreSQL row-level security (DATABASE.md §3). The catalogue check
 * covers every company-scoped table automatically, including tables added by later phases.
 */
class RowLevelSecurityIntegrationTest extends IntegrationTest {

    /** Tables documented as intentionally not RLS-protected although they carry company_id (DATABASE.md §3). */
    private static final Set<String> EXEMPT = Set.of("auth.role_assignments", "auth.role_assignment_branches");

    @Autowired
    DSLContext dsl;

    @Autowired
    TransactionTemplate tx;

    private UUID companyA;
    private UUID companyB;
    private UUID branchB;

    @BeforeEach
    void setUp() {
        companyA = tx.execute(status -> TestCompanies.create(dsl));
        companyB = tx.execute(status -> TestCompanies.create(dsl));
        inCompany(companyA, () -> insertBranch(companyA, "A1"));
        branchB = inCompany(companyB, () -> insertBranch(companyB, "B1"));
    }

    @Test
    void everyCompanyScopedTableHasForcedCompanyIsolationPolicy() throws SQLException {
        List<String> checked = new ArrayList<>();
        try (Connection c = TestDatabase.superuserConnection(TestDatabase.DATABASE);
                Statement s = c.createStatement()) {
            ResultSet rs = s.executeQuery("""
                    SELECT n.nspname || '.' || c.relname, c.relrowsecurity, c.relforcerowsecurity,
                           EXISTS (SELECT 1 FROM pg_policy p WHERE p.polrelid = c.oid AND p.polname = 'company_isolation')
                    FROM pg_class c
                    JOIN pg_namespace n ON n.oid = c.relnamespace
                    JOIN pg_attribute a ON a.attrelid = c.oid AND a.attname = 'company_id' AND NOT a.attisdropped
                    WHERE c.relkind IN ('r', 'p') AND n.nspowner = 'erp_owner'::regrole
                    """);
            while (rs.next()) {
                String table = rs.getString(1);
                if (EXEMPT.contains(table)) {
                    continue;
                }
                checked.add(table);
                assertThat(rs.getBoolean(2)).as("RLS enabled on " + table).isTrue();
                assertThat(rs.getBoolean(3)).as("RLS forced on " + table).isTrue();
                assertThat(rs.getBoolean(4))
                        .as("company_isolation policy on " + table)
                        .isTrue();
            }
        }
        assertThat(checked).contains("org.branches");
    }

    @Test
    void rowsOfOtherCompaniesAreInvisible() {
        List<String> seenByA = inCompany(
                companyA, () -> dsl.select(BRANCHES.CODE).from(BRANCHES).fetch(BRANCHES.CODE));
        List<String> seenByB = inCompany(
                companyB, () -> dsl.select(BRANCHES.CODE).from(BRANCHES).fetch(BRANCHES.CODE));

        assertThat(seenByA).containsExactly("A1");
        assertThat(seenByB).containsExactly("B1");
    }

    @Test
    void writesToOtherCompaniesAffectNothing() {
        int updated = inCompany(
                companyA,
                () -> dsl.update(BRANCHES)
                        .set(BRANCHES.NAME, "hijacked")
                        .where(BRANCHES.ID.eq(branchB))
                        .execute());
        int deleted = inCompany(
                companyA,
                () -> dsl.deleteFrom(BRANCHES).where(BRANCHES.ID.eq(branchB)).execute());

        assertThat(updated).isZero();
        assertThat(deleted).isZero();
        assertThat(inCompany(companyB, () -> dsl.fetchValue(BRANCHES.NAME, BRANCHES.ID.eq(branchB))))
                .isEqualTo("Branch B1");
    }

    @Test
    void insertingIntoAnotherCompanyIsRejected() {
        assertThatThrownBy(() -> inCompany(companyA, () -> insertBranch(companyB, "SNEAK")))
                .isInstanceOf(DataAccessException.class)
                .hasRootCauseInstanceOf(SQLException.class)
                .rootCause()
                .extracting(e -> ((SQLException) e).getSQLState())
                .isEqualTo("42501");
    }

    @Test
    void withoutCompanyContextNothingIsVisible() {
        Integer visible = tx.execute(status -> dsl.fetchCount(BRANCHES));
        Integer outsideTransaction = dsl.fetchCount(BRANCHES);

        assertThat(visible).isZero();
        assertThat(outsideTransaction).isZero();
    }

    private UUID insertBranch(UUID company, String code) {
        return dsl.insertInto(BRANCHES)
                .set(BRANCHES.COMPANY_ID, company)
                .set(BRANCHES.CODE, code)
                .set(BRANCHES.NAME, "Branch " + code)
                .returning(BRANCHES.ID)
                .fetchOne(BRANCHES.ID);
    }

    private <T> T inCompany(UUID company, Supplier<T> work) {
        return CurrentContext.callWith(
                RequestContext.forRequest("test-" + UUID.randomUUID()).withCompany(company),
                () -> tx.execute(status -> work.get()));
    }
}
