package com.erp.payroll.persistence;

import static com.erp.db.payroll.Tables.PAYROLL_RUNS;
import static com.erp.db.payroll.Tables.PAYSLIPS;
import static com.erp.db.payroll.Tables.PAYSLIP_LINES;

import com.erp.db.payroll.tables.records.PayslipLinesRecord;
import com.erp.db.payroll.tables.records.PayslipsRecord;
import com.erp.payroll.application.PayrollListings;
import com.erp.payroll.application.PayrollViews;
import com.erp.platform.jooq.KeysetPaginator;
import com.erp.platform.jooq.ListBinding;
import com.erp.platform.web.paging.ListQuery;
import com.erp.platform.web.paging.PageResponse;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;

/** Payslips and their lines (DATABASE.md §5.10). */
@Repository
public class PayslipRepository {

    private static final ListBinding BINDING = ListBinding.builder(PayrollListings.PAYSLIPS)
            .field("employeeNumber", PAYSLIPS.EMPLOYEE_NUMBER)
            .field("createdAt", PAYSLIPS.CREATED_AT)
            .field("employeeId", PAYSLIPS.EMPLOYEE_ID)
            .field("departmentId", PAYSLIPS.DEPARTMENT_ID)
            .field("branchId", PAYSLIPS.BRANCH_ID)
            .tiebreaker(PAYSLIPS.ID)
            .build();

    /** A payslip to insert with its lines. */
    public record NewPayslip(PayrollViews.Payslip payslip, List<PayrollViews.PayslipLine> lines) {}

    /** An aggregated amount of a run, for posting and reports. */
    public record Aggregate(
            UUID componentId,
            String componentCode,
            String kind,
            @Nullable UUID departmentId,
            @Nullable UUID branchId,
            BigDecimal amount) {}

    private final DSLContext dsl;
    private final KeysetPaginator paginator;

    public PayslipRepository(DSLContext dsl, KeysetPaginator paginator) {
        this.dsl = dsl;
        this.paginator = paginator;
    }

    /** Batch insert of payslips and their lines (one calculation chunk). */
    public void insert(UUID companyId, UUID runId, List<NewPayslip> payslips, @Nullable UUID actor) {
        if (payslips.isEmpty()) {
            return;
        }
        List<PayslipsRecord> headers = new ArrayList<>();
        List<PayslipLinesRecord> lines = new ArrayList<>();
        for (NewPayslip n : payslips) {
            PayrollViews.Payslip p = n.payslip();
            PayslipsRecord h = dsl.newRecord(PAYSLIPS);
            h.setId(p.id());
            h.setCompanyId(companyId);
            h.setPayrollRunId(runId);
            h.setEmployeeId(p.employeeId());
            h.setEmployeeNumber(p.employeeNumber());
            h.setEmployeeName(p.employeeName());
            h.setBranchId(p.branchId());
            h.setDepartmentId(p.departmentId());
            h.setPositionTitle(p.positionTitle());
            h.setDaysPaid(p.daysPaid());
            h.setDaysInPeriod(p.daysInPeriod());
            h.setBaseAmount(p.baseAmount());
            h.setTaxableGross(p.taxableGross());
            h.setGrossAmount(p.grossAmount());
            h.setDeductionAmount(p.deductionAmount());
            h.setEmployerContributionAmount(p.employerContributionAmount());
            h.setNetAmount(p.netAmount());
            h.setCurrencyCode(p.currencyCode());
            h.setCreatedBy(actor);
            h.setUpdatedBy(actor);
            headers.add(h);
            for (PayrollViews.PayslipLine l : n.lines()) {
                PayslipLinesRecord r = dsl.newRecord(PAYSLIP_LINES);
                r.setCompanyId(companyId);
                r.setPayslipId(p.id());
                r.setComponentId(l.componentId());
                r.setComponentCode(l.componentCode());
                r.setComponentName(l.componentName());
                r.setKind(l.kind());
                r.setIsTaxable(l.taxable());
                r.setQuantity(l.quantity());
                r.setRate(l.rate());
                r.setAmount(l.amount());
                r.setSequence(l.sequence());
                lines.add(r);
            }
        }
        dsl.batchInsert(headers).execute();
        if (!lines.isEmpty()) {
            dsl.batchInsert(lines).execute();
        }
    }

    public UUID newId() {
        return dsl.select(DSL.field("uuidv7()", UUID.class)).fetchSingle().value1();
    }

