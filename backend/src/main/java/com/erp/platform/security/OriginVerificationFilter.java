package com.erp.platform.security;

import com.erp.platform.web.PlatformErrorCode;
import com.erp.platform.web.ProblemResponses;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Second CSRF defence for cookie-based requests (SECURITY.md §10.3): unsafe methods must come from an
 * allowed origin, taken from {@code Origin} or, if absent, from {@code Referer}. Requests with an
 * {@code Authorization} header are exempt: bearer tokens are not ambient credentials.
 */
public class OriginVerificationFilter extends OncePerRequestFilter {

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    private final Set<String> allowedOrigins;
    private final ProblemResponses problems;
    private final SecurityAudit securityAudit;

    public OriginVerificationFilter(
            List<String> allowedOrigins, ProblemResponses problems, SecurityAudit securityAudit) {
        this.securityAudit = securityAudit;
        this.allowedOrigins = allowedOrigins.stream()
                .map(OriginVerificationFilter::normalize)
                .collect(Collectors.toUnmodifiableSet());
        this.problems = problems;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return SAFE_METHODS.contains(request.getMethod()) || ActorAuthenticationFilter.hasAuthorizationHeader(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String origin = request.getHeader("Origin");
        if (origin == null) {
            origin = originOf(request.getHeader("Referer"));
        }
        if (origin == null || !allowedOrigins.contains(normalize(origin))) {
            securityAudit.csrfRejected(request, origin == null ? "no Origin or Referer" : "origin not allowed");
            problems.write(
                    response,
                    problems.problem(
                            PlatformErrorCode.CSRF_INVALID, "The request origin is not allowed.", request, List.of()));
            return;
        }
        chain.doFilter(request, response);
    }

    static @Nullable String originOf(@Nullable String referer) {
        if (referer == null) {
            return null;
        }
        try {
            URI uri = URI.create(referer);
            if (uri.getScheme() == null || uri.getHost() == null) {
                return null;
            }
            return uri.getScheme() + "://" + uri.getHost() + (uri.getPort() == -1 ? "" : ":" + uri.getPort());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static String normalize(String origin) {
        String trimmed = origin.strip().toLowerCase(Locale.ROOT);
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }
}
