package com.erp.org.application;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Inputs of the department repository. */
public final class DepartmentCommands {

    public record Create(
            String code,
            String name,
            @Nullable UUID parentId,
            @Nullable UUID branchId) {}

    public record Update(
            String name, @Nullable UUID parentId, @Nullable UUID branchId, boolean active) {}

    private DepartmentCommands() {}
}