    public void deleteForRun(UUID companyId, UUID runId) {
        dsl.deleteFrom(PAYSLIPS)
                .where(PAYSLIPS.COMPANY_ID.eq(companyId))
                .and(PAYSLIPS.PAYROLL_RUN_ID.eq(runId))
                .execute();
    }

    public PageResponse<PayrollViews.Payslip> list(UUID companyId, UUID runId, ListQuery query) {
        return paginator.fetch(
                dsl,
                PAYSLIPS,
                PAYSLIPS.COMPANY_ID.eq(companyId).and(PAYSLIPS.PAYROLL_RUN_ID.eq(runId)),
                query,
                BINDING,
                PayslipRepository::toView);
    }

    public Optional<PayrollViews.Payslip> find(UUID companyId, UUID id) {
        return dsl.selectFrom(PAYSLIPS)
                .where(PAYSLIPS.COMPANY_ID.eq(companyId))
                .and(PAYSLIPS.ID.eq(id))
                .fetchOptional(PayslipRepository::toView);
    }

    public List<PayrollViews.Payslip> forRun(UUID companyId, UUID runId) {
        return dsl.selectFrom(PAYSLIPS)
                .where(PAYSLIPS.COMPANY_ID.eq(companyId))
                .and(PAYSLIPS.PAYROLL_RUN_ID.eq(runId))
                .orderBy(PAYSLIPS.EMPLOYEE_NUMBER)
                .fetch(PayslipRepository::toView);
    }

    public Map<UUID, List<PayrollViews.PayslipLine>> lines(UUID companyId, Collection<UUID> payslipIds) {
        if (payslipIds.isEmpty()) {
            return Map.of();
        }
        return dsl.selectFrom(PAYSLIP_LINES)
                .where(PAYSLIP_LINES.COMPANY_ID.eq(companyId))
                .and(PAYSLIP_LINES.PAYSLIP_ID.in(payslipIds))
                .orderBy(PAYSLIP_LINES.KIND, PAYSLIP_LINES.SEQUENCE, PAYSLIP_LINES.COMPONENT_CODE)
                .fetchGroups(
                        PAYSLIP_LINES.PAYSLIP_ID,
                        r -> new PayrollViews.PayslipLine(
                                r.getComponentId(),
                                r.getComponentCode(),
                                r.getComponentName(),
                                r.getKind(),
                                r.getIsTaxable(),
                                r.getQuantity(),
                                r.getRate(),
                                r.getAmount(),
                                r.getSequence()));
    }

    /** The employee's payslips of posted or paid runs, newest first (self-service). */
    public List<PayrollViews.Payslip> releasedForEmployee(UUID companyId, UUID employeeId) {
        return dsl.select(PAYSLIPS.fields())
                .from(PAYSLIPS)
                .join(PAYROLL_RUNS)
                .on(PAYROLL_RUNS.COMPANY_ID.eq(PAYSLIPS.COMPANY_ID).and(PAYROLL_RUNS.ID.eq(PAYSLIPS.PAYROLL_RUN_ID)))
                .where(PAYSLIPS.COMPANY_ID.eq(companyId))
                .and(PAYSLIPS.EMPLOYEE_ID.eq(employeeId))
                .and(PAYROLL_RUNS.STATUS.in("POSTED", "PAID"))
                .orderBy(PAYROLL_RUNS.ACCOUNTING_DATE.desc(), PAYSLIPS.CREATED_AT.desc())
                .fetch(r -> toView(r.into(PAYSLIPS)));
    }

    /** Payslips of posted or paid runs that have no PDF yet. */
    public List<UUID> pendingPdfs(UUID companyId, int limit) {
        return dsl.select(PAYSLIPS.ID)
                .from(PAYSLIPS)
                .join(PAYROLL_RUNS)
                .on(PAYROLL_RUNS.COMPANY_ID.eq(PAYSLIPS.COMPANY_ID).and(PAYROLL_RUNS.ID.eq(PAYSLIPS.PAYROLL_RUN_ID)))
                .where(PAYSLIPS.COMPANY_ID.eq(companyId))
                .and(PAYSLIPS.FILE_ID.isNull())
                .and(PAYROLL_RUNS.STATUS.in("POSTED", "PAID"))
                .orderBy(PAYSLIPS.CREATED_AT)
                .limit(limit)
                .fetch(PAYSLIPS.ID);
    }

