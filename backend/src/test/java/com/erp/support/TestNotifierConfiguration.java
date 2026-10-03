package com.erp.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration(proxyBeanMethods = false)
public class TestNotifierConfiguration {

    @Bean
    @Primary
    RecordingAccountNotifier recordingAccountNotifier() {
        return new RecordingAccountNotifier();
    }
}
