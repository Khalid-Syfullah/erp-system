package com.erp.org.application;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** A department as exposed by the Organization module. */
public record DepartmentView(
        UUID id,
        UUID companyId,
        String code,
        String name,
        @Nullable UUID parentId,
        @Nullable UUID branchId,
        boolean active,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        int version) {}
