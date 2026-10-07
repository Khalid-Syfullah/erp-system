package com.erp.payroll.persistence;

import static com.erp.db.payroll.Tables.EMPLOYEE_COMPENSATIONS;
import static com.erp.db.payroll.Tables.EMPLOYEE_COMPONENT_OVERRIDES;

import com.erp.db.payroll.tables.records.EmployeeCompensationsRecord;
import com.erp.payroll.application.PayrollCommands;
import com.erp.payroll.application.PayrollViews;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;

/** Effective-dated employee compensations and their component overrides (DATABASE.md §5.10). */
@Repository
public class CompensationRepository {

    private final DSLContext dsl;

    public CompensationRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public UUID insert(UUID companyId, PayrollCommands.Compensation c, String currencyCode, UUID actor) {
        UUID id = dsl.insertInto(EMPLOYEE_COMPENSATIONS)
                .set(EMPLOYEE_COMPENSATIONS.COMPANY_ID, companyId)
                .set(EMPLOYEE_COMPENSATIONS.EMPLOYEE_ID, c.employeeId())
                .set(EMPLOYEE_COMPENSATIONS.PAY_SCHEDULE_ID, c.payScheduleId())
                .set(EMPLOYEE_COMPENSATIONS.SALARY_STRUCTURE_ID, c.salaryStructureId())
                .set(EMPLOYEE_COMPENSATIONS.BASE_AMOUNT, c.baseAmount())
                .set(EMPLOYEE_COMPENSATIONS.CURRENCY_CODE, currencyCode)
                .set(EMPLOYEE_COMPENSATIONS.EFFECTIVE_FROM, c.effectiveFrom())
                .set(EMPLOYEE_COMPENSATIONS.EFFECTIVE_TO, c.effectiveTo())
                .set(EMPLOYEE_COMPENSATIONS.CREATED_BY, actor)
                .set(EMPLOYEE_COMPENSATIONS.UPDATED_BY, actor)
                .returning(EMPLOYEE_COMPENSATIONS.ID)
                .fetchSingle(EMPLOYEE_COMPENSATIONS.ID);
        for (PayrollCommands.StructureLine o : c.overrides()) {
            dsl.insertInto(EMPLOYEE_COMPONENT_OVERRIDES)
                    .set(EMPLOYEE_COMPONENT_OVERRIDES.COMPANY_ID, companyId)
                    .set(EMPLOYEE_COMPONENT_OVERRIDES.EMPLOYEE_COMPENSATION_ID, id)
                    .set(EMPLOYEE_COMPONENT_OVERRIDES.COMPONENT_ID, o.componentId())
                    .set(EMPLOYEE_COMPONENT_OVERRIDES.RATE, o.rate())
                    .set(EMPLOYEE_COMPONENT_OVERRIDES.AMOUNT, o.amount())
                    .set(EMPLOYEE_COMPONENT_OVERRIDES.CREATED_BY, actor)
                    .execute();
        }
        return id;
    }

    public Optional<PayrollViews.Compensation> find(UUID companyId, UUID id) {
        return dsl.selectFrom(EMPLOYEE_COMPENSATIONS)
                .where(EMPLOYEE_COMPENSATIONS.COMPANY_ID.eq(companyId))
                .and(EMPLOYEE_COMPENSATIONS.ID.eq(id))
                .fetchOptional(r -> toView(r, overrides(companyId, List.of(id)).getOrDefault(id, List.of())));
    }

    public List<PayrollViews.Compensation> forEmployee(UUID companyId, UUID employeeId) {
        return forEmployee(companyId, employeeId, false);
    }

    /** The employee's compensations, locked for a change. */
    public List<PayrollViews.Compensation> lockForEmployee(UUID companyId, UUID employeeId) {
        return forEmployee(companyId, employeeId, true);
    }

    private List<PayrollViews.Compensation> forEmployee(UUID companyId, UUID employeeId, boolean lock) {
        var select = dsl.selectFrom(EMPLOYEE_COMPENSATIONS)
                .where(EMPLOYEE_COMPENSATIONS.COMPANY_ID.eq(companyId))
                .and(EMPLOYEE_COMPENSATIONS.EMPLOYEE_ID.eq(employeeId))
                .orderBy(EMPLOYEE_COMPENSATIONS.EFFECTIVE_FROM);
        List<EmployeeCompensationsRecord> rows = lock ? select.forNoKeyUpdate().fetch() : select.fetch();
        Map<UUID, List<PayrollViews.Override>> overrides = overrides(
                companyId, rows.stream().map(EmployeeCompensationsRecord::getId).toList());
        return rows.stream()
                .map(r -> toView(r, overrides.getOrDefault(r.getId(), List.of())))
                .toList();
    }

