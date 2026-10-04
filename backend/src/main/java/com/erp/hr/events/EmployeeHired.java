package com.erp.hr.events;

import com.erp.platform.events.DomainEvent;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * {@code hr.employee.hired} (ARCHITECTURE.md §7), schema version 1: published in the transaction
 * that creates the employee; branch and department come from the initial assignment, if any.
 */
public record EmployeeHired(
        EventMetadata metadata,
        UUID employeeId,
        String employeeNumber,
        LocalDate hireDate,
        @Nullable UUID branchId,
        @Nullable UUID departmentId)
        implements DomainEvent {

    public static final String TYPE = "hr.employee.hired";
    public static final int SCHEMA_VERSION = 1;
}
