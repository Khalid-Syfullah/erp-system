package com.erp.platform.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.erp.support.TestDatabase;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Applies every migration to a brand-new database exactly as the production migration step does
 * (bootstrap → migrator role with SET ROLE erp_owner), then verifies idempotency.
 */
class MigrationsOnCleanDatabaseIntegrationTest {

    private final String database =
            "erp_clean_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

    @BeforeEach
    void createDatabase() throws SQLException {
        try (Connection c = TestDatabase.superuserConnection(TestDatabase.DATABASE);
                Statement s = c.createStatement()) {
            s.execute("CREATE DATABASE " + database);
        }
        TestDatabase.bootstrap(TestDatabase.jdbcUrl(database));
    }

    @AfterEach
    void dropDatabase() throws SQLException {
        try (Connection c = TestDatabase.superuserConnection(TestDatabase.DATABASE);
                Statement s = c.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
        }
    }

    @Test
    void migratesCleanDatabaseAndIsIdempotent() throws SQLException {
        Flyway flyway = flyway();

        MigrateResult first = flyway.migrate();
        assertThat(first.success).isTrue();
        assertThat(first.migrationsExecuted).isGreaterThanOrEqualTo(4);
        for (MigrationInfo info : flyway.info().all()) {
            assertThat(info.getState()).as(info.getScript()).isEqualTo(MigrationState.SUCCESS);
        }

        flyway.validate();
        assertThat(flyway.migrate().migrationsExecuted).isZero();

        try (Connection c = TestDatabase.superuserConnection(database);
                Statement s = c.createStatement()) {
            var rs =
                    s.executeQuery("SELECT (SELECT count(*) FROM org.currencies), (SELECT count(*) FROM org.countries),"
                            + " (SELECT minor_units FROM org.currencies WHERE code = 'JPY'),"
                            + " (SELECT minor_units FROM org.currencies WHERE code = 'KWD')");
            rs.next();
            assertThat(rs.getInt(1)).isGreaterThan(140);
            assertThat(rs.getInt(2)).isGreaterThan(240);
            assertThat(rs.getInt(3)).isZero();
            assertThat(rs.getInt(4)).isEqualTo(3);
        }
    }

    private Flyway flyway() {
        return Flyway.configure()
                .dataSource(TestDatabase.jdbcUrl(database), "erp_migrator", TestDatabase.MIGRATOR_PASSWORD)
                .initSql("SET ROLE erp_owner")
                .schemas("platform")
                .defaultSchema("platform")
                .table("flyway_schema_history")
                .placeholderReplacement(false)
                .validateMigrationNaming(true)
                .cleanDisabled(true)
                .locations("classpath:db/migration")
                .load();
    }
}
