package com.erp.support;

import static com.erp.support.HrFixtures.expect;

import com.erp.payroll.application.PayrollJobs;
import com.erp.support.AccountingFixtures.Books;
import com.erp.support.AuthTestSupport.TestUser;
import com.erp.support.HrFixtures.Hr;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Payroll test setups through the API (Phase 9), on top of {@link HrFixtures} and
 * {@link AccountingFixtures}: a payroll officer (configuration, compensation, preparation, payslips)
 * and a separate approver (approve, post, pay, payslips, reports), both MFA enrolled; the components
 * BASIC (100 % of base), HOUSING (fixed 500, not taxable), OVERTIME (input, 20 per hour), TAX
 * (statutory FLAT_PERCENT 10 % of taxable gross), PENSION (5 % of gross) and PENSION_ER (employer,
 * 8 % of gross); the structure STD with all of them and a monthly USD schedule with this year's
 * periods. For a base of 3 000 a full month pays gross 3 500, deductions 475, net 3 025, employer
 * contributions 280.
 */
@TestComponent
public class PayrollFixtures {

    public static final String[] OFFICER = {
        "payroll.configuration.manage",
        "payroll.compensation.read",
        "payroll.compensation.manage",
        "payroll.run.read",
        "payroll.run.prepare",
        "payroll.payslip.read",
        "hr.employee.read"
    };

    public static final String[] APPROVER = {
        "payroll.run.read",
        "payroll.run.approve",
        "payroll.run.post",
        "payroll.run.pay",
        "payroll.payslip.read",
        "payroll.report.read"
    };

    /** A company's payroll as a test sees it. */
    public record Payroll(
            Hr hr,
            Books books,
            Cookie officer,
            TestUser officerUser,
            Cookie approver,
            TestUser approverUser,
            UUID schedule,
            UUID structure,
            Map<String, UUID> components) {

        public String path(String suffix) {
            return hr.path(suffix);
        }

        public UUID component(String code) {
            return components.get(code);
        }

        public int year() {
            return hr.today().getYear();
        }
    }

    private final AuthTestSupport auth;
    private final HrFixtures hrFixtures;
    private final AccountingFixtures acc;
    private final PayrollJobs jobs;

    public PayrollFixtures(AuthTestSupport auth, HrFixtures hrFixtures, AccountingFixtures acc, PayrollJobs jobs) {
        this.auth = auth;
        this.hrFixtures = hrFixtures;
        this.acc = acc;
        this.jobs = jobs;
    }

    public Payroll setup() throws Exception {
        Hr hr = hrFixtures.setup();
        Books books = acc.books(hr.company());
        TestUser officerUser = auth.enrollMfa(auth.user());
        auth.assign(officerUser, auth.customRole(OFFICER), hr.company());
        TestUser approverUser = auth.enrollMfa(auth.user());
        auth.assign(approverUser, auth.customRole(APPROVER), hr.company());
        auth.invalidatePermissionCache();
        Cookie officer = auth.login(officerUser);
        Cookie approver = auth.login(approverUser);
        String base = hr.path("");
        Map<String, UUID> components = new HashMap<>();
        components.put(
                "BASIC", component(officer, base, "BASIC", "EARNING", "PERCENT_OF_BASE", "100", null, true, null, 10));
        components.put(
                "HOUSING", component(officer, base, "HOUSING", "EARNING", "FIXED", null, "500", false, null, 20));
        components.put(
                "OVERTIME", component(officer, base, "OVERTIME", "EARNING", "INPUT", "20", null, true, null, 30));
        components.put(
                "TAX", component(officer, base, "TAX", "DEDUCTION", "STATUTORY", "10", null, true, "FLAT_PERCENT", 10));
        components.put(
                "PENSION",
                component(officer, base, "PENSION", "DEDUCTION", "PERCENT_OF_GROSS", "5", null, true, null, 20));
        components.put(
                "PENSION_ER",
                component(
                        officer,
                        base,
                        "PENSION_ER",
                        "EMPLOYER_CONTRIBUTION",
                        "PERCENT_OF_GROSS",
                        "8",
                        null,
                        true,
                        null,
                        10));
        UUID structure = hrFixtures.create(
                officer,
                base + "/salary-structures",
                OrgFixtures.map(
                        "code",
                        "STD",
                        "name",
                        "Standard",
                        "components",
                        components.values().stream()
                                .map(id -> OrgFixtures.map("componentId", id))
                                .toList()));
        UUID schedule = hrFixtures.create(
                officer,
                base + "/pay-schedules",
                OrgFixtures.map("code", "MONTHLY", "name", "Monthly", "frequency", "MONTHLY", "currencyCode", "USD"));
        expect(
                hrFixtures.post(
                        officer,
                        base + "/pay-schedules/" + schedule + "/periods",
                        OrgFixtures.map("year", hr.today().getYear())),
                200);
        return new Payroll(hr, books, officer, officerUser, approver, approverUser, schedule, structure, components);
    }

