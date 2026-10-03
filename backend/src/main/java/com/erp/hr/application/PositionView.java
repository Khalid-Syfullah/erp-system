package com.erp.hr.application;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** A position (designation / job title), optionally tied to one department. */
public record PositionView(
        UUID id,
        UUID companyId,
        String code,
        String title,
        @Nullable UUID departmentId,
        @Nullable String grade,
        boolean active,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        int version) {}
