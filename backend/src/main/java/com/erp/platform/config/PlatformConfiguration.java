package com.erp.platform.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class PlatformConfiguration {

    /** Static so that it runs before other bean definitions are instantiated. */
    @Bean
    static RequiredConfigurationValidator requiredConfigurationValidator() {
        return new RequiredConfigurationValidator();
    }
}
