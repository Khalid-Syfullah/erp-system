package com.erp.payroll.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Inputs of the Payroll services and repositories. */
public final class PayrollCommands {

    public record Component(
            String code,
            String name,
            String kind,
            String calculation,
            @Nullable BigDecimal defaultRate,
            @Nullable BigDecimal defaultAmount,
            boolean taxable,
            @Nullable String statutoryRuleCode,
            int sequence,
            boolean active) {}

    public record StructureLine(
            UUID componentId,
            @Nullable BigDecimal rate,
            @Nullable BigDecimal amount) {}

    public record Schedule(
            String code,
            String name,
            String frequency,
            String currencyCode,
            @Nullable LocalDate anchorDate,
            int payDayOffset) {}

    public record Compensation(
            UUID employeeId,
            UUID payScheduleId,
            UUID salaryStructureId,
            BigDecimal baseAmount,
            LocalDate effectiveFrom,
            @Nullable LocalDate effectiveTo,
            List<StructureLine> overrides) {}

    public record Input(
            UUID employeeId,
            UUID componentId,
            @Nullable UUID runId,
            @Nullable BigDecimal quantity,
            @Nullable BigDecimal amount,
            @Nullable String note) {}

    private PayrollCommands() {}
}
