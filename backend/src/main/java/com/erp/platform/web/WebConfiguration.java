package com.erp.platform.web;

import com.erp.platform.config.ErpProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/** Servlet filters that run before Spring Security and Spring MVC. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class WebConfiguration {

    @Bean
    FilterRegistrationBean<RequestLoggingFilter> requestLoggingFilter() {
        FilterRegistrationBean<RequestLoggingFilter> registration =
                new FilterRegistrationBean<>(new RequestLoggingFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    @Bean
    FilterRegistrationBean<RequestBodyLimitFilter> requestBodyLimitFilter(
            ErpProperties properties, ProblemResponses problems) {
        FilterRegistrationBean<RequestBodyLimitFilter> registration = new FilterRegistrationBean<>(
                new RequestBodyLimitFilter(properties.api().maxRequestBodySize().toBytes(), problems));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        return registration;
    }
}