    public UUID component(
            Cookie session,
            String base,
            String code,
            String kind,
            String calculation,
            String rate,
            String amount,
            boolean taxable,
            String rule,
            int sequence)
            throws Exception {
        return hrFixtures.create(
                session,
                base + "/pay-components",
                OrgFixtures.map(
                        "code",
                        code,
                        "name",
                        "Component " + code,
                        "kind",
                        kind,
                        "calculation",
                        calculation,
                        "defaultRate",
                        rate,
                        "defaultAmount",
                        amount,
                        "isTaxable",
                        taxable,
                        "statutoryRuleCode",
                        rule,
                        "sequence",
                        sequence));
    }

    /** An active employee hired on {@code hireDate} with a compensation of {@code base} per month from then. */
    public UUID employee(Payroll p, String number, LocalDate hireDate, String base) throws Exception {
        UUID employee = hrFixtures.employee(p.hr(), number, hireDate, null);
        compensation(p, employee, base, hireDate);
        return employee;
    }

    public UUID compensation(Payroll p, UUID employee, String base, LocalDate from) throws Exception {
        return hrFixtures.create(
                p.officer(),
                p.path("/employees/" + employee + "/compensations"),
                OrgFixtures.map(
                        "payScheduleId",
                        p.schedule(),
                        "salaryStructureId",
                        p.structure(),
                        "baseAmount",
                        base,
                        "effectiveFrom",
                        from));
    }

    /** The period of the month in this year. */
    public UUID period(Payroll p, int month) throws Exception {
        String body = hrFixtures.body(
                p.officer(),
                p.path("/payroll-periods?filter[payScheduleId]=" + p.schedule() + "&filter[startDate]="
                        + LocalDate.of(p.year(), month, 1)));
        List<String> ids = JsonPath.read(body, "$.data[*].id");
        if (ids.size() != 1) {
            throw new AssertionError("No period for month " + month);
        }
        return UUID.fromString(ids.getFirst());
    }

    public UUID run(Payroll p, UUID period, String type) throws Exception {
        String body = hrFixtures
                .post(p.officer(), p.path("/payroll-runs"), OrgFixtures.map("payrollPeriodId", period, "runType", type))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String id = JsonPath.read(body, "$.run.id");
        return UUID.fromString(id);
    }

    /** Queues the calculation as the officer and runs the calculation job. */
    public void calculate(Payroll p, UUID run) throws Exception {
        expect(
                hrFixtures.action(
                        p.officer(), p.path("/payroll-runs/" + run + "/calculate"), version(p, run), null, null),
                202);
        jobs.calculateQueued(p.hr().company());
    }

    public ResultActions act(Payroll p, Cookie session, UUID run, String action, Object body) throws Exception {
        return hrFixtures.action(
                session,
                p.path("/payroll-runs/" + run + "/" + action),
                version(p, run),
                action + "-" + UUID.randomUUID(),
                body);
    }

    /** Calculated, approved and posted. */
    public void post(Payroll p, UUID run) throws Exception {
        calculate(p, run);
        expect(act(p, p.approver(), run, "approve", null), 200);
        expect(act(p, p.approver(), run, "post", null), 200);
    }

    public int version(Payroll p, UUID run) throws Exception {
        return JsonPath.read(hrFixtures.body(p.officer(), p.path("/payroll-runs/" + run)), "$.run.version");
    }

    public String runBody(Payroll p, UUID run) throws Exception {
        return hrFixtures.body(p.officer(), p.path("/payroll-runs/" + run));
    }

    /** The run's payslip of the employee, with lines, as JSON. */
    public String payslip(Payroll p, UUID run, UUID employee) throws Exception {
        String list = hrFixtures.body(
                p.officer(), p.path("/payroll-runs/" + run + "/payslips?filter[employeeId]=" + employee));
        List<String> ids = JsonPath.read(list, "$.data[*].id");
        if (ids.size() != 1) {
            throw new AssertionError("No payslip of " + employee + " in " + run + ": " + list);
        }
        return hrFixtures.body(p.officer(), p.path("/payslips/" + ids.getFirst()));
    }

    public PayrollJobs jobs() {
        return jobs;
    }
}
