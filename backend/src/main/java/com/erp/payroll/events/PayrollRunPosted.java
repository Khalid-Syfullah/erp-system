package com.erp.payroll.events;

import com.erp.platform.events.DomainEvent;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * {@code payroll.run.posted} (ARCHITECTURE.md §7), schema version 1: published synchronously in the
 * posting transaction; Accounting books the payroll entry from it (PRODUCT_SPEC.md §8.6). Amounts are
 * in the run's currency (the company's base currency, ADR-039) and positive.
 *
 * @param lines amounts aggregated by component, department and branch
 * @param netPayTotal what the employees are owed (salaries payable)
 */
public record PayrollRunPosted(
        EventMetadata metadata,
        UUID runId,
        String number,
        UUID periodId,
        String runType,
        LocalDate accountingDate,
        String currencyCode,
        List<Line> lines,
        BigDecimal grossTotal,
        BigDecimal deductionTotal,
        BigDecimal employerContributionTotal,
        BigDecimal netPayTotal)
        implements DomainEvent {

    public static final String TYPE = "payroll.run.posted";
    public static final int SCHEMA_VERSION = 1;

    /** One aggregated amount; {@code kind} is EARNING, DEDUCTION or EMPLOYER_CONTRIBUTION. */
    public record Line(
            UUID componentId, String componentCode, String kind, UUID departmentId, UUID branchId, BigDecimal amount) {}
}
