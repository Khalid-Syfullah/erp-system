package com.erp.org.api;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** A department as seen by other modules; {@code branchId} is set when the department belongs to one branch. */
public record DepartmentSummary(
        UUID id,
        String code,
        String name,
        @Nullable UUID parentId,
        @Nullable UUID branchId,
        boolean active) {}
