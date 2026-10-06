package com.erp.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * One PostgreSQL container per test JVM, prepared exactly like a real environment: the role
 * bootstrap (infra/db/bootstrap/00-roles.sql) runs as superuser, then the application connects as
 * {@code erp_app} and Flyway as {@code erp_migrator} (DATABASE.md §2.7). Passwords are random per run.
 */
public final class TestDatabase {

    public static final String DATABASE = "erp";
    public static final String MIGRATOR_PASSWORD = secret();
    public static final String APP_PASSWORD = secret();
    public static final String REPORTING_PASSWORD = secret();

    private static final PostgreSQLContainer CONTAINER = new PostgreSQLContainer(
                    System.getProperty("erp.test.postgres-image", "postgres:18.6"))
            .withDatabaseName(DATABASE);

    static {
        CONTAINER.start();
        bootstrap(CONTAINER.getJdbcUrl());
    }

    private TestDatabase() {}

    public static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", CONTAINER::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "erp_app");
        registry.add("spring.datasource.password", () -> APP_PASSWORD);
        registry.add("spring.flyway.user", () -> "erp_migrator");
        registry.add("spring.flyway.password", () -> MIGRATOR_PASSWORD);
        registry.add("erp.reporting.datasource.password", () -> REPORTING_PASSWORD);
        registry.add("erp.reporting.datasource.pool-size", () -> "3");
    }

    public static String jdbcUrl() {
        return CONTAINER.getJdbcUrl();
    }

    /** JDBC URL of another database in the same container. */
    public static String jdbcUrl(String database) {
        return CONTAINER.getJdbcUrl().replace("/" + DATABASE, "/" + database);
    }

    public static Connection superuserConnection(String database) throws SQLException {
        return DriverManager.getConnection(jdbcUrl(database), CONTAINER.getUsername(), CONTAINER.getPassword());
    }

    public static Connection connectAs(String role, String password) throws SQLException {
        return DriverManager.getConnection(jdbcUrl(), role, password);
    }

    /** Applies the role bootstrap to the database behind {@code jdbcUrl} and sets test passwords. */
    public static void bootstrap(String jdbcUrl) {
        try (Connection connection =
                        DriverManager.getConnection(jdbcUrl, CONTAINER.getUsername(), CONTAINER.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute(Files.readString(bootstrapScript()));
            // Random hex only, so string concatenation cannot inject SQL.
            statement.execute("ALTER ROLE erp_migrator PASSWORD '" + MIGRATOR_PASSWORD + "'");
            statement.execute("ALTER ROLE erp_app PASSWORD '" + APP_PASSWORD + "'");
            statement.execute("ALTER ROLE erp_reporting PASSWORD '" + REPORTING_PASSWORD + "'");
        } catch (SQLException e) {
            throw new IllegalStateException("Database bootstrap failed", e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path bootstrapScript() {
        String configured = System.getProperty("erp.test.bootstrap-sql");
        Path path = configured != null ? Path.of(configured) : Path.of("../infra/db/bootstrap/00-roles.sql");
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("Bootstrap SQL not found: " + path.toAbsolutePath());
        }
        return path;
    }

    private static String secret() {
        return UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");
    }
}
