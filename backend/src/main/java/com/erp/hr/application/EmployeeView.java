package com.erp.hr.application;

import com.erp.hr.domain.EmployeeStatus;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The employee record. Sensitive values (date of birth, national ID) are never part of it: only
 * whether a date of birth is stored and the national ID's last four characters (SECURITY.md §7).
 */
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
        int version,
        @Nullable UUID userId,
        @Nullable String personalEmail,
        @Nullable String phone,
        @Nullable Address address,
        @Nullable String nationalIdLast4,
        boolean dateOfBirthSet) {

    public String displayName() {
        return (preferredName != null ? preferredName : firstName) + " " + lastName;
    }
}
