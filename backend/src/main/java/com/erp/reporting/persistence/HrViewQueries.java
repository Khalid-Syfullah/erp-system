package com.erp.reporting.persistence;

import static com.erp.db.hr.tables.VRptAttendance.V_RPT_ATTENDANCE;
import static com.erp.db.hr.tables.VRptEmployees.V_RPT_EMPLOYEES;
import static com.erp.db.hr.tables.VRptHeadcount.V_RPT_HEADCOUNT;
import static com.erp.db.hr.tables.VRptLeave.V_RPT_LEAVE;
import static com.erp.db.org.tables.VRptBranches.V_RPT_BRANCHES;
import static com.erp.db.org.tables.VRptDepartments.V_RPT_DEPARTMENTS;
import static com.erp.db.payroll.tables.VRptPayrollSummary.V_RPT_PAYROLL_SUMMARY;
import static com.erp.reporting.persistence.ViewQueries.branchScope;
import static com.erp.reporting.persistence.ViewQueries.countIf;
import static com.erp.reporting.persistence.ViewQueries.eq;
import static com.erp.reporting.persistence.ViewQueries.inRange;
import static com.erp.reporting.persistence.ViewQueries.percent;
import static com.erp.reporting.persistence.ViewQueries.sumIf;

import com.erp.db.hr.tables.VRptHeadcount;
import com.erp.reporting.application.ReportCatalog;
import com.erp.reporting.application.ReportScope;
import com.erp.reporting.domain.ReportParameters;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;
import org.jooq.Condition;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Record4;
import org.jooq.Select;
import org.jooq.SelectJoinStep;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.springframework.stereotype.Component;

/**
 * HR and payroll reports over {@code hr.v_rpt_*} and {@code payroll.v_rpt_payroll_summary}. Employees
 * are scoped by the branch of their assignment on the relevant date (SECURITY.md §4.4); payroll is
 * aggregated, never per employee.
 */
@Component
class HrViewQueries implements ViewQueries {

    @Override
    public Map<String, BiFunction<ReportParameters, ReportScope, Select<? extends Record>>> queries() {
        return Map.of(
                "headcount", this::headcount,
                "turnover", this::turnover,
                "attendance", this::attendance,
                "leave", this::leave,
                "payroll-summary", this::payroll);
    }

    /** The assignment is in effect and the employee employed on {@code date} (as HrReportService.headcount). */
    static Condition employedOn(VRptHeadcount h, Field<LocalDate> date) {
        return h.EFFECTIVE_FROM
                .le(date)
                .and(h.EFFECTIVE_TO.isNull().or(h.EFFECTIVE_TO.ge(date)))
                .and(h.HIRE_DATE.le(date))
                .and(h.TERMINATION_DATE.isNull().or(h.TERMINATION_DATE.ge(date)));
    }

    /** Group code and name of an assignment row for the {@code groupBy} parameter. */
    private static Field<?>[] group(VRptHeadcount h, String groupBy) {
        var b = V_RPT_BRANCHES;
        var d = V_RPT_DEPARTMENTS;
        return switch (groupBy) {
            case "BRANCH" -> new Field<?>[] {b.BRANCH_CODE, b.BRANCH_NAME};
            case "POSITION" ->
                new Field<?>[] {
                    DSL.coalesce(h.POSITION_CODE, DSL.inline("")),
                    DSL.coalesce(h.POSITION_TITLE, DSL.inline("No position"))
                };
            case "EMPLOYMENT_TYPE" -> new Field<?>[] {h.EMPLOYMENT_TYPE, h.EMPLOYMENT_TYPE};
            default -> new Field<?>[] {d.DEPARTMENT_CODE, d.DEPARTMENT_NAME};
        };
    }

