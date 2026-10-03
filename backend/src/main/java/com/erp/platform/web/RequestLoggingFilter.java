package com.erp.platform.web;

import com.erp.platform.context.CurrentContext;
import com.erp.platform.context.RequestContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * First filter of every request: establishes the request ID (accepting a well-formed
 * {@code X-Request-Id} from the caller, generating one otherwise), the {@link RequestContext}, the
 * logging MDC, and writes one structured access-log line per request.
 *
 * <p>Logged: method, path (never the query string, which may carry search terms), status, duration,
 * client address. Never logged: headers, cookies, bodies.
 */
public class RequestLoggingFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String MDC_REQUEST_ID = "request_id";
    public static final String MDC_USER_ID = "user_id";
    public static final String MDC_COMPANY_ID = "company_id";
    static final String REQUEST_ID_ATTRIBUTE = RequestLoggingFilter.class.getName() + ".requestId";

    private static final Pattern ACCEPTED_REQUEST_ID = Pattern.compile("^[A-Za-z0-9._:-]{8,128}$");
    private static final Logger accessLog = LoggerFactory.getLogger("com.erp.access");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = requestId(request.getHeader(REQUEST_ID_HEADER));
        request.setAttribute(REQUEST_ID_ATTRIBUTE, requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);
        MDC.put(MDC_REQUEST_ID, requestId);
        CurrentContext.set(RequestContext.forRequest(requestId, request.getRemoteAddr(), userAgent(request)));
        long start = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            if (!request.getRequestURI().startsWith("/actuator")) {
                accessLog
                        .atInfo()
                        .setMessage("{} {} -> {} ({} ms)")
                        .addArgument(request.getMethod())
                        .addArgument(request.getRequestURI())
                        .addArgument(response.getStatus())
                        .addArgument(durationMs)
                        .addKeyValue("http.request.method", request.getMethod())
                        .addKeyValue("url.path", request.getRequestURI())
                        .addKeyValue("http.response.status_code", response.getStatus())
                        .addKeyValue("event.duration_ms", durationMs)
                        .addKeyValue("client.address", request.getRemoteAddr())
                        .log();
            }
            CurrentContext.clear();
            MDC.remove(MDC_REQUEST_ID);
            MDC.remove(MDC_USER_ID);
            MDC.remove(MDC_COMPANY_ID);
        }
    }

    private static String userAgent(HttpServletRequest request) {
        String agent = request.getHeader("User-Agent");
        if (agent == null) {
            return null;
        }
        String printable = agent.replaceAll("\\p{Cntrl}", "");
        return printable.length() > 512 ? printable.substring(0, 512) : printable;
    }

    static String requestId(String supplied) {
        if (supplied != null && ACCEPTED_REQUEST_ID.matcher(supplied).matches()) {
            return supplied;
        }
        return UUID.randomUUID().toString();
    }
}
