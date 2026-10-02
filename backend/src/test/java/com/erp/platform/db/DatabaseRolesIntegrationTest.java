package com.erp.platform.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.support.IntegrationTest;
import com.erp.support.TestDatabase;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The privilege model of DATABASE.md §2.7 holds on the migrated schema. */
class DatabaseRolesIntegrationTest extends IntegrationTest {

    @Test
    void runtimeRolesCannotEscalate() throws SQLException {
        try (Connection c = TestDatabase.superuserConnection(TestDatabase.DATABASE);
                Statement s = c.createStatement()) {
            ResultSet rs = s.executeQuery("SELECT rolname, rolsuper, rolbypassrls, rolcreaterole, rolcreatedb "
                    + "FROM pg_roles WHERE rolname LIKE 'erp\\_%' ORDER BY rolname");
            List<String> roles = new ArrayList<>();
            while (rs.next()) {
                roles.add(rs.getString(1));
                assertThat(rs.getBoolean(2)).as("superuser " + rs.getString(1)).isFalse();
                assertThat(rs.getBoolean(3)).as("bypassrls " + rs.getString(1)).isFalse();
                assertThat(rs.getBoolean(4)).as("createrole " + rs.getString(1)).isFalse();
                assertThat(rs.getBoolean(5)).as("createdb " + rs.getString(1)).isFalse();
            }
            assertThat(roles).containsExactly("erp_app", "erp_migrator", "erp_owner", "erp_reporting", "erp_support");
        }
    }

    @Test
    void allObjectsAreOwnedByErpOwnerAndExtensionsLiveInPublic() throws SQLException {
        try (Connection c = TestDatabase.superuserConnection(TestDatabase.DATABASE);
                Statement s = c.createStatement()) {
            ResultSet tables = s.executeQuery("SELECT n.nspname || '.' || c.relname, pg_get_userbyid(c.relowner) "
                    + "FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace "
                    + "WHERE n.nspname IN ('platform', 'org') AND c.relkind IN ('r', 'p', 'v', 'S')");
            int count = 0;
            while (tables.next()) {
                count++;
                assertThat(tables.getString(2)).as(tables.getString(1)).isEqualTo("erp_owner");
            }
            assertThat(count).isGreaterThanOrEqualTo(5);

            ResultSet extensions = s.executeQuery("SELECT e.extname, n.nspname FROM pg_extension e "
                    + "JOIN pg_namespace n ON n.oid = e.extnamespace WHERE e.extname <> 'plpgsql'");
            List<String> names = new ArrayList<>();
            while (extensions.next()) {
                names.add(extensions.getString(1));
                assertThat(extensions.getString(2)).as(extensions.getString(1)).isEqualTo("public");
            }
            assertThat(names).contains("citext", "btree_gist", "pgcrypto", "ltree", "pg_trgm");
        }
    }

    @Test
    void applicationRoleHasDmlOnlyWhereIntended() throws SQLException {
        try (Connection c = TestDatabase.superuserConnection(TestDatabase.DATABASE);
                Statement s = c.createStatement()) {
            ResultSet rs = s.executeQuery("SELECT "
                    + "has_schema_privilege('erp_app', 'org', 'CREATE'),"
                    + "has_schema_privilege('erp_app', 'platform', 'CREATE'),"
                    + "has_table_privilege('erp_app', 'platform.flyway_schema_history', 'SELECT'),"
                    + "has_table_privilege('erp_app', 'org.currencies', 'SELECT'),"
                    + "has_table_privilege('erp_app', 'org.currencies', 'INSERT'),"
                    + "has_table_privilege('erp_app', 'org.branches', 'INSERT'),"
                    + "has_table_privilege('erp_app', 'org.branches', 'TRUNCATE'),"
                    + "has_function_privilege('erp_app', 'platform.setup_module_schema(name)', 'EXECUTE'),"
                    + "has_function_privilege('erp_app', 'platform.current_company_id()', 'EXECUTE')");
            rs.next();
            assertThat(rs.getBoolean(1)).as("CREATE on org").isFalse();
            assertThat(rs.getBoolean(2)).as("CREATE on platform").isFalse();
            assertThat(rs.getBoolean(3)).as("read migration history").isFalse();
            assertThat(rs.getBoolean(4)).as("read currencies").isTrue();
            assertThat(rs.getBoolean(5)).as("write reference data").isFalse();
            assertThat(rs.getBoolean(6)).as("insert branches").isTrue();
            assertThat(rs.getBoolean(7)).as("truncate branches").isFalse();
            assertThat(rs.getBoolean(8)).as("run schema helper").isFalse();
            assertThat(rs.getBoolean(9)).as("read context").isTrue();
        }
    }

    @Test
    void applicationRoleCannotRunDdl() throws SQLException {
        try (Connection c = TestDatabase.connectAs("erp_app", TestDatabase.APP_PASSWORD);
                Statement s = c.createStatement()) {
            assertThatThrownBy(() -> s.execute("CREATE TABLE org.intruder (id int)"))
                    .isInstanceOf(SQLException.class)
                    .extracting(e -> ((SQLException) e).getSQLState())
                    .isEqualTo("42501");
            assertThatThrownBy(() -> s.execute("SET ROLE erp_owner")).isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> s.execute("ALTER TABLE org.branches DISABLE ROW LEVEL SECURITY"))
                    .isInstanceOf(SQLException.class);
        }
    }
}
