package com.erp.hr.web;

import static com.erp.support.HrFixtures.expect;
import static com.erp.support.HrFixtures.firstMonday;
import static com.erp.support.HrFixtures.leave;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erp.hr.application.LeaveStatusSync;
import com.erp.platform.context.CurrentContext;
import com.erp.platform.context.RequestContext;
import com.erp.support.HrFixtures;
import com.erp.support.HrFixtures.Hr;
import com.erp.support.HrFixtures.Person;
import com.erp.support.IntegrationTest;
import com.erp.support.OrgFixtures;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Leave (PRODUCT_SPEC.md §10.1, HR-2): accruals and carry-forward on the append-only ledger, requests
 * counted in working days (weekends and public holidays excluded), balance and overlap checks,
 * approval by HR or the manager with segregation of duties, self-service, and the ON_LEAVE status.
 */
class LeaveIntegrationTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    HrFixtures hr;

    @Autowired
    LeaveStatusSync statusSync;

    @Autowired
    DSLContext dsl;

    @Autowired
    TransactionTemplate tx;

    private Hr h;
    private int year;
    private UUID annual;
    private UUID sick;
    private UUID unpaid;
    private UUID veteran;

    @BeforeEach
    void setUp() throws Exception {
        h = hr.setup();
        year = h.today().getYear() + 1;
        annual = hr.leaveType(h, "AL", "24", "ANNUAL", "5", false);
        sick = hr.leaveType(h, "SICK", "12", "MONTHLY", "0", false);
        unpaid = hr.leaveType(h, "UNPAID", "0", "ANNUAL", "0", true);
        veteran = hr.employee(h, "E001", LocalDate.of(year - 2, 1, 1), null);
    }

    @Test
    void accrualsAreProratedIdempotentAndCarriedForward() throws Exception {
        UUID joiner = hr.employee(h, "E002", LocalDate.of(year, 7, 1), null);
        hr.accrue(h, LocalDate.of(year, 3, 15));
        assertThat(balance(veteran, annual, year)).isEqualByComparingTo("24");
        assertThat(balance(veteran, sick, year)).isEqualByComparingTo("3");
        assertThat(balance(joiner, annual, year)).isEqualByComparingTo("0");

        hr.accrue(h, LocalDate.of(year, 12, 31));
        hr.accrue(h, LocalDate.of(year, 12, 31)); // idempotent
        assertThat(balance(veteran, annual, year)).isEqualByComparingTo("24");
        assertThat(balance(veteran, sick, year)).isEqualByComparingTo("12");
        assertThat(balance(joiner, annual, year)).isEqualByComparingTo("12"); // July–December
        assertThat(balance(joiner, sick, year)).isEqualByComparingTo("6");

        // 10 days taken; the next year carries at most 5 and closes the old year.
        LocalDate monday = firstMonday(year, 9);
        approve(request(veteran, annual, monday, monday.plusDays(11)));
        assertThat(balance(veteran, annual, year)).isEqualByComparingTo("14");
        hr.accrue(h, LocalDate.of(year + 1, 1, 2));
        assertThat(balance(veteran, annual, year)).isEqualByComparingTo("0");
        assertThat(balance(veteran, annual, year + 1)).isEqualByComparingTo("29");
        String ledger = hr.body(
                h.session(),
                h.path("/leave-ledger?filter[employeeId]=" + veteran + "&filter[leaveTypeId]=" + annual + "&limit=50"));
        assertThat(JsonPath.<List<String>>read(ledger, "$.data[*].entryType"))
                .contains("ACCRUAL", "TAKEN", "EXPIRY", "CARRY_FORWARD");
    }

    @Test
    void requestsCountWorkingDaysAndRespectBalanceAndOverlap() throws Exception {
        hr.accrue(h, LocalDate.of(year, 1, 1));
        LocalDate monday = firstMonday(year, 3);
        expect(
                hr.post(
                        h.session(),
                        h.path("/public-holidays"),
                        OrgFixtures.map("date", monday.plusDays(2), "name", "Founders' Day")),
                201);
        // Monday to Sunday: five weekdays, one of them a holiday.
        UUID request = request(veteran, annual, monday, monday.plusDays(6));
        assertThat(days(request)).isEqualByComparingTo("4");
        submit(request);
        String balances = hr.body(h.session(), h.path("/leave-balances?employeeId=" + veteran + "&year=" + year));
        assertThat(JsonPath.<List<String>>read(balances, "$.data[?(@.leaveTypeCode=='AL')].pending"))
                .containsExactly("4.00");
        assertThat(JsonPath.<List<String>>read(balances, "$.data[?(@.leaveTypeCode=='AL')].available"))
                .containsExactly("20.00");
        expect(
                hr.action(h.session(), h.path("/leave-requests/" + request + "/approve"), version(request), null, null),
                200);
        assertThat(balance(veteran, annual, year)).isEqualByComparingTo("20");

        // Overlapping leave is refused at submission.
        UUID overlapping = request(veteran, unpaid, monday.plusDays(4), monday.plusDays(4));
        hr.action(h.session(), h.path("/leave-requests/" + overlapping + "/submit"), version(overlapping), null, null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LEAVE_OVERLAP"));
        // More than the balance is refused; a type allowing a negative balance is not limited.
        LocalDate april = firstMonday(year, 4);
        UUID tooLong = request(veteran, annual, april, april.plusDays(40));
        hr.action(h.session(), h.path("/leave-requests/" + tooLong + "/submit"), version(tooLong), null, null)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("LEAVE_BALANCE_INSUFFICIENT"));
        UUID unpaidLeave = request(veteran, unpaid, april, april.plusDays(4));
        approve(unpaidLeave);
        assertThat(balance(veteran, unpaid, year)).isEqualByComparingTo("-5");

        // Weekends only, a year end crossed, a half day.
        LocalDate saturday = april.plusDays(12);
        hr.post(h.session(), h.path("/leave-requests"), body(veteran, annual, saturday, saturday.plusDays(1), false))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("NO_WORKING_DAYS"));
        hr.post(
                        h.session(),
                        h.path("/leave-requests"),
                        body(veteran, annual, LocalDate.of(year, 12, 30), LocalDate.of(year + 1, 1, 2), false))
                .andExpect(status().isUnprocessableContent());
        String half = hr.post(
                        h.session(),
                        h.path("/leave-requests"),
                        body(veteran, annual, april.plusDays(14), april.plusDays(14), true))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat((String) JsonPath.read(half, "$.days")).isEqualTo("0.50");

        // Cancelling approved leave gives the days back; a rejected request books nothing.
        expect(
                hr.action(h.session(), h.path("/leave-requests/" + request + "/cancel"), version(request), null, null),
                200);
        assertThat(balance(veteran, annual, year)).isEqualByComparingTo("24");
        UUID rejected = request(veteran, annual, firstMonday(year, 5), firstMonday(year, 5));
        submit(rejected);
        expect(
                hr.action(
                        h.session(),
                        h.path("/leave-requests/" + rejected + "/reject"),
                        version(rejected),
                        null,
                        OrgFixtures.map("note", "Peak season")),
                200);
        assertThat(balance(veteran, annual, year)).isEqualByComparingTo("24");
    }

    @Test
    void employeesRequestAndManagersDecide() throws Exception {
        hr.accrue(h, LocalDate.of(year, 1, 1));
        UUID managerId = hr.employee(h, "M001", LocalDate.of(year - 3, 1, 1), null);
        UUID reportId = hr.employee(h, "R001", LocalDate.of(year - 3, 1, 1), managerId);
        UUID outsiderId = hr.employee(h, "O001", LocalDate.of(year - 3, 1, 1), null);
        hr.accrue(h, LocalDate.of(year, 1, 1));
        Person manager = hr.person(h, managerId);
        Person report = hr.person(h, reportId);
        Person outsider = hr.person(h, outsiderId);

        LocalDate monday = firstMonday(year, 6);
        String created = hr.post(
                        report.session(), h.path("/me/leave-requests"), leave(annual, monday, monday.plusDays(1)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        UUID request = UUID.fromString(JsonPath.read(created, "$.id"));
        assertThat((String) JsonPath.read(created, "$.employeeId")).isEqualTo(reportId.toString());
        expect(hr.action(report.session(), h.path("/me/leave-requests/" + request + "/submit"), 0, null, null), 200);

        // The manager sees and decides their team's requests; others do not.
        assertThat(JsonPath.<List<String>>read(
                        hr.body(manager.session(), h.path("/me/team/leave-requests?filter[status]=SUBMITTED")),
                        "$.data[*].id"))
                .containsExactly(request.toString());
        assertThat(JsonPath.<List<String>>read(
                        hr.body(manager.session(), h.path("/me/team")), "$.data[*].employeeNumber"))
                .containsExactly("R001");
        hr.action(outsider.session(), h.path("/me/team/leave-requests/" + request + "/approve"), 1, null, null)
                .andExpect(status().isNotFound());
        hr.action(report.session(), h.path("/me/team/leave-requests/" + request + "/approve"), 1, null, null)
                .andExpect(status().isNotFound());
        mvc.perform(get(h.path("/leave-requests")).cookie(report.session())).andExpect(status().isForbidden());
        expect(
                hr.action(manager.session(), h.path("/me/team/leave-requests/" + request + "/approve"), 1, null, null),
                200);
        assertThat(JsonPath.<List<String>>read(
                        hr.body(report.session(), h.path("/me/leave-balances?year=" + year)),
                        "$.data[?(@.leaveTypeCode=='AL')].balance"))
                .containsExactly("22.00");
        // The employee cancels their approved future leave.
        expect(hr.action(report.session(), h.path("/me/leave-requests/" + request + "/cancel"), 2, null, null), 200);

        // HR does not decide its own leave (SoD).
        UUID hrEmployee = hr.employee(h, "H001", LocalDate.of(year - 3, 1, 1), null);
        hr.accrue(h, LocalDate.of(year, 1, 1));
        linkHrUser(hrEmployee);
        UUID own = request(hrEmployee, annual, monday, monday);
        submit(own);
        hr.action(h.session(), h.path("/leave-requests/" + own + "/approve"), version(own), null, null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SOD_VIOLATION"));
    }

    @Test
    void approvedLeaveCoveringTodayPutsTheEmployeeOnLeave() throws Exception {
        LocalDate today = h.today();
        hr.accrue(h, today);
        UUID employee = hr.employee(h, "E003", LocalDate.of(today.getYear() - 1, 1, 1), null);
        hr.accrue(h, today);
        // Leave from today to the next working day in the same year (unpaid: no balance needed).
        LocalDate end = today.getMonthValue() == 12 && today.getDayOfMonth() > 28 ? today : today.plusDays(3);
        LocalDate start = today.getDayOfWeek() == DayOfWeek.SATURDAY || today.getDayOfWeek() == DayOfWeek.SUNDAY
                ? today.with(TemporalAdjusters.previous(DayOfWeek.FRIDAY))
                : today;
        if (start.getYear() != end.getYear()) {
            start = today;
        }
        UUID request = request(employee, unpaid, start, end.isBefore(start) ? start : end);
        approve(request);
        assertThat(employeeStatus(employee)).isEqualTo("ON_LEAVE");
        expect(
                hr.action(h.session(), h.path("/leave-requests/" + request + "/cancel"), version(request), null, null),
                200);
        assertThat(employeeStatus(employee)).isEqualTo("ACTIVE");

        // The daily run moves employees whose approved leave starts later.
        LocalDate next = firstMonday(year, 2);
        UUID later = request(employee, unpaid, next, next);
        approve(later);
        assertThat(employeeStatus(employee)).isEqualTo("ACTIVE");
        int changed = CurrentContext.callWith(
                RequestContext.forRequest("job-" + UUID.randomUUID()).withCompany(h.company()),
                () -> statusSync.syncCompany(h.company(), next));
        assertThat(changed).isEqualTo(1);
        assertThat(employeeStatus(employee)).isEqualTo("ON_LEAVE");
    }

    @Test
    void theLedgerIsAppendOnlyAndAdjustedIdempotently() throws Exception {
        hr.accrue(h, LocalDate.of(year, 1, 1));
        Map<String, Object> adjustment = OrgFixtures.map(
                "employeeId", veteran, "leaveTypeId", annual, "year", year, "days", "-1.5", "note", "Correction");
        String key = "adjust-" + UUID.randomUUID();
        expect(hr.action(h.session(), h.path("/leave-ledger/adjustments"), -1, key, adjustment), 201);
        expect(hr.action(h.session(), h.path("/leave-ledger/adjustments"), -1, key, adjustment), 201);
        assertThat(balance(veteran, annual, year)).isEqualByComparingTo("22.5");

        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() -> CurrentContext.callWith(
                RequestContext.forRequest("raw-" + UUID.randomUUID()).withCompany(h.company()),
                () -> tx.execute(
                        s -> dsl.execute("UPDATE hr.leave_ledger SET days = 100 WHERE employee_id = ?", veteran))));
        PSQLException error = null;
        for (Throwable t = thrown; t != null; t = t.getCause()) {
            if (t instanceof PSQLException e) {
                error = e;
            }
        }
        assertThat((Object) error).isNotNull();
        assertThat(error.getServerErrorMessage().getConstraint()).isEqualTo("ck_leave_ledger__immutable");
    }

    // ------------------------------------------------------------------------------ helpers

    private UUID request(UUID employee, UUID type, LocalDate start, LocalDate end) throws Exception {
        return hr.create(h.session(), h.path("/leave-requests"), body(employee, type, start, end, false));
    }

    private static Map<String, Object> body(UUID employee, UUID type, LocalDate start, LocalDate end, boolean half) {
        Map<String, Object> body = leave(type, start, end);
        body.put("employeeId", employee.toString());
        body.put("halfDay", half);
        return body;
    }

    private void submit(UUID request) throws Exception {
        expect(
                hr.action(h.session(), h.path("/leave-requests/" + request + "/submit"), version(request), null, null),
                200);
    }

    private void approve(UUID request) throws Exception {
        submit(request);
        expect(
                hr.action(h.session(), h.path("/leave-requests/" + request + "/approve"), version(request), null, null),
                200);
    }

    private int version(UUID request) throws Exception {
        return hr.version(h, "/leave-requests/" + request);
    }

    private BigDecimal days(UUID request) throws Exception {
        return new BigDecimal(hr.<String>read(h.session(), h.path("/leave-requests/" + request), "$.days"));
    }

    private BigDecimal balance(UUID employee, UUID type, int y) throws Exception {
        List<String> values = JsonPath.read(
                hr.body(h.session(), h.path("/leave-balances?employeeId=" + employee + "&year=" + y)),
                "$.data[?(@.leaveTypeId=='" + type + "')].balance");
        return values.isEmpty() ? BigDecimal.ZERO : new BigDecimal(values.getFirst());
    }

    private String employeeStatus(UUID employee) throws Exception {
        return hr.read(h.session(), h.path("/employees/" + employee), "$.status");
    }

    private void linkHrUser(UUID employee) throws Exception {
        expect(
                mvc.perform(com.erp.support.AuthTestSupport.unsafe(
                                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                                        h.path("/employees/" + employee + "/user")))
                        .cookie(h.session())
                        .header("If-Match", OrgFixtures.etag(hr.version(h, "/employees/" + employee)))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(OrgFixtures.json("userId", h.user().id()))),
                200);
    }
}
