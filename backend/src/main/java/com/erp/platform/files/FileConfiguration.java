package com.erp.platform.files;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(FileProperties.class)
class FileConfiguration {

    @Bean(destroyMethod = "close")
    S3FileStorage fileStorage(FileProperties properties) {
        return new S3FileStorage(properties);
    }
}
