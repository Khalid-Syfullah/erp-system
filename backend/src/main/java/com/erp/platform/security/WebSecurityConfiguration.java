package com.erp.platform.security;

import com.erp.platform.config.ErpProperties;
import com.erp.platform.web.ProblemResponses;
import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.header.writers.CrossOriginResourcePolicyHeaderWriter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * HTTP security baseline (SECURITY.md §1, §10). Phase 2 configures no authentication mechanism yet,
 * so every non-public API endpoint answers 401 until Phase 3 adds sessions and API tokens.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class WebSecurityConfiguration {

    static final String CSRF_HEADER = "X-CSRF-Token";
    static final String CSRF_COOKIE = "XSRF-TOKEN";

    /** Actuator endpoints are served on the internal management port only (ARCHITECTURE.md §6.8). */
    @Bean
    @Order(1)
    SecurityFilterChain managementSecurityFilterChain(HttpSecurity http) throws Exception {
        return http.securityMatcher(EndpointRequest.toAnyEndpoint())
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
                .csrf(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain apiSecurityFilterChain(
            HttpSecurity http,
            EndpointAccessPolicy endpointAccessPolicy,
            ProblemResponses problems,
            ErpProperties properties)
            throws Exception {
        ProblemSecurityHandlers handlers = new ProblemSecurityHandlers(problems);
        CookieCsrfTokenRepository csrfTokens = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfTokens.setHeaderName(CSRF_HEADER);
        csrfTokens.setCookieName(CSRF_COOKIE);
        csrfTokens.setCookiePath("/");
        csrfTokens.setCookieCustomizer(
                cookie -> cookie.sameSite("Strict").secure(properties.security().csrfCookieSecure()));

        return http.authorizeHttpRequests(authorize -> authorize
                        .dispatcherTypeMatchers(DispatcherType.ERROR)
                        .permitAll()
                        .anyRequest()
                        .access(endpointAccessPolicy))
                .csrf(csrf -> csrf.spa().csrfTokenRepository(csrfTokens))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .exceptionHandling(exceptions ->
                        exceptions.authenticationEntryPoint(handlers).accessDeniedHandler(handlers))
                .headers(headers -> headers.contentSecurityPolicy(
                                csp -> csp.policyDirectives("default-src 'none'; frame-ancestors 'none'"))
                        .referrerPolicy(
                                referrer -> referrer.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                        .frameOptions(frame -> frame.deny())
                        .httpStrictTransportSecurity(
                                hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(63_072_000))
                        .crossOriginResourcePolicy(corp -> corp.policy(
                                CrossOriginResourcePolicyHeaderWriter.CrossOriginResourcePolicy.SAME_ORIGIN)))
                .build();
    }

    @Bean
    EndpointAccessPolicy endpointAccessPolicy(
            @Qualifier("requestMappingHandlerMapping") ObjectProvider<RequestMappingHandlerMapping> handlerMapping) {
        return new EndpointAccessPolicy(handlerMapping);
    }

    @Bean
    WebMvcConfigurer endpointAccessInterceptorConfigurer(ObjectProvider<PermissionCheck> permissionCheck) {
        EndpointAccessInterceptor interceptor = new EndpointAccessInterceptor(permissionCheck);
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(interceptor);
            }
        };
    }
}
