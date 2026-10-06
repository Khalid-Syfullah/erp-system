package com.erp.reporting.application;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Read models of the Reporting module's own tables. */
public final class ReportingViews {

    private ReportingViews() {}

    /** Saved parameters of a report, private to the owner unless shared within the company. */
    public record SavedReport(
            UUID id,
            UUID ownerId,
            String reportCode,
            String name,
            Map<String, String> parameters,
            boolean shared,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            int version) {}

    /**
     * An export job. {@code branchScope} is the requester's branch scope when it was queued
     * ({@code null} = all branches); it is applied by the worker and never shown.
     */
    public record ExportJob(
            UUID id,
            UUID userId,
            String reportCode,
            Map<String, String> parameters,
            String format,
            @Nullable Set<UUID> branchScope,
            String status,
            @Nullable UUID fileId,
            @Nullable Long rowCount,
            @Nullable String errorCode,
            OffsetDateTime requestedAt,
            @Nullable OffsetDateTime startedAt,
            @Nullable OffsetDateTime completedAt,
            @Nullable OffsetDateTime expiresAt,
            int version) {}
}