    private static <R extends Record> SelectJoinStep<R> withOrganization(SelectJoinStep<R> select, VRptHeadcount h) {
        var b = V_RPT_BRANCHES;
        var d = V_RPT_DEPARTMENTS;
        return select.join(b)
                .on(b.COMPANY_ID.eq(h.COMPANY_ID))
                .and(b.BRANCH_ID.eq(h.BRANCH_ID))
                .join(d)
                .on(d.COMPANY_ID.eq(h.COMPANY_ID))
                .and(d.DEPARTMENT_ID.eq(h.DEPARTMENT_ID));
    }

    private static Condition organization(VRptHeadcount h, ReportParameters p, ReportScope s) {
        return h.COMPANY_ID
                .eq(s.companyId())
                .and(eq(h.BRANCH_ID, p.id("branchId")))
                .and(eq(h.DEPARTMENT_ID, p.id("departmentId")))
                .and(branchScope(s, h.BRANCH_ID));
    }

    private Select<? extends Record> headcount(ReportParameters p, ReportScope s) {
        var h = V_RPT_HEADCOUNT;
        Field<?>[] group = group(h, p.choice(ReportCatalog.GROUP_BY));
        var select = DSL.select(
                        group[0].as("groupCode"),
                        group[1].as("groupName"),
                        DSL.countDistinct(h.EMPLOYEE_ID).as("headcount"),
                        DSL.coalesce(DSL.sum(h.FTE), BigDecimal.ZERO).as("fte"))
                .from(h);
        return withOrganization(select, h)
                .where(organization(h, p, s))
                .and(employedOn(h, DSL.val(p.requireDate(ReportCatalog.AS_OF))))
                .groupBy(group[0], group[1]);
    }

    private Select<? extends Record> turnover(ReportParameters p, ReportScope s) {
        LocalDate from = p.requireDate(ReportCatalog.FROM);
        LocalDate to = p.requireDate(ReportCatalog.TO);
        String groupBy = p.choice(ReportCatalog.GROUP_BY);
        var h = V_RPT_HEADCOUNT;
        Table<?> events = movement(h, p, s, groupBy, "O", employedOn(h, DSL.val(from)))
                .unionAll(movement(h, p, s, groupBy, "C", employedOn(h, DSL.val(to))))
                .unionAll(movement(
                        h, p, s, groupBy, "H", h.HIRE_DATE.between(from, to).and(employedOn(h, h.HIRE_DATE))))
                .unionAll(movement(
                        h,
                        p,
                        s,
                        groupBy,
                        "T",
                        h.TERMINATION_DATE.between(from, to).and(employedOn(h, h.TERMINATION_DATE))))
                .asTable("e");
        Field<String> kind = events.field("kind", String.class);
        Field<String> code = events.field("code", String.class);
        Field<String> name = events.field("name", String.class);
        Field<Integer> opening = countIf(kind.eq("O"));
        Field<Integer> closing = countIf(kind.eq("C"));
        Field<Integer> terminations = countIf(kind.eq("T"));
        Field<BigDecimal> average =
                opening.plus(closing).cast(SQLDataType.NUMERIC).div(2);
        return DSL.select(
                        code.as("groupCode"),
                        name.as("groupName"),
                        opening.as("openingHeadcount"),
                        countIf(kind.eq("H")).as("hires"),
                        terminations.as("terminations"),
                        closing.as("closingHeadcount"),
                        percent(terminations.cast(SQLDataType.NUMERIC), average).as("turnoverPercent"))
                .from(events)
                .groupBy(code, name);
    }

    /** One row per employee of a turnover event kind (O opening, C closing, H hire, T termination). */
    private static Select<Record4<String, String, String, UUID>> movement(
            VRptHeadcount h, ReportParameters p, ReportScope s, String groupBy, String kind, Condition condition) {
        Field<?>[] group = group(h, groupBy);
        var select = DSL.selectDistinct(
                        group[0].cast(SQLDataType.VARCHAR).as("code"),
                        group[1].cast(SQLDataType.VARCHAR).as("name"),
                        DSL.inline(kind).as("kind"),
                        h.EMPLOYEE_ID.as("employee"))
                .from(h);
        return withOrganization(select, h).where(organization(h, p, s)).and(condition);
    }

