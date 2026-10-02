package com.erp.platform.web;

import com.erp.platform.context.CurrentContext;
import com.erp.platform.context.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Builds and writes problem responses consistently for MVC handlers, servlet filters, Spring
 * Security handlers and the {@code /error} fallback.
 */
@Component
public class ProblemResponses {

    private final JsonMapper jsonMapper;

    public ProblemResponses(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    public ApiProblem problem(
            ErrorCode code, @Nullable String detail, HttpServletRequest request, List<FieldViolation> errors) {
        return ApiProblem.of(code, detail, request.getRequestURI(), requestId(request), errors);
    }

    public ResponseEntity<ApiProblem> entity(ApiProblem problem) {
        return ResponseEntity.status(problem.status())
                .header("Content-Type", ApiProblem.MEDIA_TYPE)
                .body(problem);
    }

    /** Writes a problem directly to the servlet response (outside Spring MVC). */
    public void write(HttpServletResponse response, ApiProblem problem) throws IOException {
        response.setStatus(problem.status());
        response.setContentType(ApiProblem.MEDIA_TYPE);
        response.setCharacterEncoding("UTF-8");
        jsonMapper.writeValue(response.getOutputStream(), problem);
    }

    /**
     * The request ID from the current context, or from the response header set by
     * {@link RequestLoggingFilter} (error dispatches run after the context is cleared).
     */
    static @Nullable String requestId(HttpServletRequest request) {
        String fromContext = CurrentContext.get().map(RequestContext::requestId).orElse(null);
        if (fromContext != null) {
            return fromContext;
        }
        Object attribute = request.getAttribute(RequestLoggingFilter.REQUEST_ID_ATTRIBUTE);
        return attribute instanceof String id ? id : null;
    }
}
