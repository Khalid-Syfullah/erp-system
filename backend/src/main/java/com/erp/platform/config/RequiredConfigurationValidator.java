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

    static final List<Requirement> PRODUCTION = List.of(
            new Requirement("erp.api.cursor-signing-key", "ERP_API_CURSOR_SIGNING_KEY"),
            new Requirement("erp.security.allowed-origins", "ERP_SECURITY_ALLOWED_ORIGINS"),
            new Requirement("erp.crypto.field-encryption-keys", "ERP_FIELD_ENCRYPTION_KEYS"),
            new Requirement("erp.auth.public-base-url", "ERP_AUTH_PUBLIC_BASE_URL"),
            new Requirement("spring.mail.host", "ERP_MAIL_HOST"),
            new Requirement("erp.files.bucket", "ERP_FILES_BUCKET"));

    static final List<Requirement> BOOTSTRAP_ADMIN = List.of(
            new Requirement("erp.auth.bootstrap.admin-email", "ERP_BOOTSTRAP_ADMIN_EMAIL"),
            new Requirement("erp.auth.bootstrap.admin-password", "ERP_BOOTSTRAP_ADMIN_PASSWORD"));

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
        boolean bootstrapMode = env.acceptsProfiles(Profiles.of("bootstrap-admin"));
        if (migrationsEnabled) {
            required.addAll(MIGRATIONS);
        }
        if (production && !migrateMode && !bootstrapMode) {
            required.addAll(PRODUCTION);
        }
        if (bootstrapMode) {
            required.addAll(BOOTSTRAP_ADMIN);
        }

        List<String> problems = new ArrayList<>();
        List<Requirement> missing = required.stream()
                .filter(r -> !StringUtils.hasText(read(env, r.property())))
                .toList();
        if (!missing.isEmpty()) {
            problems.add("missing required configuration: " + missing);
        }
        if (production && !migrateMode && !bootstrapMode && migrationsEnabled) {
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
