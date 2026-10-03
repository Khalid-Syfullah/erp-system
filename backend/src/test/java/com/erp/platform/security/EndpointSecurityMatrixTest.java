package com.erp.platform.security;

import static com.erp.db.auth.Tables.PERMISSIONS;
import static com.erp.support.AuthTestSupport.unsafe;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import com.erp.auth.AuthPermissions;
import com.erp.support.AuthTestSupport;
import com.erp.support.AuthTestSupport.TestUser;
import com.erp.support.IntegrationTest;
import jakarta.servlet.http.Cookie;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Walks every registered API endpoint (DEVELOPMENT_PLAN Phase 3: "IDOR suite") and checks the three
 * outer layers of SECURITY.md §4.4 without knowing anything endpoint-specific:
 *
 * <ul>
 *   <li>anonymous requests to non-public endpoints are 401,
 *   <li>a member of company A addressing company B through any {@code {companyId}} path is 404,
 *   <li>a member without the required permission is 403 (before the handler runs).
 * </ul>
 *
 * New endpoints are covered automatically.
 */
class EndpointSecurityMatrixTest extends IntegrationTest {

    private static final Pattern PATH_VARIABLE = Pattern.compile("\\{([^}/]+)}");

    @Autowired
    MockMvc mvc;

    @Autowired
    AuthTestSupport auth;

    @Autowired
    DSLContext dsl;

    @Autowired
    @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping handlerMapping;

    record Endpoint(HttpMethod method, String pattern, HandlerMethod handler, MediaType contentType) {
        @Override
        public String toString() {
            return method + " " + pattern;
        }
    }

    private List<Endpoint> endpoints;

    @BeforeEach
    void collectEndpoints() {
        endpoints = new ArrayList<>();
        handlerMapping.getHandlerMethods().forEach((info, handler) -> {
            if (!handler.getBeanType().getPackageName().startsWith("com.erp.")) {
                return;
            }
            for (String pattern : patterns(info)) {
                if (pattern.startsWith("/api/v1/_test")) {
                    continue; // test-only probe controller
                }
                MediaType contentType = info.getConsumesCondition().getConsumableMediaTypes().stream()
                        .findFirst()
                        .orElse(MediaType.APPLICATION_JSON);
                for (var method : info.getMethodsCondition().getMethods()) {
                    endpoints.add(new Endpoint(HttpMethod.valueOf(method.name()), pattern, handler, contentType));
                }
            }
        });
        assertThat(endpoints).hasSizeGreaterThan(40);
    }

    @Test
    void everyNonPublicEndpointRejectsAnonymousRequests() throws Exception {
        List<String> violations = new ArrayList<>();
        for (Endpoint endpoint : endpoints) {
            if (EndpointAnnotations.isPublic(endpoint.handler())) {
                continue;
            }
            int status = perform(endpoint, UUID.randomUUID(), null);
            if (status != 401) {
                violations.add(endpoint + " -> " + status);
            }
        }
        assertThat(violations).isEmpty();
    }

    @Test
    void everyCompanyEndpointHidesCompaniesWithoutMembership() throws Exception {
        UUID mine = auth.company();
        UUID theirs = auth.company();
        TestUser user = auth.user();
        auth.assign(user, auth.customRole(harmlessPermission()), mine);
        Cookie session = auth.login(user);

        List<String> violations = new ArrayList<>();
        int checked = 0;
        for (Endpoint endpoint : endpoints) {
            if (!endpoint.pattern().contains("{companyId}") || EndpointAnnotations.isPublic(endpoint.handler())) {
                continue;
            }
            checked++;
            int status = perform(endpoint, theirs, session);
            if (status != 404) {
                violations.add(endpoint + " -> " + status);
            }
        }
        assertThat(checked).isGreaterThan(10);
        assertThat(violations).isEmpty();
    }

    @Test
    void everyPermissionGuardedEndpointRejectsMembersWithoutThePermission() throws Exception {
        UUID company = auth.company();
        TestUser user = auth.user();
        auth.assign(user, auth.customRole(harmlessPermission()), company);
        Cookie session = auth.login(user);

        List<String> violations = new ArrayList<>();
        for (Endpoint endpoint : endpoints) {
            if (EndpointAnnotations.find(endpoint.handler(), RequiresPermission.class) == null) {
                continue;
            }
            int status = perform(endpoint, company, session);
            if (status != 403) {
                violations.add(endpoint + " -> " + status);
            }
        }
        assertThat(violations).isEmpty();
    }

    @Test
    void everyEndpointDeclaresExactlyOneAccessLevel() {
        List<String> violations = new ArrayList<>();
        for (Endpoint endpoint : endpoints) {
            int declared = (EndpointAnnotations.isPublic(endpoint.handler()) ? 1 : 0)
                    + (EndpointAnnotations.find(endpoint.handler(), AuthenticatedEndpoint.class) != null ? 1 : 0)
                    + (EndpointAnnotations.find(endpoint.handler(), RequiresPermission.class) != null ? 1 : 0);
            boolean global = EndpointAnnotations.find(endpoint.handler(), GlobalAccess.class) != null;
            if (declared != 1
                    || (global && EndpointAnnotations.find(endpoint.handler(), RequiresPermission.class) == null)) {
                violations.add(endpoint.toString());
            }
        }
        assertThat(violations).isEmpty();
    }

    /** A non-sensitive permission that no endpoint requires, so a role with only it grants nothing. */
    private String harmlessPermission() {
        Set<String> required = new HashSet<>();
        for (Endpoint endpoint : endpoints) {
            RequiresPermission annotation = EndpointAnnotations.find(endpoint.handler(), RequiresPermission.class);
            if (annotation != null) {
                required.addAll(Arrays.asList(annotation.value()));
            }
        }
        return dsl
                .select(PERMISSIONS.CODE)
                .from(PERMISSIONS)
                .where(PERMISSIONS.IS_SENSITIVE.isFalse())
                .and(PERMISSIONS.DEPRECATED_AT.isNull())
                .orderBy(PERMISSIONS.CODE)
                .fetch(PERMISSIONS.CODE)
                .stream()
                .filter(code -> !required.contains(code) && !AuthPermissions.GLOBAL_ONLY.contains(code))
                .findFirst()
                .orElseThrow();
    }

    private int perform(Endpoint endpoint, UUID companyId, Cookie session) throws Exception {
        MockHttpServletRequestBuilder builder = request(endpoint.method(), expand(endpoint.pattern(), companyId));
        if (endpoint.method() != HttpMethod.GET && endpoint.method() != HttpMethod.HEAD) {
            builder = unsafe(builder)
                    .contentType(endpoint.contentType())
                    .content("{}")
                    .header("If-Match", "W/\"0\"");
        }
        if (session != null) {
            builder = builder.cookie(session);
        }
        return mvc.perform(builder).andReturn().getResponse().getStatus();
    }

    private static String expand(String pattern, UUID companyId) {
        Matcher matcher = PATH_VARIABLE.matcher(pattern);
        StringBuilder path = new StringBuilder();
        while (matcher.find()) {
            String value = matcher.group(1).equals("companyId")
                    ? companyId.toString()
                    : UUID.randomUUID().toString();
            matcher.appendReplacement(path, value);
        }
        matcher.appendTail(path);
        return path.toString();
    }

    private static Set<String> patterns(RequestMappingInfo info) {
        return info.getPathPatternsCondition() != null
                ? info.getPathPatternsCondition().getPatternValues()
                : info.getPatternValues();
    }
}
