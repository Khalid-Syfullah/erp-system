package com.erp.platform.security;

import com.erp.platform.config.ErpProperties;
import com.erp.platform.web.PlatformErrorCode;
import com.erp.platform.web.ProblemResponses;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Applies {@link FixedWindowRateLimiter} budgets per session user, per API token or per anonymous
 * client address, with IETF RateLimit headers and {@code Retry-After} on 429 (API.md §13).
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private final FixedWindowRateLimiter limiter;
    private final ErpProperties.RateLimits limits;
    private final ProblemResponses problems;

    public RateLimitFilter(FixedWindowRateLimiter limiter, ErpProperties.RateLimits limits, ProblemResponses problems) {
        this.limiter = limiter;
        this.limits = limits;
        this.problems = problems;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !limits.enabled();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String key;
        int limit;
        if (authentication instanceof ActorAuthentication actorAuthentication) {
            AuthenticatedActor actor = actorAuthentication.actor();
            if (actor.type() == ActorType.API_TOKEN) {
                key = "token:" + actor.credentialId();
                limit = actor.rateLimitPerMinute() != null ? actor.rateLimitPerMinute() : limits.tokenPerMinute();
            } else {
                key = "user:" + actor.userId();
                limit = limits.sessionPerMinute();
            }
        } else {
            key = "ip:" + request.getRemoteAddr();
            limit = limits.anonymousPerMinute();
        }
        FixedWindowRateLimiter.Decision decision = limiter.tryAcquire(key, limit);
        response.setHeader("RateLimit-Limit", Integer.toString(decision.limit()));
        response.setHeader("RateLimit-Remaining", Integer.toString(decision.remaining()));
        response.setHeader("RateLimit-Reset", Long.toString(decision.secondsUntilReset()));
        if (!decision.allowed()) {
            response.setHeader("Retry-After", Long.toString(decision.secondsUntilReset()));
            problems.write(
                    response,
                    problems.problem(
                            PlatformErrorCode.RATE_LIMITED,
                            "Too many requests. Retry after " + decision.secondsUntilReset() + " seconds.",
                            request,
                            List.of()));
            return;
        }
        chain.doFilter(request, response);
    }
}
