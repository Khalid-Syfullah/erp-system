package com.erp.hr.application;

import com.erp.hr.domain.EmployeeStatus;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** The core employee record (no sensitive personal data in Phase 4). */
public record EmployeeView(
        UUID id,
        UUID companyId,
        String employeeNumber,
        String firstName,
        String lastName,
        @Nullable String preferredName,
        @Nullable String workEmail,
        LocalDate hireDate,
        @Nullable LocalDate terminationDate,
        @Nullable String terminationReason,
        EmployeeStatus status,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        int version) {}
