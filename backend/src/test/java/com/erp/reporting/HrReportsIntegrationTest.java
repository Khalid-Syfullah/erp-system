package com.erp.reporting;

import static com.erp.support.AuthTestSupport.unsafe;
import static com.erp.support.HrFixtures.expect;
import static com.erp.support.ReportingFixtures.decimal;
import static com.erp.support.ReportingFixtures.rows;
import static com.erp.support.ReportingFixtures.totals;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erp.support.AuthTestSupport;
import com.erp.support.HrFixtures;
import com.erp.support.HrFixtures.Hr;
import com.erp.support.IntegrationTest;
import com.erp.support.OrgFixtures;
import com.erp.support.PayrollFixtures;
import com.erp.support.PayrollFixtures.Payroll;
import com.erp.support.ReportingFixtures;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * HR and payroll reports: headcount equals HR's own headcount, turnover counts hires and
 * terminations at period boundaries, attendance and leave count what HR recorded, and the payroll
 * summary equals the posted run's totals without per-employee figures and only with payroll's
 * report permission.
 */
class HrReportsIntegrationTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    HrFixtures hr;

    @Autowired
    PayrollFixtures payroll;

    @Autowired
    ReportingFixtures rep;

    @Autowired
    AuthTestSupport auth;

    @Test
    void headcountAndTurnoverFollowEmploymentDates() throws Exception {
        Hr h = hr.setup();
        LocalDate today = h.today();
        UUID sales = hr.create(h.session(), h.path("/departments"), OrgFixtures.map("code", "SALES", "name", "Sales"));
        LocalDate longAgo = LocalDate.of(today.getYear() - 2, 1, 1);
        hr.employee(h, "E1", longAgo, null);
        UUID leaver = hr.employee(h, "E2", longAgo, null);
        UUID newcomer = employee(h, "E3", today.minusDays(5), sales);
        expect(
                hr.action(
                        h.session(),
                        h.path("/employees/" + leaver + "/terminate"),
                        hr.version(h, "/employees/" + leaver),
                        null,
                        OrgFixtures.map("terminationDate", today, "reason", "Resigned")),
                200);
        Cookie analyst = rep.user(h.company(), "reporting.hr.read");

        // The last day of employment still counts, as in HR's headcount.
        String headcount = rep.run(analyst, h.company(), "headcount", "asOf=" + today);
        assertThat(totals(headcount)).containsEntry("headcount", 3);
        assertThat((Integer)
                        JsonPath.read(hr.body(h.session(), h.path("/hr-reports/headcount?asOf=" + today)), "$.total"))
                .isEqualTo(3);
        assertThat(rows(headcount))
                .extracting(r -> r.get("groupCode") + "=" + r.get("headcount"))
                .containsExactly("OPS=2", "SALES=1");
        assertThat(decimal(totals(headcount).get("fte"))).isEqualByComparingTo("3");
        assertThat(totals(rep.run(analyst, h.company(), "headcount", "asOf=" + today.plusDays(1))))
                .containsEntry("headcount", 2);
        assertThat(totals(rep.run(analyst, h.company(), "headcount", "asOf=" + today.minusDays(6))))
                .containsEntry("headcount", 2);
        assertThat(rows(rep.run(analyst, h.company(), "headcount", "groupBy=POSITION")))
                .extracting(r -> r.get("groupCode") + "=" + r.get("headcount"))
                .containsExactly("=1", "DEV=2");
        assertThat(rows(rep.run(analyst, h.company(), "headcount", "groupBy=BRANCH")))
                .singleElement()
                .satisfies(r -> assertThat(r).containsEntry("groupCode", "MAIN"));

        Map<String, Map<String, Object>> turnover =
                rows(rep.run(analyst, h.company(), "turnover", "from=" + today.minusDays(10) + "&to=" + today)).stream()
                        .collect(Collectors.toMap(r -> (String) r.get("groupCode"), r -> r));
        assertThat(turnover.get("OPS"))
                .containsEntry("openingHeadcount", 2)
                .containsEntry("hires", 0)
                .containsEntry("terminations", 1)
                .containsEntry("closingHeadcount", 2);
        assertThat(decimal(turnover.get("OPS").get("turnoverPercent"))).isEqualByComparingTo("50");
        assertThat(turnover.get("SALES")).containsEntry("hires", 1).containsEntry("closingHeadcount", 1);
        Map<String, Object> company = rows(rep.run(
                        analyst,
                        h.company(),
                        "turnover",
                        "from=" + today.minusDays(10) + "&to=" + today + "&groupBy=BRANCH"))
                .getFirst();
        assertThat(company)
                .containsEntry("openingHeadcount", 2)
                .containsEntry("hires", 1)
                .containsEntry("terminations", 1);
        assertThat(decimal(company.get("turnoverPercent"))).isEqualByComparingTo("40");
        assertThat(newcomer).isNotNull();

        // Employees are scoped by their assignment's branch.
        Cookie elsewhere = rep.user(h.company(), new UUID[] {auth.branch(h.company(), "EAST")}, "reporting.hr.read");
        assertThat(rows(rep.run(elsewhere, h.company(), "headcount", ""))).isEmpty();
        // HR's operational permissions do not open the reports.
        rep.get(h.session(), h.company(), "headcount", "").andExpect(status().isForbidden());
    }

    @Test
    void attendanceAndLeaveCountWhatHrRecorded() throws Exception {
        Hr h = hr.setup();
        LocalDate today = h.today();
        UUID employee = hr.employee(h, "E1", LocalDate.of(today.getYear() - 1, 1, 1), null);
        attendance(
                h,
                employee,
                today.minusDays(3),
                Map.of(
                        "status",
                        "PRESENT",
                        "checkIn",
                        today.minusDays(3) + "T08:00:00Z",
                        "checkOut",
                        today.minusDays(3) + "T16:00:00Z"));
        attendance(h, employee, today.minusDays(2), Map.of("status", "ABSENT"));
        attendance(
                h,
                employee,
                today.minusDays(1),
                Map.of(
                        "status",
                        "HALF_DAY",
                        "checkIn",
                        today.minusDays(1) + "T08:00:00Z",
                        "checkOut",
                        today.minusDays(1) + "T12:00:00Z"));
        Cookie analyst = rep.user(h.company(), "reporting.hr.read");

        Map<String, Object> row = rows(rep.run(
                        analyst, h.company(), "attendance", "from=" + today.minusDays(7) + "&to=" + today))
                .getFirst();
        assertThat(row)
                .containsEntry("employeeNumber", "E1")
                .containsEntry("departmentCode", "OPS")
                .containsEntry("presentDays", 1)
                .containsEntry("absentDays", 1)
                .containsEntry("halfDays", 1)
                .containsEntry("recordedDays", 3)
                .containsEntry("workedMinutes", 720);
        assertThat(rows(rep.run(
                        analyst,
                        h.company(),
                        "attendance",
                        "from=" + today.minusDays(2) + "&to=" + today.minusDays(2))))
                .singleElement()
                .satisfies(r -> assertThat(r).containsEntry("recordedDays", 1).containsEntry("absentDays", 1));

        UUID annual = hr.leaveType(h, "AL", "20", "ANNUAL", "5", false);
        hr.accrue(h, LocalDate.of(today.getYear(), 1, 1));
        LocalDate monday = today.plusWeeks(2).with(java.time.DayOfWeek.MONDAY);
        Map<String, Object> request = HrFixtures.leave(annual, monday, monday.plusDays(1));
        request.put("employeeId", employee.toString());
        request.put("halfDay", false);
        UUID leave = hr.create(h.session(), h.path("/leave-requests"), request);
        expect(hr.action(h.session(), h.path("/leave-requests/" + leave + "/submit"), 0, null, null), 200);
        String range = "from=" + monday.minusDays(1) + "&to=" + monday.plusDays(7);
        assertThat(rows(rep.run(analyst, h.company(), "leave", range))).isEmpty();
        assertThat(rows(rep.run(analyst, h.company(), "leave", range + "&status=SUBMITTED")))
                .hasSize(1);
        expect(
                hr.action(
                        h.session(),
                        h.path("/leave-requests/" + leave + "/approve"),
                        hr.version(h, "/leave-requests/" + leave),
                        null,
                        null),
                200);
        Map<String, Object> taken =
                rows(rep.run(analyst, h.company(), "leave", range)).getFirst();
        assertThat(taken)
                .containsEntry("leaveTypeCode", "AL")
                .containsEntry("paid", true)
                .containsEntry("requestCount", 1);
        assertThat(decimal(taken.get("days")))
                .isEqualByComparingTo(decimal(hr.read(h.session(), h.path("/leave-requests/" + leave), "$.days")));
        assertThat(rows(rep.run(
                        analyst, h.company(), "leave", "from=" + monday.plusDays(1) + "&to=" + monday.plusDays(7))))
                .isEmpty();
    }

    @Test
    void thePayrollSummaryEqualsThePostedRunAndNeedsPayrollsPermission() throws Exception {
        Payroll p = payroll.setup();
        LocalDate hired = LocalDate.of(p.year() - 1, 1, 1);
        payroll.employee(p, "E1", hired, "3000");
        payroll.employee(p, "E2", hired, "4000");
        UUID run = payroll.run(p, payroll.period(p, 1), "REGULAR");
        payroll.post(p, run);
        String totals = payroll.runBody(p, run);
        UUID company = p.hr().company();
        Cookie analyst = rep.user(company, "payroll.report.read");
        String january = "from=" + LocalDate.of(p.year(), 1, 1) + "&to=" + LocalDate.of(p.year(), 1, 31);

        String byComponent = rep.run(analyst, company, "payroll-summary", january);
        assertThat(decimal(totals(byComponent).get("earnings")))
                .isEqualByComparingTo(decimal(JsonPath.read(totals, "$.run.grossTotal")));
        assertThat(decimal(totals(byComponent).get("deductions")))
                .isEqualByComparingTo(decimal(JsonPath.read(totals, "$.run.deductionTotal")));
        assertThat(decimal(totals(byComponent).get("employerContributions")))
                .isEqualByComparingTo(decimal(JsonPath.read(totals, "$.run.employerContributionTotal")));
        assertThat(decimal(totals(byComponent).get("netPay")))
                .isEqualByComparingTo(decimal(JsonPath.read(totals, "$.run.netTotal")));
        assertThat(rows(byComponent))
                .anySatisfy(
                        r -> assertThat(r).containsEntry("groupCode", "BASIC").containsEntry("kind", "EARNING"));
        List<Map<String, Object>> byDepartment =
                rows(rep.run(analyst, company, "payroll-summary", january + "&groupBy=DEPARTMENT"));
        assertThat(byDepartment).singleElement().satisfies(r -> assertThat(r).containsEntry("groupCode", "OPS"));
        assertThat(rows(rep.run(analyst, company, "payroll-summary", january + "&groupBy=PERIOD")))
                .singleElement()
                .satisfies(r -> assertThat(r)
                        .containsEntry("groupCode", LocalDate.of(p.year(), 1, 1).toString()));
        // Nothing identifies an employee.
        assertThat(rep.run(analyst, company, "payroll-summary", january)).doesNotContain("E1", "E2", "First");
        assertThat(rows(rep.run(
                        analyst,
                        company,
                        "payroll-summary",
                        "from=" + LocalDate.of(p.year(), 2, 1) + "&to=" + LocalDate.of(p.year(), 2, 28))))
                .isEmpty();

        Cookie hrAnalyst = rep.user(company, "reporting.hr.read");
        rep.get(hrAnalyst, company, "payroll-summary", january).andExpect(status().isForbidden());
    }

    private UUID employee(Hr h, String number, LocalDate hireDate, UUID department) throws Exception {
        UUID id = hr.create(
                h.session(),
                h.path("/employees"),
                OrgFixtures.map(
                        "employeeNumber",
                        number,
                        "firstName",
                        "First" + number,
                        "lastName",
                        "Last" + number,
                        "hireDate",
                        hireDate,
                        "initialAssignment",
                        OrgFixtures.assignment(h.branch(), department, null, null)));
        expect(
                hr.action(
                        h.session(),
                        h.path("/employees/" + id + "/activate"),
                        hr.version(h, "/employees/" + id),
                        null,
                        null),
                200);
        return id;
    }

    private void attendance(Hr h, UUID employee, LocalDate day, Map<String, String> body) throws Exception {
        mvc.perform(unsafe(put(h.path("/employees/" + employee + "/attendance/" + day)))
                        .cookie(h.session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OrgFixtures.JSON_MAPPER.writeValueAsString(body)))
                .andExpect(status().isOk());
    }
}