    /** Sets the PDF unless another worker did; returns whether this call set it. */
    public boolean setFile(UUID companyId, UUID payslipId, UUID fileId) {
        return dsl.update(PAYSLIPS)
                        .set(PAYSLIPS.FILE_ID, fileId)
                        .where(PAYSLIPS.COMPANY_ID.eq(companyId))
                        .and(PAYSLIPS.ID.eq(payslipId))
                        .and(PAYSLIPS.FILE_ID.isNull())
                        .execute()
                == 1;
    }

    /** Amounts of the run by component, department, branch and kind (payroll.run.posted). */
    public List<Aggregate> aggregate(UUID companyId, UUID runId) {
        return dsl.select(
                        PAYSLIP_LINES.COMPONENT_ID,
                        PAYSLIP_LINES.COMPONENT_CODE,
                        PAYSLIP_LINES.KIND,
                        PAYSLIPS.DEPARTMENT_ID,
                        PAYSLIPS.BRANCH_ID,
                        DSL.sum(PAYSLIP_LINES.AMOUNT))
                .from(PAYSLIP_LINES)
                .join(PAYSLIPS)
                .on(PAYSLIPS.COMPANY_ID.eq(PAYSLIP_LINES.COMPANY_ID).and(PAYSLIPS.ID.eq(PAYSLIP_LINES.PAYSLIP_ID)))
                .where(PAYSLIPS.COMPANY_ID.eq(companyId))
                .and(PAYSLIPS.PAYROLL_RUN_ID.eq(runId))
                .groupBy(
                        PAYSLIP_LINES.COMPONENT_ID,
                        PAYSLIP_LINES.COMPONENT_CODE,
                        PAYSLIP_LINES.KIND,
                        PAYSLIPS.DEPARTMENT_ID,
                        PAYSLIPS.BRANCH_ID)
                .orderBy(PAYSLIP_LINES.KIND, PAYSLIP_LINES.COMPONENT_CODE, PAYSLIPS.DEPARTMENT_ID, PAYSLIPS.BRANCH_ID)
                .fetch(r -> new Aggregate(r.value1(), r.value2(), r.value3(), r.value4(), r.value5(), r.value6()));
    }

    /** Component totals of posted and paid runs with an accounting date in the range. */
    public List<Aggregate> postedComponentTotals(UUID companyId, LocalDate from, LocalDate to) {
        Condition condition = PAYSLIPS.COMPANY_ID
                .eq(companyId)
                .and(PAYROLL_RUNS.STATUS.in("POSTED", "PAID"))
                .and(PAYROLL_RUNS.ACCOUNTING_DATE.between(from, to));
        return dsl.select(
                        PAYSLIP_LINES.COMPONENT_ID,
                        PAYSLIP_LINES.COMPONENT_CODE,
                        PAYSLIP_LINES.KIND,
                        DSL.sum(PAYSLIP_LINES.AMOUNT))
                .from(PAYSLIP_LINES)
                .join(PAYSLIPS)
                .on(PAYSLIPS.COMPANY_ID.eq(PAYSLIP_LINES.COMPANY_ID).and(PAYSLIPS.ID.eq(PAYSLIP_LINES.PAYSLIP_ID)))
                .join(PAYROLL_RUNS)
                .on(PAYROLL_RUNS.COMPANY_ID.eq(PAYSLIPS.COMPANY_ID).and(PAYROLL_RUNS.ID.eq(PAYSLIPS.PAYROLL_RUN_ID)))
                .where(condition)
                .groupBy(PAYSLIP_LINES.COMPONENT_ID, PAYSLIP_LINES.COMPONENT_CODE, PAYSLIP_LINES.KIND)
                .orderBy(PAYSLIP_LINES.KIND, PAYSLIP_LINES.COMPONENT_CODE)
                .fetch(r -> new Aggregate(r.value1(), r.value2(), r.value3(), null, null, r.value4()));
    }

    static PayrollViews.Payslip toView(PayslipsRecord r) {
        return new PayrollViews.Payslip(
                r.getId(),
                r.getPayrollRunId(),
                r.getEmployeeId(),
                r.getEmployeeNumber(),
                r.getEmployeeName(),
                r.getBranchId(),
                r.getDepartmentId(),
                r.getPositionTitle(),
                r.getDaysPaid(),
                r.getDaysInPeriod(),
                r.getBaseAmount(),
                r.getTaxableGross(),
                r.getGrossAmount(),
                r.getDeductionAmount(),
                r.getEmployerContributionAmount(),
                r.getNetAmount(),
                r.getCurrencyCode(),
                r.getFileId(),
                r.getVersion());
    }
}
