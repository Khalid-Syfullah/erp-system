package com.erp.platform.audit;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * A business audit record (DATABASE.md §5.11).
 *
 * @param action UPPER_SNAKE_CASE verb, e.g. {@code LOGIN}, {@code CREATE}, {@code ROLE_CHANGE}
 * @param module owning module, e.g. {@code auth}
 * @param companyId the company the event belongs to; {@code null} for global events (falls back to
 *     the active company of the request when not set explicitly)
 * @param actingUserId the user who acted when the request has no authenticated actor yet (login,
 *     password reset, invitation acceptance); ignored when an actor is authenticated
 */
public record AuditEvent(
        String action,
        String module,
        @Nullable String entityType,
        @Nullable UUID entityId,
        @Nullable String entityLabel,
        @Nullable UUID companyId,
        @Nullable String fromState,
        @Nullable String toState,
        @Nullable UUID actingUserId,
        Map<String, AuditChange> changes) {

    private static final Pattern ACTION = Pattern.compile("^[A-Z][A-Z_]{1,49}$");
    private static final Pattern MODULE = Pattern.compile("^[a-z]{2,20}$");

    public AuditEvent {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(module, "module");
        if (!ACTION.matcher(action).matches() || !MODULE.matcher(module).matches()) {
            throw new IllegalArgumentException("Invalid audit action or module: " + action + "/" + module);
        }
        if (entityLabel != null && entityLabel.length() > 200) {
            entityLabel = entityLabel.substring(0, 200);
        }
        changes = changes == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(changes));
    }

    public static Builder builder(String action, String module) {
        return new Builder(action, module);
    }

    /** Fluent construction of audit events. */
    public static final class Builder {
        private final String action;
        private final String module;
        private String entityType;
        private UUID entityId;
        private String entityLabel;
        private UUID companyId;
        private String fromState;
        private String toState;
        private UUID actingUserId;
        private final Map<String, AuditChange> changes = new LinkedHashMap<>();

        private Builder(String action, String module) {
            this.action = action;
            this.module = module;
        }

        public Builder entity(String type, @Nullable UUID id, @Nullable String label) {
            this.entityType = type;
            this.entityId = id;
            this.entityLabel = label;
            return this;
        }

        public Builder company(@Nullable UUID company) {
            this.companyId = company;
            return this;
        }

        public Builder transition(@Nullable String from, @Nullable String to) {
            this.fromState = from;
            this.toState = to;
            return this;
        }

        /** Attributes an event of an anonymous request to the user who proved their identity in it. */
        public Builder actingUser(@Nullable UUID userId) {
            this.actingUserId = userId;
            return this;
        }

        public Builder change(String field, @Nullable Object oldValue, @Nullable Object newValue) {
            if (!Objects.equals(oldValue, newValue)) {
                changes.put(field, new AuditChange(oldValue, newValue));
            }
            return this;
        }

        /** Records that a restricted field changed without its values. */
        public Builder redactedChange(String field) {
            changes.put(field, AuditChange.redacted());
            return this;
        }

        public Builder detail(String field, @Nullable Object value) {
            changes.put(field, new AuditChange(null, value));
            return this;
        }

        public AuditEvent build() {
            return new AuditEvent(
                    action,
                    module,
                    entityType,
                    entityId,
                    entityLabel,
                    companyId,
                    fromState,
                    toState,
                    actingUserId,
                    changes);
        }
    }
}
