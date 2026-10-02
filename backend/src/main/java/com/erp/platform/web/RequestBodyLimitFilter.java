package com.erp.platform.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Rejects request bodies above the configured limit (SECURITY.md §6): by {@code Content-Length}
 * up front, and by counting bytes for chunked bodies. Multipart uploads are excluded; they get their
 * own limits with the file service.
 */
public class RequestBodyLimitFilter extends OncePerRequestFilter {

    private final long maxBytes;
    private final ProblemResponses problems;

    public RequestBodyLimitFilter(long maxBytes, ProblemResponses problems) {
        this.maxBytes = maxBytes;
        this.problems = problems;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String contentType = request.getContentType();
        return contentType != null && contentType.toLowerCase().startsWith(MediaType.MULTIPART_FORM_DATA_VALUE);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > maxBytes) {
            problems.write(
                    response,
                    problems.problem(
                            PlatformErrorCode.PAYLOAD_TOO_LARGE, "The request body is too large.", request, List.of()));
            return;
        }
        chain.doFilter(new LimitedRequest(request, maxBytes), response);
    }

    /** Thrown while reading a body that exceeds the limit; mapped to 413 by the exception handler. */
    public static final class PayloadTooLargeException extends IOException {
        private static final long serialVersionUID = 1L;

        PayloadTooLargeException() {
            super("Request body exceeds the configured limit");
        }
    }

    private static final class LimitedRequest extends HttpServletRequestWrapper {
        private final long maxBytes;
        private ServletInputStream stream;

        LimitedRequest(HttpServletRequest request, long maxBytes) {
            super(request);
            this.maxBytes = maxBytes;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (stream == null) {
                stream = new LimitedInputStream(super.getInputStream(), maxBytes);
            }
            return stream;
        }
    }

    private static final class LimitedInputStream extends ServletInputStream {
        private final ServletInputStream delegate;
        private final long maxBytes;
        private long count;

        LimitedInputStream(ServletInputStream delegate, long maxBytes) {
            this.delegate = delegate;
            this.maxBytes = maxBytes;
        }

        @Override
        public int read() throws IOException {
            int b = delegate.read();
            if (b >= 0) {
                count(1);
            }
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = delegate.read(buffer, offset, length);
            if (read > 0) {
                count(read);
            }
            return read;
        }

        private void count(int bytes) throws PayloadTooLargeException {
            count += bytes;
            if (count > maxBytes) {
                throw new PayloadTooLargeException();
            }
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener listener) {
            delegate.setReadListener(listener);
        }
    }
}
