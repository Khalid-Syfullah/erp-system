package com.erp.payroll.application;

import com.erp.payroll.domain.RunStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Read models of the Payroll module. */
public final class PayrollViews {

    public record Settings(String prorationBasis, int version) {}

    public record Component(
            UUID id,
            String code,
            String name,
            String kind,
            String calculation,
            @Nullable BigDecimal defaultRate,
            @Nullable BigDecimal defaultAmount,
            boolean taxable,
            @Nullable String statutoryRuleCode,
            int sequence,
            boolean active,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            int version) {}

    public record StructureComponent(
            UUID componentId,
            String componentCode,
            @Nullable BigDecimal rate,
            @Nullable BigDecimal amount) {}

    public record Structure(
            UUID id,
            String code,
            String name,
            boolean active,
            List<StructureComponent> components,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            int version) {}

    public record Schedule(
            UUID id,
            String code,
            String name,
            String frequency,
            String currencyCode,
            @Nullable LocalDate anchorDate,
            int payDayOffset,
            boolean active,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            int version) {}

    public record Period(
            UUID id,
            UUID payScheduleId,
            LocalDate startDate,
            LocalDate endDate,
            LocalDate payDate,
            String status,
            int version) {}

    public record Override(
            UUID componentId,
            @Nullable BigDecimal rate,
            @Nullable BigDecimal amount) {}

    public record Compensation(
            UUID id,
            UUID employeeId,
            UUID payScheduleId,
            UUID salaryStructureId,
            BigDecimal baseAmount,
            String currencyCode,
            LocalDate effectiveFrom,
            @Nullable LocalDate effectiveTo,
            List<Override> overrides,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            int version) {}

    public record Input(
            UUID id,
            UUID periodId,
            @Nullable UUID runId,
            UUID employeeId,
            UUID componentId,
            @Nullable BigDecimal quantity,
            @Nullable BigDecimal amount,
            @Nullable String note,
            OffsetDateTime createdAt,
            int version) {}

    public record Run(
            UUID id,
            @Nullable String number,
            UUID periodId,
            String runType,
            @Nullable String description,
            RunStatus status,
            LocalDate accountingDate,
            String currencyCode,
            int employeeCount,
            BigDecimal grossTotal,
            BigDecimal deductionTotal,
            BigDecimal employerContributionTotal,
            BigDecimal netTotal,
            @Nullable UUID calculationRequestedBy,
            @Nullable OffsetDateTime calculatedAt,
            @Nullable UUID approvedBy,
            @Nullable OffsetDateTime approvedAt,
            @Nullable OffsetDateTime postedAt,
            @Nullable UUID postedBy,
            @Nullable OffsetDateTime paidAt,
            @Nullable LocalDate paymentDate,
            @Nullable UUID paymentBankAccountId,
            OffsetDateTime createdAt,
            @Nullable UUID createdBy,
            OffsetDateTime updatedAt,
            int version) {}

    public record Issue(UUID id, @Nullable UUID employeeId, String code, String message) {}

    public record Payslip(
            UUID id,
            UUID runId,
            UUID employeeId,
            String employeeNumber,
            String employeeName,
            UUID branchId,
            UUID departmentId,
            @Nullable String positionTitle,
            int daysPaid,
            int daysInPeriod,
            BigDecimal baseAmount,
            BigDecimal taxableGross,
            BigDecimal grossAmount,
            BigDecimal deductionAmount,
            BigDecimal employerContributionAmount,
            BigDecimal netAmount,
            String currencyCode,
            @Nullable UUID fileId,
            int version) {}

    public record PayslipLine(
            UUID componentId,
            String componentCode,
            String componentName,
            String kind,
            boolean taxable,
            @Nullable BigDecimal quantity,
            @Nullable BigDecimal rate,
            BigDecimal amount,
            int sequence) {}

    private PayrollViews() {}
}
