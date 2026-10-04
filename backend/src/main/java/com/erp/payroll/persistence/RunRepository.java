package com.erp.payroll.persistence;

import static com.erp.db.payroll.Tables.PAYROLL_RUNS;
import static com.erp.db.payroll.Tables.PAYROLL_RUN_ISSUES;

import com.erp.db.payroll.tables.records.PayrollRunsRecord;
import com.erp.payroll.application.PayrollListings;
import com.erp.payroll.application.PayrollViews;
import com.erp.payroll.domain.RunStatus;
import com.erp.platform.jooq.KeysetPaginator;
import com.erp.platform.jooq.ListBinding;
import com.erp.platform.web.paging.ListQuery;
import com.erp.platform.web.paging.PageResponse;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;

/** Payroll runs and their calculation issues (DATABASE.md §5.10). */
@Repository
public class RunRepository {

    private static final ListBinding BINDING = ListBinding.builder(PayrollListings.RUNS)
            .field("accountingDate", PAYROLL_RUNS.ACCOUNTING_DATE)
            .field("createdAt", PAYROLL_RUNS.CREATED_AT)
            .field("payrollPeriodId", PAYROLL_RUNS.PAYROLL_PERIOD_ID)
            .field("status", PAYROLL_RUNS.STATUS)
            .field("runType", PAYROLL_RUNS.RUN_TYPE)
            .tiebreaker(PAYROLL_RUNS.ID)
            .build();

