package com.erp.platform.web;

import com.erp.platform.security.PublicEndpoint;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Replaces Spring Boot's default {@code /error} page so that failures outside Spring MVC (servlet
 * filters, the container itself) also produce problem responses without internal details.
 */
@RestController
public class ProblemErrorController implements ErrorController {

    private static final Logger log = LoggerFactory.getLogger(ProblemErrorController.class);

    private final ProblemResponses problems;

    public ProblemErrorController(ProblemResponses problems) {
        this.problems = problems;
    }

    @PublicEndpoint
    @RequestMapping("${server.error.path:/error}")
    ResponseEntity<ApiProblem> error(HttpServletRequest request) {
        int status = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE) instanceof Integer code ? code : 500;
        if (request.getAttribute(RequestDispatcher.ERROR_EXCEPTION) instanceof Throwable failure) {
            log.error("Request failed outside the MVC exception handler", failure);
            status = 500;
        }
        PlatformErrorCode code = PlatformErrorCode.forStatus(status);
        String uri = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI) instanceof String path
                ? path
                : request.getRequestURI();
        ApiProblem problem =
                ApiProblem.of(code, code.title() + ".", uri, ProblemResponses.requestId(request), List.of());
        return problems.entity(problem);
    }
}
