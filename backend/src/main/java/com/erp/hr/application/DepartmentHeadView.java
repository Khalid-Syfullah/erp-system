package com.erp.hr.application;

import com.erp.hr.domain.EffectivePeriod;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** An employee heading a department for a period. */
public record DepartmentHeadView(
        UUID id,
        UUID companyId,
        UUID departmentId,
        UUID employeeId,
        LocalDate effectiveFrom,
        @Nullable LocalDate effectiveTo,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        int version) {

    public EffectivePeriod period() {
        return new EffectivePeriod(effectiveFrom, effectiveTo);
    }
}
