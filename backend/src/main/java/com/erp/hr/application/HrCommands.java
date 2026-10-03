package com.erp.hr.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Inputs of the HR services and repositories. */
public final class HrCommands {

    public record Position(
            String code,
            String title,
            @Nullable UUID departmentId,
            @Nullable String grade,
            boolean active) {}

    public record Employee(
            String employeeNumber,
            String firstName,
            String lastName,
            @Nullable String preferredName,
            @Nullable String workEmail,
            LocalDate hireDate) {}

    /** {@code effectiveFrom == null} means "from the hire date" (initial assignment only). */
    public record Assignment(
            UUID branchId,
            UUID departmentId,
            @Nullable UUID positionId,
            @Nullable UUID managerEmployeeId,
            String employmentType,
            BigDecimal fte,
            @Nullable LocalDate effectiveFrom,
            @Nullable LocalDate effectiveTo) {}

    public record DepartmentHead(
            UUID departmentId,
            UUID employeeId,
            LocalDate effectiveFrom,
            @Nullable LocalDate effectiveTo) {}

    private HrCommands() {}
}
