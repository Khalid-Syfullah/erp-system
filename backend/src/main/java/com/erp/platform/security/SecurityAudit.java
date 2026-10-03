package com.erp.platform.security;

import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditPort;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Records rejected requests that SECURITY.md §8 requires in the audit log, in their own transaction
 * (the request itself fails): permission denials on mutating endpoints and CSRF/Origin rejections.
 * Auditing failures never change the response.
 */
public final class SecurityAudit {

    private static final Logger log = LoggerFactory.getLogger(SecurityAudit.class);
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    private final ObjectProvider<AuditPort> audit;

    public SecurityAudit(ObjectProvider<AuditPort> audit) {
        this.audit = audit;
    }

    public void permissionDenied(HttpServletRequest request, String reason) {
        if (!SAFE_METHODS.contains(request.getMethod())) {
            record("PERMISSION_DENIED", request, reason);
        }
    }

    public void csrfRejected(HttpServletRequest request, String reason) {
        record("CSRF_REJECTED", request, reason);
    }

    private void record(String action, HttpServletRequest request, String reason) {
        AuditPort port = audit.getIfAvailable();
        if (port == null) {
            return;
        }
        try {
            port.recordIndependently(AuditEvent.builder(action, "security")
                    .detail("method", request.getMethod())
                    .detail("path", request.getRequestURI())
                    .detail("reason", reason)
                    .build());
        } catch (RuntimeException e) {
            log.error("Could not audit {} for {} {}", action, request.getMethod(), request.getRequestURI(), e);
        }
    }
}
