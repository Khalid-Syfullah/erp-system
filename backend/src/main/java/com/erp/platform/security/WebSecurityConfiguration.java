package com.erp.platform.security;

import com.erp.platform.config.ErpProperties;
import com.erp.platform.web.ProblemResponses;
import jakarta.servlet.DispatcherType;
import java.time.Clock;
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
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.header.writers.CrossOriginResourcePolicyHeaderWriter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * HTTP security (SECURITY.md §3–§4, §9–§10). Filter order for API requests:
 *
 * <ol>
 *   <li>{@link OriginVerificationFilter} – unsafe cookie-based requests from allowed origins only
 *   <li>CSRF – cookie {@code XSRF-TOKEN} echoed in {@code X-CSRF-Token}; requests with an
 *       {@code Authorization} header are exempt
 *   <li>{@link ActorAuthenticationFilter} – bearer token or session cookie via {@link RequestAuthenticator}
 *   <li>{@link RateLimitFilter}
 *   <li>authorization – anonymous only for {@link PublicEndpoint} handlers ({@link EndpointAccessPolicy})
 * </ol>
 *
 * Then, in Spring MVC: {@link CompanyContextInterceptor} (404 for companies without membership) and
 * {@link EndpointAccessInterceptor} (permissions).
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

    /** Shared with Auth, which rotates the token at login and serves it at {@code GET /auth/csrf}. */
    @Bean
    CookieCsrfTokenRepository csrfTokenRepository(ErpProperties properties) {
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setHeaderName(CSRF_HEADER);
        repository.setCookieName(CSRF_COOKIE);
        repository.setCookiePath("/");
        repository.setCookieCustomizer(
                cookie -> cookie.sameSite("Strict").secure(properties.security().secureCookies()));
        return repository;
    }

    @Bean
    SecurityAudit securityAudit(ObjectProvider<com.erp.platform.audit.AuditPort> audit) {
        return new SecurityAudit(audit);
    }

    @Bean
    FixedWindowRateLimiter fixedWindowRateLimiter(Clock clock) {
        return new FixedWindowRateLimiter(clock);
    }

    @Bean
    @Order(2)
    SecurityFilterChain apiSecurityFilterChain(
            HttpSecurity http,
            EndpointAccessPolicy endpointAccessPolicy,
            ProblemResponses problems,
            ErpProperties properties,
            CookieCsrfTokenRepository csrfTokenRepository,
            ObjectProvider<RequestAuthenticator> authenticator,
            FixedWindowRateLimiter rateLimiter,
            SecurityAudit securityAudit)
            throws Exception {
        ProblemSecurityHandlers handlers = new ProblemSecurityHandlers(problems, securityAudit);
        return http.authorizeHttpRequests(authorize -> authorize
                        .dispatcherTypeMatchers(DispatcherType.ERROR)
                        .permitAll()
                        .anyRequest()
                        .access(endpointAccessPolicy))
                .csrf(csrf -> csrf.spa()
                        .csrfTokenRepository(csrfTokenRepository)
                        .ignoringRequestMatchers(ActorAuthenticationFilter::hasAuthorizationHeader))
                .addFilterBefore(
                        new OriginVerificationFilter(properties.security().allowedOrigins(), problems, securityAudit),
                        CsrfFilter.class)
                .addFilterBefore(
                        new ActorAuthenticationFilter(authenticator, problems), AnonymousAuthenticationFilter.class)
                .addFilterBefore(
                        new RateLimitFilter(rateLimiter, properties.security().rateLimits(), problems),
                        AuthorizationFilter.class)
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
    WebMvcConfigurer endpointAccessInterceptorConfigurer(
            ObjectProvider<CompanyAccessResolver> companyAccessResolver,
            ObjectProvider<PermissionCheck> permissionCheck,
            SecurityAudit securityAudit) {
        CompanyContextInterceptor companyContext = new CompanyContextInterceptor(companyAccessResolver);
        EndpointAccessInterceptor endpointAccess = new EndpointAccessInterceptor(permissionCheck, securityAudit);
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(companyContext).order(0);
                registry.addInterceptor(endpointAccess).order(10);
            }
        };
    }
}
