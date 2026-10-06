package com.erp.reporting.persistence;

import com.erp.reporting.application.ReportingProperties;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

/** Creates the reporting connection pool from {@code erp.reporting.datasource}. */
@Configuration(proxyBeanMethods = false)
class ReportingDatabaseConfiguration {

    @Bean(destroyMethod = "close")
    ReportingDatabase reportingDatabase(ReportingProperties properties, Environment environment) {
        ReportingProperties.Datasource settings = properties.datasource();
        String url = StringUtils.hasText(settings.url())
                ? settings.url()
                : environment.getRequiredProperty("spring.datasource.url");
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setPoolName("erp-reporting");
        dataSource.setJdbcUrl(url);
        dataSource.setUsername(settings.username());
        dataSource.setPassword(settings.password());
        dataSource.setMaximumPoolSize(settings.poolSize());
        dataSource.setMinimumIdle(0);
        dataSource.setReadOnly(true);
        dataSource.setAutoCommit(false);
        // Connect lazily: the application starts even while the reporting replica is unavailable.
        dataSource.setInitializationFailTimeout(-1);
        return new ReportingDatabase(dataSource);
    }
}
