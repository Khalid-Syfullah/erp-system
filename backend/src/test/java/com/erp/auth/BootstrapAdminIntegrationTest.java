package com.erp.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.ErpApplication;
import com.erp.support.TestDatabase;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.web.context.WebApplicationContext;

/**
 * The one-shot command that creates the first system administrator on a new installation
 * ({@code SPRING_PROFILES_ACTIVE=prod,bootstrap-admin}, DEVELOPMENT_PLAN Phase 3). Runs against its
 * own fresh database because other tests create system administrators.
 */
class BootstrapAdminIntegrationTest {

    private static final String EMAIL = "First.Admin@Example.test";
    private static final String PASSWORD = "Quartz-Lantern-Harbor-73"; // gitleaks:allow (test-only password)

    private final String database =
            "erp_bootstrap_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

    @BeforeEach
    void createAndMigrateDatabase() throws SQLException {
        try (Connection c = TestDatabase.superuserConnection(TestDatabase.DATABASE);
                Statement s = c.createStatement()) {
            s.execute("CREATE DATABASE " + database);
        }
        TestDatabase.bootstrap(TestDatabase.jdbcUrl(database));
        new SpringApplicationBuilder(ErpApplication.class)
                .profiles("prod", "migrate")
                .properties(
                        "ERP_DB_URL=" + TestDatabase.jdbcUrl(database),
                        "ERP_DB_MIGRATOR_USER=erp_migrator",
                        "ERP_DB_MIGRATOR_PASSWORD=" + TestDatabase.MIGRATOR_PASSWORD,
                        "logging.level.root=WARN")
                .run()
                .close();
    }

    @AfterEach
    void dropDatabase() throws SQLException {
        try (Connection c = TestDatabase.superuserConnection(TestDatabase.DATABASE);
                Statement s = c.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
        }
    }

    @Test
    void createsTheFirstSystemAdministratorOnceWithoutAWebServer() throws SQLException {
        try (ConfigurableApplicationContext context = bootstrap(PASSWORD)) {
            assertThat(context).isNotInstanceOf(WebApplicationContext.class);
        }
        bootstrap("Another-Strong-Passphrase-99").close();

        try (Connection c = TestDatabase.superuserConnection(database);
                Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("SELECT email, password_hash, status, is_system_admin FROM auth.users")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("email")).isEqualTo("first.admin@example.test");
            assertThat(rs.getString("password_hash")).startsWith("$argon2id$");
            assertThat(rs.getString("status")).isEqualTo("ACTIVE");
            assertThat(rs.getBoolean("is_system_admin")).isTrue();
            assertThat(rs.next())
                    .as("second run must not create another administrator")
                    .isFalse();
        }
        try (Connection c = TestDatabase.superuserConnection(database);
                Statement s = c.createStatement();
                ResultSet rs = s.executeQuery(
                        "SELECT count(*) FROM admin.audit_log WHERE action = 'CREATE' AND entity_type = 'user'")) {
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(1);
        }
    }

    @Test
    void refusesPasswordsThatViolateThePolicy() throws SQLException {
        assertThatThrownBy(() -> bootstrap("password1234").close()).hasStackTraceContaining("password");

        try (Connection c = TestDatabase.superuserConnection(database);
                Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("SELECT count(*) FROM auth.users")) {
            rs.next();
            assertThat(rs.getInt(1)).isZero();
        }
    }

    @Test
    void requiresTheBootstrapCredentials() {
        assertThatThrownBy(() -> new SpringApplicationBuilder(ErpApplication.class)
                        .profiles("prod", "bootstrap-admin")
                        .properties(
                                "ERP_DB_URL=" + TestDatabase.jdbcUrl(database),
                                "ERP_DB_APP_USER=erp_app",
                                "ERP_DB_APP_PASSWORD=" + TestDatabase.APP_PASSWORD,
                                "logging.level.root=OFF")
                        .run()
                        .close())
                .hasStackTraceContaining("ERP_BOOTSTRAP_ADMIN_EMAIL")
                .hasStackTraceContaining("ERP_BOOTSTRAP_ADMIN_PASSWORD");
    }

    private ConfigurableApplicationContext bootstrap(String password) {
        return new SpringApplicationBuilder(ErpApplication.class)
                .profiles("prod", "bootstrap-admin")
                .properties(
                        "ERP_DB_URL=" + TestDatabase.jdbcUrl(database),
                        "ERP_DB_APP_USER=erp_app",
                        "ERP_DB_APP_PASSWORD=" + TestDatabase.APP_PASSWORD,
                        "ERP_BOOTSTRAP_ADMIN_EMAIL=" + EMAIL,
                        "ERP_BOOTSTRAP_ADMIN_PASSWORD=" + password,
                        "logging.level.root=WARN")
                .run();
    }
}
