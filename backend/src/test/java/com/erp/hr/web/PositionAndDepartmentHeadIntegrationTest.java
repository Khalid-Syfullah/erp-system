package com.erp.hr.web;

import static com.erp.support.AuthTestSupport.unsafe;
import static com.erp.support.OrgFixtures.assignment;
import static com.erp.support.OrgFixtures.etag;
import static com.erp.support.OrgFixtures.json;
import static org.hamcrest.Matchers.contains;
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

/** Positions (designations / job titles) and department heads. */
class PositionAndDepartmentHeadIntegrationTest extends IntegrationTest {

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
        department = fixtures.department(admin, "ENG", null, null);
    }

    @Test
    void positionLifecycleAndConsistency() throws Exception {
        UUID other = fixtures.department(admin, "FIN", null, null);
        UUID position = fixtures.position(admin, "DEV", department);

        postJson("/positions", json("code", "DEV", "title", "Again")).andExpect(status().isConflict());
        postJson("/positions", json("code", "QA", "title", "Tester", "departmentId", UUID.randomUUID()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].code").value("UNKNOWN_DEPARTMENT"));
        mvc.perform(get(admin.path("/positions"))
                        .cookie(admin.session())
                        .param("filter[departmentId]", department.toString()))
                .andExpect(jsonPath("$.data[*].code").value(contains("DEV")));

        UUID employee =
                fixtures.employee(admin, "P1", today.minusYears(1), assignment(branch, department, position, null));
        // Held positions can neither be deactivated nor tied to a department their holders are not in.
        action("/positions/" + position + "/deactivate", 0)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESOURCE_IN_USE"));
        patchPosition(position, 0, json("departmentId", other)).andExpect(status().isConflict());
        patchPosition(position, 0, json("title", "Developer", "grade", "G7", "departmentId", OrgFixtures.NULL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.departmentId").doesNotExist())
                .andExpect(jsonPath("$.grade").value("G7"));

        // Once nobody holds it, it can be deactivated; inactive positions are not assignable.
        UUID assignment = UUID.fromString(com.jayway.jsonpath.JsonPath.read(
                mvc.perform(get(admin.path("/employees/" + employee + "/assignments"))
                                .cookie(admin.session()))
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.data[0].id"));
        mvc.perform(unsafe(patch(admin.path("/employees/" + employee + "/assignments/" + assignment)))
                        .cookie(admin.session())
                        .header("If-Match", etag(0))
                        .contentType(MERGE_PATCH)
                        .content(json("positionId", OrgFixtures.NULL)))
                .andExpect(status().isOk());
        action("/positions/" + position + "/deactivate", 1).andExpect(status().isOk());
        UUID newcomer = fixtures.employee(admin, "P2", today, null);
        postJson(
                        "/employees/" + newcomer + "/assignments",
                        json("branchId", branch, "departmentId", department, "positionId", position))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].code").value("INACTIVE"));
    }

    @Test
    void positionsAreReadableByEmployeeReadersButOnlyManagedByPositionManagers() throws Exception {
        UUID position = fixtures.position(admin, "OPS", null);
        TestUser reader = auth.user();
        auth.assign(reader, auth.customRole("hr.employee.read"), admin.company());
        Cookie session = auth.login(reader);

        mvc.perform(get(admin.path("/positions/" + position)).cookie(session)).andExpect(status().isOk());
        mvc.perform(unsafe(post(admin.path("/positions")))
                        .cookie(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("code", "NEW", "title", "New")))
                .andExpect(status().isForbidden());
    }

    @Test
    void aDepartmentHasOneHeadAtATime() throws Exception {
        UUID first = fixtures.employee(admin, "H1", today.minusYears(2), branch, department);
        UUID second = fixtures.employee(admin, "H2", today.minusYears(2), branch, department);
        UUID head = fixtures.create(
                admin,
                "/department-heads",
                json(
                        "departmentId",
                        department,
                        "employeeId",
                        first,
                        "effectiveFrom",
                        today.minusYears(1).toString()));

        postJson(
                        "/department-heads",
                        json("departmentId", department, "employeeId", second, "effectiveFrom", today.toString()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DEPARTMENT_HEAD_OVERLAP"));
        postJson(
                        "/department-heads",
                        json(
                                "departmentId",
                                department,
                                "employeeId",
                                second,
                                "effectiveFrom",
                                today.minusYears(3).toString(),
                                "effectiveTo",
                                today.minusYears(2).toString()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].code").value("BEFORE_HIRE"));
        mvc.perform(unsafe(patch(admin.path("/department-heads/" + head)))
                        .cookie(admin.session())
                        .header("If-Match", etag(0))
                        .contentType(MERGE_PATCH)
                        .content(json("effectiveTo", today.minusDays(1).toString())))
                .andExpect(status().isOk());
        postJson(
                        "/department-heads",
                        json("departmentId", department, "employeeId", second, "effectiveFrom", today.toString()))
                .andExpect(status().isCreated());
        // Extending the old headship again would overlap the new head.
        mvc.perform(unsafe(patch(admin.path("/department-heads/" + head)))
                        .cookie(admin.session())
                        .header("If-Match", etag(1))
                        .contentType(MERGE_PATCH)
                        .content(json("effectiveTo", OrgFixtures.NULL)))
                .andExpect(status().isConflict());

        mvc.perform(get(admin.path("/department-heads"))
                        .cookie(admin.session())
                        .param("asOf", today.toString())
                        .param("filter[departmentId]", department.toString()))
                .andExpect(jsonPath("$.data[*].employeeId").value(contains(second.toString())));
        // A department with a current head cannot be deactivated.
        action("/departments/" + department + "/deactivate", 0).andExpect(status().isConflict());
    }

    private ResultActions postJson(String path, String body) throws Exception {
        return mvc.perform(unsafe(post(admin.path(path)))
                .cookie(admin.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions patchPosition(UUID position, int version, String body) throws Exception {
        return mvc.perform(unsafe(patch(admin.path("/positions/" + position)))
                .cookie(admin.session())
                .header("If-Match", etag(version))
                .contentType(MERGE_PATCH)
                .content(body));
    }

    private ResultActions action(String path, int version) throws Exception {
        return mvc.perform(
                unsafe(post(admin.path(path))).cookie(admin.session()).header("If-Match", etag(version)));
    }
}
