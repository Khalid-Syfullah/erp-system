package com.erp.hr.web;

import com.erp.hr.application.AssignmentView;
import com.erp.hr.application.EmploymentAssignmentService;
import com.erp.hr.application.HrCommands;
import com.erp.platform.web.ApiException;
import com.erp.platform.web.FieldViolation;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/** Request and response shapes shared by the HR controllers. */
final class HrRequests {

    static final String MERGE_PATCH = "application/merge-patch+json";

    record AssignmentRequest(
            @NotNull UUID branchId,
            @NotNull UUID departmentId,
            @Nullable UUID positionId,
            @Nullable UUID managerEmployeeId,

            @Pattern(regexp = "^(FULL_TIME|PART_TIME|CONTRACT|INTERN|TEMPORARY)$") @Nullable String employmentType,

            @DecimalMin("0.0001") @DecimalMax("1") @Nullable BigDecimal fte,
            @Nullable LocalDate effectiveFrom,
            @Nullable LocalDate effectiveTo) {

        HrCommands.Assignment toCommand() {
            BigDecimal value = fte == null ? BigDecimal.ONE : fte;
            if (value.stripTrailingZeros().scale() > EmploymentAssignmentService.FTE_SCALE) {
                throw ApiException.validationFailed(
                        "The assignment is invalid.",
                        List.of(FieldViolation.atPointer(
                                "/fte", "INVALID_VALUE", "must have at most 4 decimal places")));
            }
            return new HrCommands.Assignment(
                    branchId,
                    departmentId,
                    positionId,
                    managerEmployeeId,
                    employmentType == null ? "FULL_TIME" : employmentType,
                    value,
                    effectiveFrom,
                    effectiveTo);
        }
    }

    record AssignmentResponse(
            UUID id,
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

        static @Nullable AssignmentResponse from(@Nullable AssignmentView v) {
            if (v == null) {
                return null;
            }
            return new AssignmentResponse(
                    v.id(),
                    v.employeeId(),
                    v.branchId(),
                    v.departmentId(),
                    v.positionId(),
                    v.managerEmployeeId(),
                    v.employmentType(),
                    v.fte(),
                    v.effectiveFrom(),
                    v.effectiveTo(),
                    v.createdAt(),
                    v.updatedAt(),
                    v.version());
        }
    }

    /** Extracts the optional {@code asOf} date, which is not part of the generic list syntax. */
    record AsOf(@Nullable LocalDate date, MultiValueMap<String, String> remaining) {

        static AsOf from(MultiValueMap<String, String> parameters) {
            MultiValueMap<String, String> rest = new LinkedMultiValueMap<>(parameters);
            List<String> values = rest.remove("asOf");
            if (values == null || values.isEmpty()) {
                return new AsOf(null, rest);
            }
            try {
                if (values.size() > 1) {
                    throw new DateTimeParseException("repeated", values.toString(), 0);
                }
                return new AsOf(LocalDate.parse(values.getFirst()), rest);
            } catch (DateTimeParseException e) {
                throw ApiException.badRequest(
                        "Invalid asOf parameter.",
                        List.of(FieldViolation.atParameter("asOf", "INVALID_VALUE", "must be one date (yyyy-MM-dd)")));
            }
        }
    }

    private HrRequests() {}
}