    /** Totals of a calculated run. */
    public record Totals(
            int employees, BigDecimal gross, BigDecimal deductions, BigDecimal contributions, BigDecimal net) {
        public static final Totals ZERO =
                new Totals(0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    private final DSLContext dsl;
    private final KeysetPaginator paginator;

    public RunRepository(DSLContext dsl, KeysetPaginator paginator) {
        this.dsl = dsl;
        this.paginator = paginator;
    }

    public UUID insert(
            UUID companyId,
            UUID periodId,
            String runType,
            @Nullable String description,
            LocalDate accountingDate,
            String currencyCode,
            UUID actor) {
        return dsl.insertInto(PAYROLL_RUNS)
                .set(PAYROLL_RUNS.COMPANY_ID, companyId)
                .set(PAYROLL_RUNS.PAYROLL_PERIOD_ID, periodId)
                .set(PAYROLL_RUNS.RUN_TYPE, runType)
                .set(PAYROLL_RUNS.DESCRIPTION, description)
                .set(PAYROLL_RUNS.ACCOUNTING_DATE, accountingDate)
                .set(PAYROLL_RUNS.CURRENCY_CODE, currencyCode)
                .set(PAYROLL_RUNS.CREATED_BY, actor)
                .set(PAYROLL_RUNS.UPDATED_BY, actor)
                .returning(PAYROLL_RUNS.ID)
                .fetchSingle(PAYROLL_RUNS.ID);
    }

    public Optional<PayrollViews.Run> find(UUID companyId, UUID id) {
        return dsl.selectFrom(PAYROLL_RUNS)
                .where(PAYROLL_RUNS.COMPANY_ID.eq(companyId))
                .and(PAYROLL_RUNS.ID.eq(id))
                .fetchOptional(RunRepository::toView);
    }

    public Optional<PayrollViews.Run> lock(UUID companyId, UUID id) {
        return dsl.selectFrom(PAYROLL_RUNS)
                .where(PAYROLL_RUNS.COMPANY_ID.eq(companyId))
                .and(PAYROLL_RUNS.ID.eq(id))
                .forNoKeyUpdate()
                .fetchOptional(RunRepository::toView);
    }

    public PageResponse<PayrollViews.Run> list(UUID companyId, ListQuery query) {
        return paginator.fetch(
                dsl, PAYROLL_RUNS, PAYROLL_RUNS.COMPANY_ID.eq(companyId), query, BINDING, RunRepository::toView);
    }

    /** The company's oldest run waiting for calculation. */
    public Optional<UUID> queued(UUID companyId) {
        return dsl.select(PAYROLL_RUNS.ID)
                .from(PAYROLL_RUNS)
                .where(PAYROLL_RUNS.COMPANY_ID.eq(companyId))
                .and(PAYROLL_RUNS.STATUS.eq(RunStatus.CALCULATING.name()))
                .orderBy(PAYROLL_RUNS.UPDATED_AT)
                .limit(1)
                .fetchOptional(PAYROLL_RUNS.ID);
    }

    /** The run, locked for its calculation; empty when another worker holds it. */
    public Optional<PayrollViews.Run> claim(UUID companyId, UUID id) {
        return dsl.selectFrom(PAYROLL_RUNS)
                .where(PAYROLL_RUNS.COMPANY_ID.eq(companyId))
                .and(PAYROLL_RUNS.ID.eq(id))
                .forNoKeyUpdate()
                .skipLocked()
                .fetchOptional(RunRepository::toView);
    }

    /** Runs of the period in the given states (input changes, posting). */
    public List<PayrollViews.Run> forPeriod(UUID companyId, UUID periodId) {
        return dsl.selectFrom(PAYROLL_RUNS)
                .where(PAYROLL_RUNS.COMPANY_ID.eq(companyId))
                .and(PAYROLL_RUNS.PAYROLL_PERIOD_ID.eq(periodId))
                .forNoKeyUpdate()
                .fetch(RunRepository::toView);
    }

    /** Moves the run to {@code status}, setting the given columns too; optimistic on the version. */
    public boolean transition(
            UUID companyId, UUID id, int expectedVersion, RunStatus status, @Nullable UUID actor, Object... columns) {
        var update = dsl.update(PAYROLL_RUNS)
                .set(PAYROLL_RUNS.STATUS, status.name())
                .set(PAYROLL_RUNS.UPDATED_AT, OffsetDateTime.now())
                .set(PAYROLL_RUNS.UPDATED_BY, actor)
                .set(PAYROLL_RUNS.VERSION, expectedVersion + 1);
        for (int i = 0; i < columns.length; i += 2) {
            @SuppressWarnings("unchecked")
            Field<Object> field = (Field<Object>) columns[i];
            update = update.set(field, columns[i + 1]);
        }
        return update.where(PAYROLL_RUNS.COMPANY_ID.eq(companyId))
                        .and(PAYROLL_RUNS.ID.eq(id))
                        .and(PAYROLL_RUNS.VERSION.eq(expectedVersion))
                        .execute()
                == 1;
    }

    public static Object[] totals(Totals t) {
        return new Object[] {
            PAYROLL_RUNS.EMPLOYEE_COUNT, t.employees(),
            PAYROLL_RUNS.GROSS_TOTAL, t.gross(),
            PAYROLL_RUNS.DEDUCTION_TOTAL, t.deductions(),
            PAYROLL_RUNS.EMPLOYER_CONTRIBUTION_TOTAL, t.contributions(),
            PAYROLL_RUNS.NET_TOTAL, t.net()
        };
    }

    public static Field<?> field(String name) {
        return switch (name) {
            case "calculationRequestedBy" -> PAYROLL_RUNS.CALCULATION_REQUESTED_BY;
            case "calculatedAt" -> PAYROLL_RUNS.CALCULATED_AT;
            case "calculatedBy" -> PAYROLL_RUNS.CALCULATED_BY;
            case "approvedAt" -> PAYROLL_RUNS.APPROVED_AT;
            case "approvedBy" -> PAYROLL_RUNS.APPROVED_BY;
            case "number" -> PAYROLL_RUNS.NUMBER;
            case "postedAt" -> PAYROLL_RUNS.POSTED_AT;
            case "postedBy" -> PAYROLL_RUNS.POSTED_BY;
            case "paidAt" -> PAYROLL_RUNS.PAID_AT;
            case "paidBy" -> PAYROLL_RUNS.PAID_BY;
            case "paymentDate" -> PAYROLL_RUNS.PAYMENT_DATE;
            case "paymentBankAccountId" -> PAYROLL_RUNS.PAYMENT_BANK_ACCOUNT_ID;
            case "cancelledAt" -> PAYROLL_RUNS.CANCELLED_AT;
            default -> throw new IllegalArgumentException(name);
        };
    }

    public void delete(UUID companyId, UUID id) {
        dsl.deleteFrom(PAYROLL_RUNS)
                .where(PAYROLL_RUNS.COMPANY_ID.eq(companyId))
                .and(PAYROLL_RUNS.ID.eq(id))
                .execute();
    }

    // -------------------------------------------------------------------------------- issues

    public void insertIssue(UUID companyId, UUID runId, @Nullable UUID employeeId, String code, String message) {
        dsl.insertInto(PAYROLL_RUN_ISSUES)
                .set(PAYROLL_RUN_ISSUES.COMPANY_ID, companyId)
                .set(PAYROLL_RUN_ISSUES.PAYROLL_RUN_ID, runId)
                .set(PAYROLL_RUN_ISSUES.EMPLOYEE_ID, employeeId)
                .set(PAYROLL_RUN_ISSUES.CODE, code)
                .set(PAYROLL_RUN_ISSUES.MESSAGE, message.length() > 500 ? message.substring(0, 500) : message)
                .execute();
    }

    public void deleteIssues(UUID companyId, UUID runId) {
        dsl.deleteFrom(PAYROLL_RUN_ISSUES)
                .where(PAYROLL_RUN_ISSUES.COMPANY_ID.eq(companyId))
                .and(PAYROLL_RUN_ISSUES.PAYROLL_RUN_ID.eq(runId))
                .execute();
    }

    public List<PayrollViews.Issue> issues(UUID companyId, UUID runId) {
        return dsl.selectFrom(PAYROLL_RUN_ISSUES)
                .where(PAYROLL_RUN_ISSUES.COMPANY_ID.eq(companyId))
                .and(PAYROLL_RUN_ISSUES.PAYROLL_RUN_ID.eq(runId))
                .orderBy(PAYROLL_RUN_ISSUES.CREATED_AT, PAYROLL_RUN_ISSUES.ID)
                .fetch(r -> new PayrollViews.Issue(r.getId(), r.getEmployeeId(), r.getCode(), r.getMessage()));
    }

    static PayrollViews.Run toView(PayrollRunsRecord r) {
        return new PayrollViews.Run(
                r.getId(),
                r.getNumber(),
                r.getPayrollPeriodId(),
                r.getRunType(),
                r.getDescription(),
                RunStatus.valueOf(r.getStatus()),
                r.getAccountingDate(),
                r.getCurrencyCode(),
                r.getEmployeeCount(),
                r.getGrossTotal(),
                r.getDeductionTotal(),
                r.getEmployerContributionTotal(),
                r.getNetTotal(),
                r.getCalculationRequestedBy(),
                r.getCalculatedAt(),
                r.getApprovedBy(),
                r.getApprovedAt(),
                r.getPostedAt(),
                r.getPostedBy(),
                r.getPaidAt(),
                r.getPaymentDate(),
                r.getPaymentBankAccountId(),
                r.getCreatedAt(),
                r.getCreatedBy(),
                r.getUpdatedAt(),
                r.getVersion());
    }
}
