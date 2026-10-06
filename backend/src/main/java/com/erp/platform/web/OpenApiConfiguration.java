package com.erp.platform.web;

import com.erp.platform.security.AuthenticatedEndpoint;
import com.erp.platform.security.PublicEndpoint;
import com.erp.platform.security.RequiresPermission;
import io.swagger.v3.core.converter.ModelConverter;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.function.SingletonSupplier;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * The OpenAPI 3.1 document (DEVELOPMENT_PLAN.md §3, ADR-035). Every operation carries
 * {@code x-permission} from its access annotation ({@code public}, {@code authenticated} or the
 * permission codes) and the problem responses that apply to it (API.md §6).
 *
 * <p>The document is not served at runtime ({@code springdoc.api-docs.enabled=false}): springdoc's
 * endpoint has none of the access annotations, so the endpoint interceptor would deny it anyway.
 * {@code OpenApiContractTest} generates it into {@code build/openapi/openapi.json} and checks it.
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfiguration {

    public static final String PERMISSION_EXTENSION = "x-permission";
    private static final String PROBLEM = "Problem";
    private static final String SESSION = "session";
    private static final String TOKEN = "token";

    static {
        // Decimals travel as strings (API.md §11), never as JSON numbers.
        SpringDocUtils.getConfig()
                .replaceWithSchema(
                        BigDecimal.class,
                        new StringSchema()
                                .format("decimal")
                                .pattern("^-?[0-9]+(\\.[0-9]+)?$")
                                .example("1234.50"));
    }

    @Bean
    OpenAPI erpOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("ERP API")
                        .version("v1")
                        .description("Company-scoped business API. Errors are RFC 9457 problem documents (API.md §6); "
                                + "decimals are strings (API.md §11)."))
                .components(new Components()
                        .addSecuritySchemes(
                                SESSION,
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.APIKEY)
                                        .in(SecurityScheme.In.COOKIE)
                                        .name("__Host-erp_session"))
                        .addSecuritySchemes(
                                TOKEN,
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .description("Personal or service access token (erp_pat_…)"))
                        .addSchemas(PROBLEM, problemSchema()));
    }

    /** Unique schema names for same-named request and response records (OpenApiSchemaNames). */
    @Bean
    ModelConverter uniqueSchemaNames(
            @Qualifier("requestMappingHandlerMapping") ObjectProvider<RequestMappingHandlerMapping> handlerMapping) {
        return new OpenApiSchemaNames.Converter(SingletonSupplier.of(() -> {
            List<Method> handlers = new ArrayList<>();
            handlerMapping.getObject().getHandlerMethods().forEach((info, handler) -> {
                if (handler.getBeanType().getName().startsWith("com.erp.")
                        && info.getPatternValues().stream()
                                .anyMatch(p -> p.startsWith("/api/") && !p.startsWith("/api/v1/_test/"))) {
                    handlers.add(handler.getMethod());
                }
            });
            return OpenApiSchemaNames.qualifiedNames(OpenApiSchemaNames.reachableTypes(handlers));
        }));
    }

    @Bean
    OperationCustomizer accessAndProblems() {
        return OpenApiConfiguration::customize;
    }

    static Operation customize(Operation operation, HandlerMethod handler) {
        RequiresPermission permission = find(handler, RequiresPermission.class);
        boolean isPublic = find(handler, PublicEndpoint.class) != null;
        boolean authenticated = find(handler, AuthenticatedEndpoint.class) != null;
        Object access = permission != null
                ? List.of(permission.value())
                : isPublic ? "public" : authenticated ? "authenticated" : "none";
        operation.addExtension(PERMISSION_EXTENSION, access);
        if (!isPublic) {
            operation.addSecurityItem(new SecurityRequirement().addList(SESSION));
            operation.addSecurityItem(new SecurityRequirement().addList(TOKEN));
        }

        ApiResponses responses = operation.getResponses() != null ? operation.getResponses() : new ApiResponses();
        problem(responses, "400", "BAD_REQUEST: malformed JSON, unknown properties, invalid parameters");
        if (!isPublic) {
            problem(responses, "401", "UNAUTHENTICATED");
        }
        if (permission != null) {
            problem(responses, "403", "FORBIDDEN: missing " + String.join(", ", permission.value()));
        }
        if (handler.getMethod().getDeclaringClass().getName().startsWith("com.erp")
                && hasPathVariable(operation, "companyId")) {
            problem(responses, "404", "NOT_FOUND: not a member of the company, or the resource is not visible");
        }
        if (operation.getRequestBody() != null) {
            problem(responses, "422", "VALIDATION_FAILED or a business rule code (API.md §6.1)");
        }
        if (hasHeader(handler, EntityTags.IF_MATCH)) {
            problem(responses, "409", "VERSION_CONFLICT, INVALID_STATE, RESOURCE_IN_USE or RESOURCE_BUSY");
            problem(responses, "412", "PRECONDITION_FAILED: stale If-Match");
            problem(responses, "428", "PRECONDITION_REQUIRED: If-Match missing");
        }
        if (hasHeader(handler, "Idempotency-Key")) {
            problem(responses, "409", "IDEMPOTENCY_KEY_REUSED, IDEMPOTENCY_IN_PROGRESS or a state conflict");
        }
        operation.setResponses(responses);
        return operation;
    }

    private static boolean hasPathVariable(Operation operation, String name) {
        List<Parameter> parameters = operation.getParameters() != null ? operation.getParameters() : List.of();
        return parameters.stream().anyMatch(p -> "path".equals(p.getIn()) && name.equals(p.getName()));
    }

    /** Header parameters the handler declares, or (for idempotent commands) reads from the request. */
    private static boolean hasHeader(HandlerMethod handler, String name) {
        for (var parameter : handler.getMethodParameters()) {
            RequestHeader header = parameter.getParameterAnnotation(RequestHeader.class);
            if (header != null && (name.equalsIgnoreCase(header.name()) || name.equalsIgnoreCase(header.value()))) {
                return true;
            }
            if ("Idempotency-Key".equals(name)
                    && parameter.getParameterType().getName().equals("jakarta.servlet.http.HttpServletRequest")) {
                return true;
            }
        }
        return false;
    }

    private static void problem(ApiResponses responses, String status, String description) {
        ApiResponse existing = responses.get(status);
        if (existing != null
                && existing.getDescription() != null
                && !existing.getDescription().contains(description)) {
            existing.setDescription(existing.getDescription() + "; " + description);
            return;
        }
        responses.addApiResponse(
                status,
                new ApiResponse()
                        .description(description)
                        .content(new Content()
                                .addMediaType(
                                        "application/problem+json",
                                        new MediaType()
                                                .schema(new Schema<>().$ref("#/components/schemas/" + PROBLEM)))));
    }

    private static Schema<?> problemSchema() {
        Schema<Object> violation = new Schema<>();
        violation.setType("object");
        violation.setProperties(Map.of(
                "pointer", new Schema<>().type("string"),
                "parameter", new Schema<>().type("string"),
                "code", new Schema<>().type("string"),
                "message", new Schema<>().type("string"),
                "meta", new Schema<>().type("object")));
        Schema<Object> problem = new Schema<>();
        problem.setType("object");
        problem.setRequired(new ArrayList<>(List.of("type", "title", "status", "code")));
        problem.setProperties(Map.of(
                "type", new Schema<>().type("string"),
                "title", new Schema<>().type("string"),
                "status", new Schema<>().type("integer"),
                "detail", new Schema<>().type("string"),
                "instance", new Schema<>().type("string"),
                "code", new Schema<>().type("string").description("Stable error code (API.md §6.1)"),
                "requestId", new Schema<>().type("string"),
                "errors", new ArraySchema().items(violation)));
        return problem;
    }

    private static <A extends Annotation> @Nullable A find(HandlerMethod handler, Class<A> type) {
        A onMethod = AnnotatedElementUtils.findMergedAnnotation(handler.getMethod(), type);
        return onMethod != null ? onMethod : AnnotatedElementUtils.findMergedAnnotation(handler.getBeanType(), type);
    }
}
