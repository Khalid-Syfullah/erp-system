package com.erp.hr.web;

import static com.erp.support.AuthTestSupport.unsafe;
import static com.erp.support.OrgFixtures.assignment;
import static com.erp.support.OrgFixtures.etag;
import static com.erp.support.OrgFixtures.json;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erp.support.AuthTestSupport;
import com.erp.support.AuthTestSupport.TestUser;
import com.erp.support.IntegrationTest;
import com.erp.support.OrgFixtures;
import com.erp.support.OrgFixtures.Admin;
import jakarta.servlet.http.Cookie;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Employees: lifecycle, consistency, branch isolation, list features and audit (API.md §17.9). */
class EmployeeIntegrationTest extends IntegrationTest {

    private static final String MERGE_PATCH = "application/merge-patch+json";

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
    void employeesAreCreatedWithTheirPlaceInTheOrganization() throws Exception {
        UUID position = fixtures.position(admin, "CLERK", department);
        UUID employee =
                fixtures.employee(admin, "E100", today.minusMonths(6), assignment(branch, department, position, null));

        mvc.perform(get(admin.path("/employees/" + employee)).cookie(admin.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ONBOARDING"))
                .andExpect(jsonPath("$.currentAssignment.positionId").value(position.toString()))
                .andExpect(jsonPath("$.currentAssignment.fte").value("1.0000"))
                .andExpect(jsonPath("$.currentAssignment.effectiveFrom")
                        .value(today.minusMonths(6).toString()));
        create(json("employeeNumber", "E100", "firstName", "Dup", "lastName", "Licate", "hireDate", today.toString()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_CODE"));
        create(json(
                        "employeeNumber",
                        "E101",
                        "firstName",
                        "Ann",
                        "lastName",
                        "Lee",
                        "hireDate",
                        today.toString(),
                        "workEmail",
                        "Ann.Lee@Example.test"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.workEmail").value("ann.lee@example.test"))
                .andExpect(jsonPath("$.currentAssignment").doesNotExist());
        create(json(
                        "employeeNumber",
                        "E102",
                        "firstName",
                        "Ann",
                        "lastName",
                        "Other",
                        "hireDate",
                        today.toString(),
                        "workEmail",
                        "ANN.LEE@example.test"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_WORK_EMAIL"));
        create(json(
                        "employeeNumber",
                        "E103",
                        "firstName",
                        "X",
                        "lastName",
                        "Y",
                        "hireDate",
                        today.toString(),
                        "workEmail",
                        "not-an-email"))
                .andExpect(status().isUnprocessableContent());
        create(json("employeeNumber", "e 104", "firstName", "X", "lastName", "Y", "hireDate", today.toString()))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void profileUpdatesKeepDatesConsistent() throws Exception {
        UUID employee = fixtures.employee(admin, "E200", today.minusYears(1), branch, department);

        patchEmployee(employee, 0, json("preferredName", "Sam", "lastName", "  Smith  "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.preferredName").value("Sam"))
                .andExpect(jsonPath("$.lastName").value("Smith"));
        patchEmployee(employee, 1, json("hireDate", today.toString()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].code").value("AFTER_FIRST_ASSIGNMENT"));
        patchEmployee(employee, 1, json("hireDate", today.minusYears(2).toString()))
                .andExpect(status().isOk());
        patchEmployee(employee, 1, json("firstName", "Stale")).andExpect(status().isPreconditionFailed());
        patchEmployee(employee, 2, json("employeeNumber", "E999")).andExpect(status().isBadRequest());
        patchEmployee(employee, 2, json("firstName", OrgFixtures.NULL)).andExpect(status().isUnprocessableContent());
    }

    @Test
    void terminationEndsTheEmployeesPlacesAndMakesTheRecordReadOnly() throws Exception {
        UUID manager = fixtures.employee(admin, "MGR", today.minusYears(2), branch, department);
        UUID report =
                fixtures.employee(admin, "REP", today.minusYears(1), assignment(branch, department, null, manager));
        fixtures.create(
                admin,
                "/department-heads",
                json(
                        "departmentId",
                        department,
                        "employeeId",
                        manager,
                        "effectiveFrom",
                        today.minusYears(1).toString()));
        action(manager, "activate", 0)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        action(manager, "activate", 1).andExpect(status().isConflict());

        terminate(manager, 1, today.minusYears(3)).andExpect(status().isUnprocessableContent());
        // Someone still reports to the manager after the termination date.
        terminate(manager, 1, today)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("report")));
        UUID reportAssignment = UUID.fromString(com.jayway.jsonpath.JsonPath.read(
                mvc.perform(get(admin.path("/employees/" + report + "/assignments"))
                                .cookie(admin.session()))
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.data[0].id"));
        mvc.perform(unsafe(patch(admin.path("/employees/" + report + "/assignments/" + reportAssignment)))
                        .cookie(admin.session())
                        .header("If-Match", etag(0))
                        .contentType(MERGE_PATCH)
                        .content(json("managerEmployeeId", OrgFixtures.NULL)))
                .andExpect(status().isOk());

        // Termination needs hr.employee.terminate, which HR officers do not have.
        TestUser officer = auth.user();
        auth.assign(officer, "HR_OFFICER", admin.company());
        mvc.perform(unsafe(post(admin.path("/employees/" + manager + "/terminate")))
                        .cookie(auth.login(officer))
                        .header("If-Match", etag(1))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("terminationDate", today.toString())))
                .andExpect(status().isForbidden());

        terminate(manager, 1, today)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("TERMINATED"))
                .andExpect(jsonPath("$.terminationDate").value(today.toString()))
                .andExpect(jsonPath("$.currentAssignment.effectiveTo").value(today.toString()));
        mvc.perform(get(admin.path("/department-heads"))
                        .cookie(admin.session())
                        .param("filter[employeeId]", manager.toString()))
                .andExpect(jsonPath("$.data[0].effectiveTo").value(today.toString()));
        patchEmployee(manager, 2, json("firstName", "Ghost"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE"));
        mvc.perform(unsafe(post(admin.path("/employees/" + manager + "/assignments")))
                        .cookie(admin.session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(
                                "branchId",
                                branch,
                                "departmentId",
                                department,
                                "effectiveFrom",
                                today.plusDays(1).toString())))
                .andExpect(status().isConflict());
        terminate(manager, 2, today).andExpect(status().isConflict());
    }

    @Test
    void branchRestrictedUsersSeeAndChangeOnlyTheirBranches() throws Exception {
        UUID north = auth.branch(admin.company(), "NORTH");
        UUID south = auth.branch(admin.company(), "SOUTH");
        UUID northEmployee = fixtures.employee(admin, "N1", today.minusYears(1), north, department);
        UUID southEmployee = fixtures.employee(admin, "S1", today.minusYears(1), south, department);
        UUID unplaced = fixtures.employee(admin, "U1", today.minusYears(1), null);
        TestUser officer = auth.user();
        auth.assign(officer, "HR_OFFICER", admin.company(), north);
        Cookie session = auth.login(officer);

        mvc.perform(get(admin.path("/employees")).cookie(session))
                .andExpect(jsonPath("$.data[*].employeeNumber").value(contains("N1")));
        mvc.perform(get(admin.path("/employees/" + southEmployee)).cookie(session))
                .andExpect(status().isNotFound());
        mvc.perform(get(admin.path("/employees/" + unplaced)).cookie(session)).andExpect(status().isNotFound());
        mvc.perform(get(admin.path("/employees/" + northEmployee)).cookie(session))
                .andExpect(status().isOk());
        mvc.perform(unsafe(patch(admin.path("/employees/" + southEmployee)))
                        .cookie(session)
                        .header("If-Match", etag(0))
                        .contentType(MERGE_PATCH)
                        .content(json("firstName", "Hijacked")))
                .andExpect(status().isNotFound());
        mvc.perform(get(admin.path("/employment-assignments")).cookie(session))
                .andExpect(jsonPath("$.data[*].branchId").value(not(hasItem(south.toString()))));

        String newcomer =
                json("employeeNumber", "N2", "firstName", "New", "lastName", "Comer", "hireDate", today.toString());
        mvc.perform(unsafe(post(admin.path("/employees")))
                        .cookie(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(newcomer))
                .andExpect(status().isForbidden());
        mvc.perform(unsafe(post(admin.path("/employees")))
                        .cookie(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(
                                "employeeNumber",
                                "N2",
                                "firstName",
                                "New",
                                "lastName",
                                "Comer",
                                "hireDate",
                                today.toString(),
                                "initialAssignment",
                                assignment(south, department, null, null))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].code").value("UNKNOWN_BRANCH"));
        mvc.perform(unsafe(post(admin.path("/employees")))
                        .cookie(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(
                                "employeeNumber",
                                "N2",
                                "firstName",
                                "New",
                                "lastName",
                                "Comer",
                                "hireDate",
                                today.toString(),
                                "initialAssignment",
                                assignment(north, department, null, null))))
                .andExpect(status().isCreated());
    }

    @Test
    void employeesOfOtherCompaniesAreInvisible() throws Exception {
        Admin other = fixtures.admin();
        UUID foreign = fixtures.employee(other, "F1", today.minusYears(1), null);

        mvc.perform(get(admin.path("/employees/" + foreign)).cookie(admin.session()))
                .andExpect(status().isNotFound());
        mvc.perform(get(admin.path("/employees/" + foreign + "/assignments")).cookie(admin.session()))
                .andExpect(status().isNotFound());
        action(foreign, "activate", 0).andExpect(status().isNotFound());
        mvc.perform(get(other.path("/employees/" + foreign)).cookie(admin.session()))
                .andExpect(status().isNotFound());
    }

    @Test
    void listsSupportFiltersSearchSortingAndPaging() throws Exception {
        fixtures.employee(admin, "L1", today.minusYears(3), branch, department);
        UUID second = fixtures.employee(admin, "L2", today.minusYears(2), branch, department);
        fixtures.employee(admin, "L3", today.minusYears(1), branch, department);
        action(second, "activate", 0).andExpect(status().isOk());

        mvc.perform(get(admin.path("/employees")).cookie(admin.session()).param("filter[status]", "ACTIVE"))
                .andExpect(jsonPath("$.data[*].employeeNumber").value(contains("L2")));
        mvc.perform(get(admin.path("/employees")).cookie(admin.session()).param("q", "lastl3"))
                .andExpect(jsonPath("$.data[*].employeeNumber").value(contains("L3")));
        mvc.perform(get(admin.path("/employees"))
                        .cookie(admin.session())
                        .param("sort", "-hireDate")
                        .param("limit", "1"))
                .andExpect(jsonPath("$.data[*].employeeNumber").value(contains("L3")))
                .andExpect(jsonPath("$.page.hasMore").value(true))
                .andExpect(jsonPath("$.data[0].currentAssignment.departmentId").value(department.toString()));
        mvc.perform(get(admin.path("/employees"))
                        .cookie(admin.session())
                        .param("filter[hireDate][lte]", today.minusYears(2).toString()))
                .andExpect(jsonPath("$.data[*].employeeNumber").value(contains("L1", "L2")));
    }

    @Test
    void administrativeChangesAreAudited() throws Exception {
        UUID employee = fixtures.employee(admin, "A1", today.minusYears(1), branch, department);
        patchEmployee(employee, 0, json("workEmail", "a1@example.test")).andExpect(status().isOk());
        action(employee, "activate", 1).andExpect(status().isOk());

        mvc.perform(get(admin.path("/audit-log"))
                        .cookie(admin.session())
                        .param("filter[entityId]", employee.toString())
                        .param("sort", "occurredAt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].action").value(contains("CREATE", "UPDATE", "STATE_CHANGE")))
                .andExpect(jsonPath("$.data[0].actorUserId")
                        .value(admin.user().id().toString()))
                .andExpect(jsonPath("$.data[0].entityType").value("employee"))
                .andExpect(jsonPath("$.data[1].changes.workEmail.new").value("a1@example.test"))
                .andExpect(jsonPath("$.data[2].toState").value("ACTIVE"));
    }

    // ------------------------------------------------------------------------------ helpers

    private ResultActions create(String body) throws Exception {
        return mvc.perform(unsafe(post(admin.path("/employees")))
                .cookie(admin.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions patchEmployee(UUID employee, int version, String body) throws Exception {
        return mvc.perform(unsafe(patch(admin.path("/employees/" + employee)))
                .cookie(admin.session())
                .header("If-Match", etag(version))
                .contentType(MERGE_PATCH)
                .content(body));
    }

    private ResultActions action(UUID employee, String action, int version) throws Exception {
        return mvc.perform(unsafe(post(admin.path("/employees/" + employee + "/" + action)))
                .cookie(admin.session())
                .header("If-Match", etag(version)));
    }

    private ResultActions terminate(UUID employee, int version, LocalDate date) throws Exception {
        return mvc.perform(unsafe(post(admin.path("/employees/" + employee + "/terminate")))
                .cookie(admin.session())
                .header("If-Match", etag(version))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json("terminationDate", date.toString(), "reason", "Resigned")));
    }
}
