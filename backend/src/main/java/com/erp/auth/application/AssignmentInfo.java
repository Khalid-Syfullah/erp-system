package com.erp.auth.application;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** A role assignment; empty {@code branchIds} means all branches of the company. */
public record AssignmentInfo(
        UUID id,
        UUID userId,
        String userEmail,
        String userDisplayName,
        UUID roleId,
        String roleCode,
        UUID companyId,
        Set<UUID> branchIds,
        @Nullable LocalDate validFrom,
        @Nullable LocalDate validTo,
        OffsetDateTime createdAt) {}
