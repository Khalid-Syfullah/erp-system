package com.erp.platform.security;

import com.erp.platform.web.ApiProblem;
import com.erp.platform.web.PlatformErrorCode;
import com.erp.platform.web.ProblemResponses;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;

/** Renders Spring Security filter-chain rejections as problem responses (401 / 403). */
public class ProblemSecurityHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ProblemResponses problems;

    public ProblemSecurityHandlers(ProblemResponses problems) {
        this.problems = problems;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException ex)
            throws IOException {
        ApiProblem problem = problems.problem(
                PlatformErrorCode.UNAUTHENTICATED,
                "Authentication is required to access this resource.",
                request,
                List.of());
        problems.write(response, problem);
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex)
            throws IOException {
        ApiProblem problem = ex instanceof CsrfException
                ? problems.problem(
                        PlatformErrorCode.CSRF_INVALID,
                        "The request is missing a valid CSRF token (X-CSRF-Token header).",
                        request,
                        List.of())
                : problems.problem(
                        PlatformErrorCode.FORBIDDEN,
                        "You do not have permission to perform this action.",
                        request,
                        List.of());
        problems.write(response, problem);
    }
}
