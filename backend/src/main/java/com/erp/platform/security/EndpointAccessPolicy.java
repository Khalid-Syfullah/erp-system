package com.erp.platform.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.AuthenticationTrustResolver;
import org.springframework.security.authentication.AuthenticationTrustResolverImpl;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.ServletRequestPathUtils;

/**
 * Filter-level authorization (SECURITY.md §4.4, layer 1): a request is let through anonymously only
 * if it maps to a handler annotated {@link PublicEndpoint}; everything else, including unknown paths,
 * requires an authenticated principal. Finer checks happen in {@link EndpointAccessInterceptor}.
 */
public class EndpointAccessPolicy implements AuthorizationManager<RequestAuthorizationContext> {

    private final ObjectProvider<RequestMappingHandlerMapping> handlerMapping;
    private final AuthenticationTrustResolver trustResolver = new AuthenticationTrustResolverImpl();
    private volatile List<RequestMappingInfo> publicMappings;

    public EndpointAccessPolicy(ObjectProvider<RequestMappingHandlerMapping> handlerMapping) {
        this.handlerMapping = handlerMapping;
    }

    @Override
    public AuthorizationResult authorize(
            Supplier<? extends Authentication> authentication, RequestAuthorizationContext context) {
        if (isPublic(context.getRequest())) {
            return new AuthorizationDecision(true);
        }
        Authentication current = authentication.get();
        return new AuthorizationDecision(trustResolver.isAuthenticated(current));
    }

    boolean isPublic(HttpServletRequest request) {
        boolean parsedHere = !ServletRequestPathUtils.hasParsedRequestPath(request);
        if (parsedHere) {
            ServletRequestPathUtils.parseAndCache(request);
        }
        try {
            for (RequestMappingInfo mapping : publicMappings()) {
                if (mapping.getMatchingCondition(request) != null) {
                    return true;
                }
            }
            return false;
        } finally {
            if (parsedHere) {
                ServletRequestPathUtils.clearParsedRequestPath(request);
            }
        }
    }

    private List<RequestMappingInfo> publicMappings() {
        List<RequestMappingInfo> mappings = publicMappings;
        if (mappings == null) {
            mappings = handlerMapping.getObject().getHandlerMethods().entrySet().stream()
                    .filter(entry -> EndpointAnnotations.isPublic(entry.getValue()))
                    .map(java.util.Map.Entry::getKey)
                    .toList();
            publicMappings = mappings;
        }
        return mappings;
    }
}