    private Select<? extends Record> attendance(ReportParameters p, ReportScope s) {
        var a = V_RPT_ATTENDANCE;
        var e = V_RPT_EMPLOYEES;
        var h = V_RPT_HEADCOUNT;
        var d = V_RPT_DEPARTMENTS;
        Field<String> department = DSL.coalesce(d.DEPARTMENT_CODE, DSL.inline(""));
        return DSL.select(
                        a.EMPLOYEE_ID.as("employeeId"),
                        e.EMPLOYEE_NUMBER.as("employeeNumber"),
                        e.EMPLOYEE_NAME.as("employeeName"),
                        department.as("departmentCode"),
                        countIf(a.STATUS.eq("PRESENT")).as("presentDays"),
                        countIf(a.STATUS.eq("REMOTE")).as("remoteDays"),
                        countIf(a.STATUS.eq("HALF_DAY")).as("halfDays"),
                        countIf(a.STATUS.eq("ABSENT")).as("absentDays"),
                        countIf(a.STATUS.eq("ON_LEAVE")).as("leaveDays"),
                        countIf(a.STATUS.eq("HOLIDAY")).as("holidayDays"),
                        DSL.count().as("recordedDays"),
                        DSL.coalesce(DSL.sum(a.WORKED_MINUTES), BigDecimal.ZERO)
                                .cast(SQLDataType.BIGINT)
                                .as("workedMinutes"))
                .from(a)
                .join(e)
                .on(e.COMPANY_ID.eq(a.COMPANY_ID))
                .and(e.EMPLOYEE_ID.eq(a.EMPLOYEE_ID))
                .leftJoin(h)
                .on(h.COMPANY_ID.eq(a.COMPANY_ID))
                .and(h.EMPLOYEE_ID.eq(a.EMPLOYEE_ID))
                .and(h.EFFECTIVE_FROM.le(a.WORK_DATE))
                .and(h.EFFECTIVE_TO.isNull().or(h.EFFECTIVE_TO.ge(a.WORK_DATE)))
                .leftJoin(d)
                .on(d.COMPANY_ID.eq(h.COMPANY_ID))
                .and(d.DEPARTMENT_ID.eq(h.DEPARTMENT_ID))
                .where(a.COMPANY_ID.eq(s.companyId()))
                .and(inRange(a.WORK_DATE, p))
                .and(eq(a.EMPLOYEE_ID, p.id("employeeId")))
                .and(eq(h.BRANCH_ID, p.id("branchId")))
                .and(eq(h.DEPARTMENT_ID, p.id("departmentId")))
                .and(branchScope(s, h.BRANCH_ID))
                .groupBy(a.EMPLOYEE_ID, e.EMPLOYEE_NUMBER, e.EMPLOYEE_NAME, department);
    }

