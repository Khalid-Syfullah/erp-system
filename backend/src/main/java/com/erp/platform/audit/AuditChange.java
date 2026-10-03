package com.erp.platform.audit;

import org.jspecify.annotations.Nullable;

/** One field change; restricted values are replaced by {@link #REDACTED} before they get here. */
public record AuditChange(
        @Nullable Object oldValue, @Nullable Object newValue) {

    public static final String REDACTED = "***";

    /** A change to a restricted field: records that it changed, never the values. */
    public static AuditChange redacted() {
        return new AuditChange(REDACTED, REDACTED);
    }
}
