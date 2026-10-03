package com.erp.hr.web;

import static com.erp.support.AuthTestSupport.unsafe;
import static com.erp.support.OrgFixtures.assignment;
import static com.erp.support.OrgFixtures.etag;
import static com.erp.support.OrgFixtures.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erp.support.AuthTestSupport;
import com.erp.support.IntegrationTest;
import com.erp.support.OrgFixtures;
import com.erp.support.OrgFixtures.Admin;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

/** Employment assignments: the employee ↔ branch / department / position / manager relationships. */
class EmploymentAssignmentIntegrationTest extends IntegrationTest {

    private static final String MERGE_PATCH = "application/merge-patch+json";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    MockMvc mvc;

    @Autowired
    AuthTestSupport auth;

    @Autowired
    OrgFixtures fixtures;

    private final LocalDate today = LocalDate.now(ZoneId.of("America/New_York"));
    private Admin admin;
    private UUID branch;
    private UUID department;

    @BeforeEach
    void structure() throws Exception {
        admin = fixtures.admin();
        branch = auth.branch(admin.company(), "MAIN");
        department = fixtures.department(admin, "OPS", null, null);
    }

    @Test
    void assignmentsOfOneEmployeeNeverOverlap() throws Exception {
        UUID employee = fixtures.employee(admin, "E1", today.minusYears(1), branch, department);

        create(employee, body(branch, department, null, null, today.minusMonths(1), null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ASSIGNMENT_OVERLAP"));
        UUID current = assignments(employee).getFirst();
        // End the current assignment, then start the next one the day after.
        patchAssignment(employee, current, 0, json("effectiveTo", today.toString()))
                .andExpect(status().isOk());
        UUID next = UUID.fromString(com.jayway.jsonpath.JsonPath.read(
                create(employee, body(branch, department, null, null, today.plusDays(1), null))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.id"));
        // Extending the first assignment into the second is an overlap too.
        patchAssignment(
                        employee,
                        current,
                        1,
                        json("effectiveTo", today.plusDays(5).toString()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ASSIGNMENT_OVERLAP"));
        mvc.perform(get(admin.path("/employees/" + employee + "/assignments")).cookie(admin.session()))
                .andExpect(jsonPath("$.data[*].id").value(contains(current.toString(), next.toString())));
    }

    @Test
    void concurrentOverlappingAssignmentsLeaveExactlyOne() throws Exception {
        UUID employee = fixtures.employee(admin, "E2", today.minusYears(1), null);

        var first = CompletableFuture.supplyAsync(
                () -> statusOf(create(employee, body(branch, department, null, null, today, null))));
        var second = CompletableFuture.supplyAsync(
                () -> statusOf(create(employee, body(branch, department, null, null, today.plusDays(3), null))));

        assertThat(List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder(201, 409);
        assertThat(assignments(employee)).hasSize(1);
    }

    @Test
    void unitsMustBeConsistentActiveAndOfTheSameCompany() throws Exception {
        UUID employee = fixtures.employee(admin, "E3", today.minusYears(1), null);
        UUID otherBranch = auth.branch(admin.company(), "SIDE");
        UUID branchDepartment = fixtures.department(admin, "SIDE-OPS", null, otherBranch);
        UUID sales = fixtures.department(admin, "SALES", null, null);
        UUID salesPosition = fixtures.position(admin, "SALES-REP", sales);
        Admin other = fixtures.admin();
        UUID foreignBranch = auth.branch(other.company(), "FOREIGN");
        UUID foreignDepartment = fixtures.department(other, "FOREIGN", null, null);
        UUID foreignPosition = fixtures.position(other, "FOREIGN", null);

        create(employee, body(foreignBranch, foreignDepartment, foreignPosition, null, today, null))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[*].code")
                        .value(containsInAnyOrder("UNKNOWN_BRANCH", "UNKNOWN_DEPARTMENT", "UNKNOWN_POSITION")));
        create(employee, body(branch, branchDepartment, null, null, today, null))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].code").value("DEPARTMENT_BRANCH_MISMATCH"));
        create(employee, body(branch, department, salesPosition, null, today, null))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].code").value("POSITION_DEPARTMENT_MISMATCH"));
        create(employee, body(branch, department, null, null, today.minusYears(2), null))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].code").value("BEFORE_HIRE"));
        create(employee, body(branch, department, null, null, today, today.minusDays(1)))
                .andExpect(status().isUnprocessableContent());
        create(employee, "{\"branchId\":\"" + branch + "\",\"departmentId\":\"" + department + "\",\"fte\":\"1.5\"}")
                .andExpect(status().isUnprocessableContent());

        mvc.perform(unsafe(post(admin.path("/departments/" + sales + "/deactivate")))
                        .cookie(admin.session())
                        .header("If-Match", etag(0)))
                .andExpect(status().isConflict()); // the position SALES-REP is still active
        create(employee, body(otherBranch, branchDepartment, null, null, today, null))
                .andExpect(status().isCreated());
    }

    @Test
    void managersMustBeEmployedAndReportingLinesCannotCycle() throws Exception {
        UUID boss = fixtures.employee(admin, "BOSS", today.minusYears(3), branch, department);
        UUID lead = fixtures.employee(admin, "LEAD", today.minusYears(2), assignment(branch, department, null, boss));
        UUID staff = fixtures.employee(admin, "STAFF", today.minusYears(1), null);
        UUID newcomer = fixtures.employee(admin, "NEW", today.plusMonths(1), null);
        UUID foreignEmployee = fixtures.employee(fixtures.admin(), "FOREIGN", today.minusYears(1), null);

        create(staff, body(branch, department, null, staff, today, null))
                .andExpect(jsonPath("$.errors[0].code").value("SELF"));
        create(staff, body(branch, department, null, foreignEmployee, today, null))
                .andExpect(jsonPath("$.errors[0].code").value("UNKNOWN_EMPLOYEE"));
        create(staff, body(branch, department, null, newcomer, today, null))
                .andExpect(jsonPath("$.errors[0].code").value("MANAGER_NOT_EMPLOYED"));
        create(staff, body(branch, department, null, lead, today, null)).andExpect(status().isCreated());

        // boss → lead → staff: making the boss report to staff closes a loop. (The boss's existing
        // assignment started before staff was hired, so that line fails earlier: MANAGER_NOT_EMPLOYED.)
        UUID bossAssignment = assignments(boss).getFirst();
        patchAssignment(boss, bossAssignment, 0, json("managerEmployeeId", staff.toString()))
                .andExpect(jsonPath("$.errors[0].code").value("MANAGER_NOT_EMPLOYED"));
        patchAssignment(
                        boss,
                        bossAssignment,
                        0,
                        json("effectiveTo", today.minusDays(1).toString()))
                .andExpect(status().isOk());
        create(boss, body(branch, department, null, staff, today, null))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].code").value("REPORTING_CYCLE"));
    }

    /** A reporting line that only becomes a loop at a later date is caught too. */
    @Test
    void cyclesAreDetectedAcrossTime() throws Exception {
        UUID a = fixtures.employee(admin, "A", today.minusYears(1), null);
        UUID b = fixtures.employee(admin, "B", today.minusYears(1), null);
        // From next month, B reports to A.
        create(
                        b,
                        body(
                                branch,
                                department,
                                null,
                                null,
                                today.minusYears(1),
                                today.plusMonths(1).minusDays(1)))
                .andExpect(status().isCreated());
        create(b, body(branch, department, null, a, today.plusMonths(1), null)).andExpect(status().isCreated());

        // A reporting to B from today (open-ended) would loop from next month on.
        create(a, body(branch, department, null, b, today, null))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].code").value("REPORTING_CYCLE"));
        // Until the end of this month it is fine.
        create(
                        a,
                        body(
                                branch,
                                department,
                                null,
                                b,
                                today.minusMonths(1),
                                today.plusMonths(1).minusDays(1)))
                .andExpect(status().isCreated());
    }

    @Test
    void onlyFutureAssignmentsCanBeDeletedAndUsedUnitsCannotBeDeactivated() throws Exception {
        UUID employee = fixtures.employee(admin, "E4", today.minusYears(1), branch, department);
        UUID current = assignments(employee).getFirst();

        mvc.perform(unsafe(delete(admin.path("/employees/" + employee + "/assignments/" + current)))
                        .cookie(admin.session())
                        .header("If-Match", etag(0)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE"));
        mvc.perform(unsafe(post(admin.path("/branches/" + branch + "/deactivate")))
                        .cookie(admin.session())
                        .header("If-Match", etag(0)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("employment assignment")));
        mvc.perform(unsafe(post(admin.path("/departments/" + department + "/deactivate")))
                        .cookie(admin.session())
                        .header("If-Match", etag(0)))
                .andExpect(status().isConflict());

        patchAssignment(
                        employee,
                        current,
                        0,
                        json("effectiveTo", today.minusDays(1).toString()))
                .andExpect(status().isOk());
        UUID future = UUID.fromString(com.jayway.jsonpath.JsonPath.read(
                create(employee, body(branch, department, null, null, today.plusMonths(2), null))
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.id"));
        mvc.perform(unsafe(delete(admin.path("/employees/" + employee + "/assignments/" + future)))
                        .cookie(admin.session())
                        .header("If-Match", etag(0)))
                .andExpect(status().isNoContent());
        // Nothing current or future remains: the department can now be deactivated.
        mvc.perform(unsafe(post(admin.path("/departments/" + department + "/deactivate")))
                        .cookie(admin.session())
                        .header("If-Match", etag(0)))
                .andExpect(status().isOk());
        // Bringing the old assignment back to the present needs an active department.
        patchAssignment(employee, current, 1, json("effectiveTo", OrgFixtures.NULL))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].code").value("INACTIVE"));
    }

    @Test
    void theCompanyWideListShowsTheStructureOnADate() throws Exception {
        UUID support = fixtures.department(admin, "SUPPORT", null, null);
        UUID boss = fixtures.employee(admin, "M1", today.minusYears(2), branch, department);
        fixtures.employee(admin, "M2", today.minusYears(1), assignment(branch, department, null, boss));
        UUID mover = fixtures.employee(admin, "M3", today.minusYears(1), branch, department);
        patchAssignment(
                        mover,
                        assignments(mover).getFirst(),
                        0,
                        json("effectiveTo", today.minusDays(1).toString()))
                .andExpect(status().isOk());
        create(mover, body(branch, support, null, null, today, null)).andExpect(status().isCreated());

        mvc.perform(get(admin.path("/employment-assignments"))
                        .cookie(admin.session())
                        .param("asOf", today.toString())
                        .param("filter[departmentId]", department.toString()))
                .andExpect(jsonPath("$.data.length()").value(2));
        mvc.perform(get(admin.path("/employment-assignments"))
                        .cookie(admin.session())
                        .param("asOf", today.minusDays(1).toString())
                        .param("filter[departmentId]", department.toString()))
                .andExpect(jsonPath("$.data.length()").value(3));
        mvc.perform(get(admin.path("/employment-assignments"))
                        .cookie(admin.session())
                        .param("filter[managerEmployeeId]", boss.toString()))
                .andExpect(jsonPath("$.data.length()").value(1));
        mvc.perform(get(admin.path("/employment-assignments"))
                        .cookie(admin.session())
                        .param("asOf", "yesterday"))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------------------ helpers

    private String body(
            UUID branchId, UUID departmentId, UUID positionId, UUID managerId, LocalDate from, LocalDate to) {
        Map<String, Object> body = assignment(branchId, departmentId, positionId, managerId);
        body.put("effectiveFrom", from.toString());
        if (to != null) {
            body.put("effectiveTo", to.toString());
        }
        return JSON.writeValueAsString(body);
    }

    private ResultActions create(UUID employee, String body) {
        try {
            return mvc.perform(unsafe(post(admin.path("/employees/" + employee + "/assignments")))
                    .cookie(admin.session())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private ResultActions patchAssignment(UUID employee, UUID assignment, int version, String body) throws Exception {
        return mvc.perform(unsafe(patch(admin.path("/employees/" + employee + "/assignments/" + assignment)))
                .cookie(admin.session())
                .header("If-Match", etag(version))
                .contentType(MERGE_PATCH)
                .content(body));
    }

    private List<UUID> assignments(UUID employee) throws Exception {
        String body = mvc.perform(get(admin.path("/employees/" + employee + "/assignments"))
                        .cookie(admin.session()))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return com.jayway.jsonpath.JsonPath.<List<String>>read(body, "$.data[*].id").stream()
                .map(UUID::fromString)
                .toList();
    }

    private static int statusOf(ResultActions actions) {
        return actions.andReturn().getResponse().getStatus();
    }
}
