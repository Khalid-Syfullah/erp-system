package com.erp.platform.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.erp.platform.security.AuthenticatedEndpoint;
import com.erp.platform.security.PublicEndpoint;
import com.erp.platform.security.RequiresPermission;
import com.erp.support.IntegrationTest;
import com.jayway.jsonpath.JsonPath;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springdoc.webmvc.api.OpenApiWebMvcResource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Generates the OpenAPI document (DEVELOPMENT_PLAN.md §3) into {@code build/openapi/openapi.json} and
 * checks it against the code: every API operation is documented with the {@code x-permission} of its
 * access annotation, and with the problem responses for authentication and permission failures.
 */
@TestPropertySource(properties = "springdoc.api-docs.enabled=true")
class OpenApiContractTest extends IntegrationTest {

    @Autowired
    OpenApiWebMvcResource openApi;

    @Autowired
    RequestMappingHandlerMapping handlerMapping;

    @Test
    void everyOperationIsDocumentedWithItsAccessRule() throws Exception {
        String json = new String(
                openApi.openapiJson(new MockHttpServletRequest("GET", "/v3/api-docs"), "/v3/api-docs", Locale.ROOT),
                java.nio.charset.StandardCharsets.UTF_8);
        Path out = Path.of("build", "openapi", "openapi.json");
        Files.createDirectories(out.getParent());
        Files.writeString(out, json);

        assertThat((String) JsonPath.read(json, "$.openapi")).startsWith("3.1");
        // Decimals are strings on the wire (API.md §11).
        assertThat((String) JsonPath.read(json, "$.components.schemas.StockLevel.properties.onHand.type"))
                .isEqualTo("string");
        assertThat(JsonPath.<List<Object>>read(json, "$.components.schemas..properties[?(@.type == 'number')]"))
                .as("no JSON numbers in the API")
                .isEmpty();
        Map<String, Map<String, Object>> paths = JsonPath.read(json, "$.paths");

        Set<String> expected = new TreeSet<>();
        Map<String, Object> expectedAccess = new java.util.HashMap<>();
        handlerMapping.getHandlerMethods().forEach((info, handler) -> {
            if (!handler.getBeanType().getName().startsWith("com.erp")) {
                return;
            }
            for (String pattern : info.getPatternValues()) {
                if (!pattern.startsWith("/api/")) {
                    continue;
                }
                for (var method : info.getMethodsCondition().getMethods()) {
                    String key = method.name().toLowerCase(Locale.ROOT) + " " + pattern;
                    expected.add(key);
                    expectedAccess.put(key, access(handler));
                }
            }
        });

        Set<String> documented = new TreeSet<>();
        List<String> wrong = new ArrayList<>();
        paths.forEach((path, operations) -> operations.forEach((verb, raw) -> {
            if (!(raw instanceof Map<?, ?> operation)) {
                return;
            }
            String key = verb + " " + path;
            documented.add(key);
            Object permission = operation.get(OpenApiConfiguration.PERMISSION_EXTENSION);
            if (!normalized(expectedAccess.get(key)).equals(normalized(permission))) {
                wrong.add(key + ": documented " + permission + ", code says " + expectedAccess.get(key));
            }
            Map<?, ?> responses = (Map<?, ?>) operation.get("responses");
            if (!"public".equals(permission) && !responses.containsKey("401")) {
                wrong.add(key + ": no 401 response");
            }
            if (permission instanceof List<?> && !responses.containsKey("403")) {
                wrong.add(key + ": no 403 response");
            }
        }));

        assertThat(documented).as("documented operations").containsExactlyInAnyOrderElementsOf(expected);
        assertThat(wrong).isEmpty();
        assertThat(expected)
                .contains(
                        "post /api/v1/companies/{companyId}/stock-movements/{movementId}/post",
                        "get /api/v1/companies/{companyId}/stock-levels");
        List<String> postPermission = JsonPath.read(
                json,
                "$.paths['/api/v1/companies/{companyId}/stock-movements/{movementId}/post'].post['x-permission']");
        assertThat(postPermission).containsExactly("inventory.movement.post");
        assertThat(JsonPath.<Map<String, Object>>read(
                        json,
                        "$.paths['/api/v1/companies/{companyId}/stock-movements/{movementId}/post'].post.responses"))
                .containsKeys("401", "403", "404", "409", "412", "428");
    }

    private static String normalized(Object access) {
        return access instanceof List<?> list
                ? String.join(",", list.stream().map(String::valueOf).toList())
                : String.valueOf(access);
    }

    private static Object access(HandlerMethod handler) {
        RequiresPermission permission = find(handler, RequiresPermission.class);
        if (permission != null) {
            return List.of(permission.value());
        }
        if (find(handler, PublicEndpoint.class) != null) {
            return "public";
        }
        return find(handler, AuthenticatedEndpoint.class) != null ? "authenticated" : "none";
    }

    private static <A extends java.lang.annotation.Annotation> A find(HandlerMethod handler, Class<A> type) {
        A onMethod = AnnotatedElementUtils.findMergedAnnotation(handler.getMethod(), type);
        return onMethod != null ? onMethod : AnnotatedElementUtils.findMergedAnnotation(handler.getBeanType(), type);
    }
}
