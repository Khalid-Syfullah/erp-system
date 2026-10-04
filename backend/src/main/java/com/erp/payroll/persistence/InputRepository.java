package com.erp.payroll.persistence;

import static com.erp.db.payroll.Tables.PAYROLL_INPUTS;

import com.erp.db.payroll.tables.records.PayrollInputsRecord;
import com.erp.payroll.application.PayrollCommands;
import com.erp.payroll.application.PayrollViews;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;

/** Period inputs (overtime, bonuses …) of the period's regular run or of an off-cycle run. */
@Repository
public class InputRepository {

    private final DSLContext dsl;

    public InputRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public UUID insert(UUID companyId, UUID periodId, PayrollCommands.Input c, UUID actor) {
        return dsl.insertInto(PAYROLL_INPUTS)
                .set(PAYROLL_INPUTS.COMPANY_ID, companyId)
                .set(PAYROLL_INPUTS.PAYROLL_PERIOD_ID, periodId)
                .set(PAYROLL_INPUTS.PAYROLL_RUN_ID, c.runId())
                .set(PAYROLL_INPUTS.EMPLOYEE_ID, c.employeeId())
                .set(PAYROLL_INPUTS.COMPONENT_ID, c.componentId())
                .set(PAYROLL_INPUTS.QUANTITY, c.quantity())
                .set(PAYROLL_INPUTS.AMOUNT, c.amount())
                .set(PAYROLL_INPUTS.NOTE, c.note())
                .set(PAYROLL_INPUTS.CREATED_BY, actor)
                .set(PAYROLL_INPUTS.UPDATED_BY, actor)
                .returning(PAYROLL_INPUTS.ID)
                .fetchSingle(PAYROLL_INPUTS.ID);
    }

    public boolean update(
            UUID companyId,
            UUID id,
            int expectedVersion,
            UUID actor,
            @Nullable BigDecimal quantity,
            @Nullable BigDecimal amount,
            @Nullable String note) {
        return dsl.update(PAYROLL_INPUTS)
                        .set(PAYROLL_INPUTS.QUANTITY, quantity)
                        .set(PAYROLL_INPUTS.AMOUNT, amount)
                        .set(PAYROLL_INPUTS.NOTE, note)
                        .set(PAYROLL_INPUTS.UPDATED_AT, OffsetDateTime.now())
                        .set(PAYROLL_INPUTS.UPDATED_BY, actor)
                        .set(PAYROLL_INPUTS.VERSION, expectedVersion + 1)
                        .where(PAYROLL_INPUTS.COMPANY_ID.eq(companyId))
                        .and(PAYROLL_INPUTS.ID.eq(id))
                        .and(PAYROLL_INPUTS.VERSION.eq(expectedVersion))
                        .execute()
                == 1;
    }

    public void delete(UUID companyId, UUID id) {
        dsl.deleteFrom(PAYROLL_INPUTS)
                .where(PAYROLL_INPUTS.COMPANY_ID.eq(companyId))
                .and(PAYROLL_INPUTS.ID.eq(id))
                .execute();
    }

    public Optional<PayrollViews.Input> find(UUID companyId, UUID periodId, UUID id) {
        return dsl.selectFrom(PAYROLL_INPUTS)
                .where(PAYROLL_INPUTS.COMPANY_ID.eq(companyId))
                .and(PAYROLL_INPUTS.PAYROLL_PERIOD_ID.eq(periodId))
                .and(PAYROLL_INPUTS.ID.eq(id))
                .fetchOptional(InputRepository::toView);
    }

    public List<PayrollViews.Input> forPeriod(UUID companyId, UUID periodId) {
        return dsl.selectFrom(PAYROLL_INPUTS)
                .where(PAYROLL_INPUTS.COMPANY_ID.eq(companyId))
                .and(PAYROLL_INPUTS.PAYROLL_PERIOD_ID.eq(periodId))
                .orderBy(PAYROLL_INPUTS.EMPLOYEE_ID, PAYROLL_INPUTS.CREATED_AT)
                .fetch(InputRepository::toView);
    }

    /** The inputs a run pays: the period's (regular run) or the run's own (off-cycle). */
    public List<PayrollViews.Input> forRun(UUID companyId, UUID periodId, @Nullable UUID offCycleRunId) {
        return dsl.selectFrom(PAYROLL_INPUTS)
                .where(PAYROLL_INPUTS.COMPANY_ID.eq(companyId))
                .and(PAYROLL_INPUTS.PAYROLL_PERIOD_ID.eq(periodId))
                .and(
                        offCycleRunId == null
                                ? PAYROLL_INPUTS.PAYROLL_RUN_ID.isNull()
                                : PAYROLL_INPUTS.PAYROLL_RUN_ID.eq(offCycleRunId))
                .orderBy(PAYROLL_INPUTS.EMPLOYEE_ID, PAYROLL_INPUTS.CREATED_AT)
                .fetch(InputRepository::toView);
    }

    static PayrollViews.Input toView(PayrollInputsRecord r) {
        return new PayrollViews.Input(
                r.getId(),
                r.getPayrollPeriodId(),
                r.getPayrollRunId(),
                r.getEmployeeId(),
                r.getComponentId(),
                r.getQuantity(),
                r.getAmount(),
                r.getNote(),
                r.getCreatedAt(),
                r.getVersion());
    }
}
