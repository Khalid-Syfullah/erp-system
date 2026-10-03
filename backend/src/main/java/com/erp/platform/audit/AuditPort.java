package com.erp.platform.audit;

/**
 * Port for the business audit trail (SECURITY.md §8, PRODUCT_SPEC.md §12.2), implemented by the
 * Administration module. The actor, request ID, client address and active company are taken from the
 * current {@code RequestContext}.
 */
public interface AuditPort {

    /** Records the event inside the caller's transaction: it commits or rolls back with the change. */
    void record(AuditEvent event);

    /**
     * Records the event in its own transaction, for security events that must persist even when the
     * surrounding work fails (failed logins, lockouts, denied access).
     */
    void recordIndependently(AuditEvent event);
}
