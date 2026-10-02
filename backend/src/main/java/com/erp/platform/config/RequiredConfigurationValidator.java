package com.erp.platform.config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.util.StringUtils;

/**
 * Fails startup early, before any datasource is created, when required configuration is missing or
 * unsafe. Error messages name the missing environment variables but never echo configured values.
 *
 * <p>Runs as a {@link BeanFactoryPostProcessor} so that every property source (including test
 * {@code @DynamicPropertySource}s) is visible.
 */
public final class RequiredConfigurationValidator implements BeanFactoryPostProcessor, EnvironmentAware {

    record Requirement(String property, String environmentVariable) {
        @Override
        public String toString() {
            return environmentVariable + " (" + property + ")";
        }
    }

    static final List<Requirement> DATASOURCE = List.of(
            new Requirement("spring.datasource.url", "ERP_DB_URL"),
            new Requirement("spring.datasource.username", "ERP_DB_APP_USER"),
            new Requirement("spring.datasource.password", "ERP_DB_APP_PASSWORD"));

    static final List<Requirement> MIGRATIONS = List.of(
            new Requirement("spring.flyway.user", "ERP_DB_MIGRATOR_USER"),
            new Requirement("spring.flyway.password", "ERP_DB_MIGRATOR_PASSWORD"));

    static final List<Requirement> PRODUCTION =
            List.of(new Requirement("erp.api.cursor-signing-key", "ERP_API_CURSOR_SIGNING_KEY"));

    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        validate(environment);
    }

    static void validate(Environment env) {
        List<Requirement> required = new ArrayList<>(DATASOURCE);
        boolean migrationsEnabled = Boolean.parseBoolean(read(env, "spring.flyway.enabled"));
        boolean production = env.acceptsProfiles(Profiles.of("prod"));
        boolean migrateMode = env.acceptsProfiles(Profiles.of("migrate"));
        if (migrationsEnabled) {
            required.addAll(MIGRATIONS);
        }
        if (production && !migrateMode) {
            required.addAll(PRODUCTION);
        }

        List<String> problems = new ArrayList<>();
        List<Requirement> missing = required.stream()
                .filter(r -> !StringUtils.hasText(read(env, r.property())))
                .toList();
        if (!missing.isEmpty()) {
            problems.add("missing required configuration: " + missing);
        }
        if (production && !migrateMode && migrationsEnabled) {
            problems.add("database migrations must not run on application startup in production;"
                    + " run them as a separate step with the 'migrate' profile (ARCHITECTURE.md §8.2)");
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Invalid configuration for profiles "
                    + Arrays.toString(env.getActiveProfiles()) + ": " + String.join("; ", problems));
        }
    }

    private static String read(Environment env, String property) {
        try {
            return env.getProperty(property);
        } catch (IllegalArgumentException unresolvablePlaceholder) {
            return null;
        }
    }
}
