package com.erp.platform.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class RequiredConfigurationValidatorTest {

    private static MockEnvironment complete() {
        return new MockEnvironment()
                .withProperty("spring.datasource.url", "jdbc:postgresql://db/erp")
                .withProperty("spring.datasource.username", "erp_app")
                .withProperty("spring.datasource.password", "app-secret-value")
                .withProperty("erp.api.cursor-signing-key", "configured");
    }

    @Test
    void acceptsCompleteConfiguration() {
        MockEnvironment env = complete();
        env.setActiveProfiles("prod");

        assertThatCode(() -> RequiredConfigurationValidator.validate(env)).doesNotThrowAnyException();
    }

    @Test
    void listsMissingVariablesWithoutEchoingValues() {
        MockEnvironment env = complete().withProperty("spring.datasource.password", "");
        env.setProperty("erp.api.cursor-signing-key", " ");
        env.setActiveProfiles("prod");

        assertThatThrownBy(() -> RequiredConfigurationValidator.validate(env))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ERP_DB_APP_PASSWORD")
                .hasMessageContaining("ERP_API_CURSOR_SIGNING_KEY")
                .hasMessageNotContaining("jdbc:postgresql")
                .hasMessageNotContaining("erp_app");
    }

    @Test
    void requiresMigratorCredentialsOnlyWhenMigrationsRun() {
        MockEnvironment env = complete().withProperty("spring.flyway.enabled", "true");

        assertThatThrownBy(() -> RequiredConfigurationValidator.validate(env))
                .hasMessageContaining("ERP_DB_MIGRATOR_PASSWORD");

        env.setProperty("spring.flyway.user", "erp_migrator");
        env.setProperty("spring.flyway.password", "migrator-secret");
        assertThatCode(() -> RequiredConfigurationValidator.validate(env)).doesNotThrowAnyException();
    }

    @Test
    void forbidsMigrationsOnProductionApiStartup() {
        MockEnvironment env = complete()
                .withProperty("spring.flyway.enabled", "true")
                .withProperty("spring.flyway.user", "erp_migrator")
                .withProperty("spring.flyway.password", "migrator-secret");
        env.setActiveProfiles("prod");

        assertThatThrownBy(() -> RequiredConfigurationValidator.validate(env))
                .hasMessageContaining("'migrate' profile");

        env.setActiveProfiles("prod", "migrate");
        assertThatCode(() -> RequiredConfigurationValidator.validate(env)).doesNotThrowAnyException();
    }

    @Test
    void treatsUnresolvablePlaceholdersAsMissing() {
        MockEnvironment env = complete().withProperty("spring.datasource.url", "${ERP_DB_URL_NOT_SET}");

        assertThatThrownBy(() -> RequiredConfigurationValidator.validate(env)).hasMessageContaining("ERP_DB_URL");
    }
}
