package com.erp.platform.tx;

import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link CompanyScopedTransactionManager} as the application's only transaction manager.
 * Spring Boot's jOOQ auto-configuration joins it (SpringTransactionProvider over a
 * transaction-aware DataSource), so jOOQ queries inside {@code @Transactional} methods run on the
 * prepared connection.
 */
@Configuration(proxyBeanMethods = false)
class TransactionConfiguration {

    @Bean
    CompanyScopedTransactionManager transactionManager(DataSource dataSource) {
        return new CompanyScopedTransactionManager(dataSource);
    }
}
