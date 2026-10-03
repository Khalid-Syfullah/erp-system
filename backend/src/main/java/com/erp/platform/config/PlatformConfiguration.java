package com.erp.platform.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class PlatformConfiguration {

    /** The application clock (UTC). Inject it instead of calling {@code Instant.now()} in domain code. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** Static so that it runs before other bean definitions are instantiated. */
    @Bean
    static RequiredConfigurationValidator requiredConfigurationValidator() {
        return new RequiredConfigurationValidator();
    }
}