    /** Compensations on the schedule overlapping the period (calculation), with overrides. */
    public List<PayrollViews.Compensation> forPeriod(
            UUID companyId, UUID scheduleId, LocalDate from, LocalDate to, @Nullable Collection<UUID> employeeIds) {
        var condition = EMPLOYEE_COMPENSATIONS
                .COMPANY_ID
                .eq(companyId)
                .and(EMPLOYEE_COMPENSATIONS.PAY_SCHEDULE_ID.eq(scheduleId))
                .and(EMPLOYEE_COMPENSATIONS.EFFECTIVE_FROM.le(to))
                .and(EMPLOYEE_COMPENSATIONS.EFFECTIVE_TO.isNull().or(EMPLOYEE_COMPENSATIONS.EFFECTIVE_TO.ge(from)));
        if (employeeIds != null) {
            condition = condition.and(EMPLOYEE_COMPENSATIONS.EMPLOYEE_ID.in(employeeIds));
        }
        List<EmployeeCompensationsRecord> rows = dsl.selectFrom(EMPLOYEE_COMPENSATIONS)
                .where(condition)
                .orderBy(EMPLOYEE_COMPENSATIONS.EMPLOYEE_ID, EMPLOYEE_COMPENSATIONS.EFFECTIVE_FROM)
                .fetch();
        Map<UUID, List<PayrollViews.Override>> overrides = overrides(
                companyId, rows.stream().map(EmployeeCompensationsRecord::getId).toList());
        return rows.stream()
                .map(r -> toView(r, overrides.getOrDefault(r.getId(), List.of())))
                .toList();
    }

    public boolean updateEnd(
            UUID companyId, UUID id, int expectedVersion, @Nullable UUID actor, @Nullable LocalDate end) {
        return dsl.update(EMPLOYEE_COMPENSATIONS)
                        .set(EMPLOYEE_COMPENSATIONS.EFFECTIVE_TO, end)
                        .set(EMPLOYEE_COMPENSATIONS.UPDATED_AT, OffsetDateTime.now())
                        .set(EMPLOYEE_COMPENSATIONS.UPDATED_BY, actor)
                        .set(EMPLOYEE_COMPENSATIONS.VERSION, expectedVersion + 1)
                        .where(EMPLOYEE_COMPENSATIONS.COMPANY_ID.eq(companyId))
                        .and(EMPLOYEE_COMPENSATIONS.ID.eq(id))
                        .and(EMPLOYEE_COMPENSATIONS.VERSION.eq(expectedVersion))
                        .execute()
                == 1;
    }

    public void delete(UUID companyId, UUID id) {
        dsl.deleteFrom(EMPLOYEE_COMPENSATIONS)
                .where(EMPLOYEE_COMPENSATIONS.COMPANY_ID.eq(companyId))
                .and(EMPLOYEE_COMPENSATIONS.ID.eq(id))
                .execute();
    }

    private Map<UUID, List<PayrollViews.Override>> overrides(UUID companyId, Collection<UUID> compensationIds) {
        if (compensationIds.isEmpty()) {
            return Map.of();
        }
        return dsl.selectFrom(EMPLOYEE_COMPONENT_OVERRIDES)
                .where(EMPLOYEE_COMPONENT_OVERRIDES.COMPANY_ID.eq(companyId))
                .and(EMPLOYEE_COMPONENT_OVERRIDES.EMPLOYEE_COMPENSATION_ID.in(compensationIds))
                .fetchGroups(
                        r -> r.getEmployeeCompensationId(),
                        r -> new PayrollViews.Override(r.getComponentId(), r.getRate(), r.getAmount()));
    }

    private static PayrollViews.Compensation toView(
            EmployeeCompensationsRecord r, List<PayrollViews.Override> overrides) {
        return new PayrollViews.Compensation(
                r.getId(),
                r.getEmployeeId(),
                r.getPayScheduleId(),
                r.getSalaryStructureId(),
                r.getBaseAmount(),
                r.getCurrencyCode(),
                r.getEffectiveFrom(),
                r.getEffectiveTo(),
                overrides,
                r.getCreatedAt(),
                r.getUpdatedAt(),
                r.getVersion());
    }
}
