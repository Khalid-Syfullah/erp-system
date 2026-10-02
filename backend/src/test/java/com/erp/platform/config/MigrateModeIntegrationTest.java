package com.erp.platform.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.erp.ErpApplication;
import com.erp.support.TestDatabase;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.web.context.WebApplicationContext;

/**
 * The production migration job ({@code SPRING_PROFILES_ACTIVE=prod,migrate}): starts without a web
 * server, connects as the migrator role, leaves no pending migration (ARCHITECTURE.md §8.2).
 */
class MigrateModeIntegrationTest {

    @Test
    void startsWithoutWebServerAndAppliesMigrations() {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(ErpApplication.class)
                .profiles("prod", "migrate")
                .properties(
                        "ERP_DB_URL=" + TestDatabase.jdbcUrl(),
                        "ERP_DB_MIGRATOR_USER=erp_migrator",
                        "ERP_DB_MIGRATOR_PASSWORD=" + TestDatabase.MIGRATOR_PASSWORD,
                        "logging.level.root=WARN")
                .run()) {
            assertThat(context).isNotInstanceOf(WebApplicationContext.class);
            Flyway flyway = context.getBean(Flyway.class);
            assertThat(flyway.info().pending()).isEmpty();
            assertThat(flyway.info().current()).isNotNull();
        }
    }
}
