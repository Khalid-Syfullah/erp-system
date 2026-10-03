package com.erp.platform.security;

import com.erp.platform.context.CurrentContext;
import com.erp.platform.context.RequestContext;
import com.erp.platform.web.PlatformErrorCode;
import com.erp.platform.web.ProblemResponses;
import com.erp.platform.web.RequestLoggingFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates each request (SECURITY.md §4.4 layer 1) through the {@link RequestAuthenticator}
 * port:
 *
 * <ul>
 *   <li>{@code Authorization: Bearer} → API token only; cookies are ignored. An invalid, expired or
 *       revoked token is rejected with 401 at once (no anonymous fallback).
 *   <li>otherwise the session cookie, if present and valid; else the request stays anonymous and
 *       non-public endpoints answer 401.
 * </ul>
 *
 * The actor is published to Spring Security, the {@link RequestContext} and the logging MDC.
 */
public class ActorAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";

    private final ObjectProvider<RequestAuthenticator> authenticator;
    private final ProblemResponses problems;
    private final SecurityContextHolderStrategy contextHolder = SecurityContextHolder.getContextHolderStrategy();

    public ActorAuthenticationFilter(ObjectProvider<RequestAuthenticator> authenticator, ProblemResponses problems) {
        this.authenticator = authenticator;
        this.problems = problems;
    }

    /** True for requests that carry an {@code Authorization} header (exempt from CSRF and Origin checks). */
    public static boolean hasAuthorizationHeader(HttpServletRequest request) {
        return request.getHeader(HttpHeaders.AUTHORIZATION) != null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        RequestAuthenticator port = authenticator.getIfAvailable();
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        Optional<AuthenticatedActor> actor = Optional.empty();
        if (authorization != null) {
            boolean bearer = authorization.regionMatches(true, 0, BEARER, 0, BEARER.length());
            if (bearer && port != null) {
                actor = port.authenticateBearer(
                        authorization.substring(BEARER.length()).strip(), request);
            }
            if (actor.isEmpty()) {
                problems.write(
                        response,
                        problems.problem(
                                PlatformErrorCode.UNAUTHENTICATED,
                                "The bearer token is missing, invalid, expired or revoked.",
                                request,
                                List.of()));
                return;
            }
        } else if (port != null) {
            actor = port.authenticateSession(request, response);
        }
        actor.ifPresent(this::publish);
        chain.doFilter(request, response);
    }

    private void publish(AuthenticatedActor actor) {
        SecurityContext context = contextHolder.createEmptyContext();
        context.setAuthentication(new ActorAuthentication(actor));
        contextHolder.setContext(context);
        CurrentContext.get().ifPresent(current -> CurrentContext.set(current.withActor(actor)));
        MDC.put(RequestLoggingFilter.MDC_USER_ID, actor.userId().toString());
    }
}
