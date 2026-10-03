package com.erp.admin.application;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** An audit log entry as exposed by the Administration module. */
public record AuditEntryView(
        UUID id,
        OffsetDateTime occurredAt,
        @Nullable UUID companyId,
        @Nullable UUID actorUserId,
        String actorType,
        @Nullable UUID apiTokenId,
        String action,
        String module,
        @Nullable String entityType,
        @Nullable UUID entityId,
        @Nullable String entityLabel,
        @Nullable String fromState,
        @Nullable String toState,
        @Nullable String changesJson,
        @Nullable String requestId) {}
