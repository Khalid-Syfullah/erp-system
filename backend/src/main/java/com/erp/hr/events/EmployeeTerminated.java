package com.erp.hr.events;

import com.erp.platform.events.DomainEvent;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * {@code hr.employee.terminated} (ARCHITECTURE.md §7), schema version 1: published synchronously in
 * the termination transaction. Payroll ends the employee's compensation at the termination date so
 * the last regular run pays the final, prorated period.
 */
public record EmployeeTerminated(
        EventMetadata metadata,
        UUID employeeId,
        String employeeNumber,
        LocalDate terminationDate,
        @Nullable String reason)
        implements DomainEvent {

    public static final String TYPE = "hr.employee.terminated";
    public static final int SCHEMA_VERSION = 1;
}