    private Select<? extends Record> leave(ReportParameters p, ReportScope s) {
        var l = V_RPT_LEAVE;
        var e = V_RPT_EMPLOYEES;
        var h = V_RPT_HEADCOUNT;
        var d = V_RPT_DEPARTMENTS;
        Field<String> department = DSL.coalesce(d.DEPARTMENT_CODE, DSL.inline(""));
        return DSL.select(
                        l.EMPLOYEE_ID.as("employeeId"),
                        e.EMPLOYEE_NUMBER.as("employeeNumber"),
                        e.EMPLOYEE_NAME.as("employeeName"),
                        department.as("departmentCode"),
                        l.LEAVE_TYPE_CODE.as("leaveTypeCode"),
                        l.LEAVE_TYPE_NAME.as("leaveTypeName"),
                        l.IS_PAID.as("paid"),
                        DSL.count().as("requestCount"),
                        DSL.coalesce(DSL.sum(l.DAYS), BigDecimal.ZERO).as("days"))
                .from(l)
                .join(e)
                .on(e.COMPANY_ID.eq(l.COMPANY_ID))
                .and(e.EMPLOYEE_ID.eq(l.EMPLOYEE_ID))
                .leftJoin(h)
                .on(h.COMPANY_ID.eq(l.COMPANY_ID))
                .and(h.EMPLOYEE_ID.eq(l.EMPLOYEE_ID))
                .and(h.EFFECTIVE_FROM.le(l.START_DATE))
                .and(h.EFFECTIVE_TO.isNull().or(h.EFFECTIVE_TO.ge(l.START_DATE)))
                .leftJoin(d)
                .on(d.COMPANY_ID.eq(h.COMPANY_ID))
                .and(d.DEPARTMENT_ID.eq(h.DEPARTMENT_ID))
                .where(l.COMPANY_ID.eq(s.companyId()))
                .and(inRange(l.START_DATE, p))
                .and(eq(l.LEAVE_TYPE_ID, p.id("leaveTypeId")))
                .and(eq(l.STATUS, p.text("status")))
                .and(eq(h.BRANCH_ID, p.id("branchId")))
                .and(eq(h.DEPARTMENT_ID, p.id("departmentId")))
                .and(branchScope(s, h.BRANCH_ID))
                .groupBy(
                        l.EMPLOYEE_ID,
                        e.EMPLOYEE_NUMBER,
                        e.EMPLOYEE_NAME,
                        department,
                        l.LEAVE_TYPE_CODE,
                        l.LEAVE_TYPE_NAME,
                        l.IS_PAID);
    }

    private Select<? extends Record> payroll(ReportParameters p, ReportScope s) {
        var ps = V_RPT_PAYROLL_SUMMARY;
        var d = V_RPT_DEPARTMENTS;
        Field<String> code;
        Field<String> name;
        Field<String> kind;
        switch (p.choice(ReportCatalog.GROUP_BY)) {
            case "PERIOD" -> {
                code = DSL.field("to_char({0}, 'YYYY-MM-DD')", SQLDataType.VARCHAR, ps.PERIOD_START);
                name = DSL.field(
                        "to_char({0}, 'YYYY-MM-DD') || ' – ' || to_char({1}, 'YYYY-MM-DD')",
                        SQLDataType.VARCHAR, ps.PERIOD_START, ps.PERIOD_END);
                kind = DSL.castNull(SQLDataType.VARCHAR);
            }
            case "DEPARTMENT" -> {
                code = d.DEPARTMENT_CODE;
                name = d.DEPARTMENT_NAME;
                kind = DSL.castNull(SQLDataType.VARCHAR);
            }
            default -> {
                code = ps.COMPONENT_CODE;
                name = ps.COMPONENT_NAME;
                kind = ps.KIND;
            }
        }
        Field<BigDecimal> earnings = sumIf(ps.AMOUNT, ps.KIND.eq("EARNING"));
        Field<BigDecimal> deductions = sumIf(ps.AMOUNT, ps.KIND.eq("DEDUCTION"));
        return DSL.select(
                        code.as("groupCode"),
                        name.as("groupName"),
                        kind.as("kind"),
                        earnings.as("earnings"),
                        deductions.as("deductions"),
                        sumIf(ps.AMOUNT, ps.KIND.eq("EMPLOYER_CONTRIBUTION")).as("employerContributions"),
                        earnings.minus(deductions).as("netPay"))
                .from(ps)
                .join(d)
                .on(d.COMPANY_ID.eq(ps.COMPANY_ID))
                .and(d.DEPARTMENT_ID.eq(ps.DEPARTMENT_ID))
                .where(ps.COMPANY_ID.eq(s.companyId()))
                .and(inRange(ps.PERIOD_END, p))
                .and(eq(ps.BRANCH_ID, p.id("branchId")))
                .and(eq(ps.DEPARTMENT_ID, p.id("departmentId")))
                .and(branchScope(s, ps.BRANCH_ID))
                .groupBy(code, name, kind);
    }
}
