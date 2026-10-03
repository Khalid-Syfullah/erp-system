package com.erp.hr.application;

import com.erp.hr.domain.EffectivePeriod;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** An effective-dated employment assignment: where, in which unit and role, and to whom an employee reports. */
public record AssignmentView(
        UUID id,
        UUID companyId,
        UUID employeeId,
        UUID branchId,
        UUID departmentId,
        @Nullable UUID positionId,
        @Nullable UUID managerEmployeeId,
        String employmentType,
        BigDecimal fte,
        LocalDate effectiveFrom,
        @Nullable LocalDate effectiveTo,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        int version) {

    public EffectivePeriod period() {
        return new EffectivePeriod(effectiveFrom, effectiveTo);
    }
}
