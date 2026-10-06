package com.erp.reporting.application;

import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * What a report run may see: the company (also enforced by RLS), the caller's branch scope
 * ({@code null} = all branches; for exports the requester's scope when the job was queued) and the
 * company's business date.
 */
public record ReportScope(UUID companyId, @Nullable Set<UUID> branches, LocalDate today) {

    public ReportScope {
        branches = branches == null ? null : Set.copyOf(branches);
    }

    public boolean restricted() {
        return branches != null;
    }
}
