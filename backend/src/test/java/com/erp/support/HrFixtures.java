package com.erp.support;

import static com.erp.support.AuthTestSupport.unsafe;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.erp.support.AuthTestSupport.TestUser;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * HR test setups through the API (Phase 9): a company with branch MAIN, department OPS and position
 * DEV, an HR manager holding every HR permission (MFA enrolled), employees with an initial assignment
 * ({@link #employee}), employees linked to their own user for self-service ({@link #person}), leave
 * types and accruals.
 */
@TestComponent
public class HrFixtures {

    public static final String[] ALL_HR = {
        "hr.employee.read",
        "hr.employee.manage",
        "hr.employee.read_sensitive",
        "hr.employee.manage_bank",
        "hr.employee.terminate",
        "hr.position.manage",
        "hr.leave.read",
        "hr.leave.approve",
        "hr.leave.adjust",
        "hr.leave.configure",
        "hr.attendance.read",
        "hr.attendance.manage",
        "org.branch.read",
        "org.department.read",
        "org.department.manage"
    };

    /** A company's HR as a test sees it. */
    public record Hr(UUID company, TestUser user, Cookie session, UUID branch, UUID department, UUID position) {

        public String path(String suffix) {
            return "/api/v1/companies/" + company + suffix;
        }

        public LocalDate today() {
            return LocalDate.now(ZoneId.of("America/New_York"));
        }
    }

    /** An employee linked to their own user, signed in (self-service). */
    public record Person(UUID employee, TestUser user, Cookie session) {}

    private final MockMvc mvc;
    private final AuthTestSupport auth;

    public HrFixtures(MockMvc mvc, AuthTestSupport auth) {
        this.mvc = mvc;
        this.auth = auth;
    }

    public Hr setup() throws Exception {
        return setup(auth.company());
    }

    public Hr setup(UUID company) throws Exception {
        UUID branch = auth.branch(company, "MAIN");
        TestUser user = auth.enrollMfa(auth.user());
        auth.assign(user, auth.customRole(ALL_HR), company);
        auth.invalidatePermissionCache();
        Cookie session = auth.login(user);
        String base = "/api/v1/companies/" + company;
        UUID department = create(session, base + "/departments", OrgFixtures.map("code", "OPS", "name", "Operations"));
        UUID position = create(
                session,
                base + "/positions",
                OrgFixtures.map("code", "DEV", "title", "Developer", "departmentId", department));
        return new Hr(company, user, session, branch, department, position);
    }

    /** An active employee with an open-ended assignment from the hire date. */
    public UUID employee(Hr h, String number, LocalDate hireDate, @Nullable UUID managerId) throws Exception {
        UUID id = create(
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
                        OrgFixtures.assignment(h.branch(), h.department(), h.position(), managerId)));
        expect(
                action(
                        h.session(),
                        h.path("/employees/" + id + "/activate"),
                        version(h, "/employees/" + id),
                        null,
                        null),
                200);
        return id;
    }

    /** Links a new user (member of the company without permissions) to the employee and signs them in. */
    public Person person(Hr h, UUID employeeId) throws Exception {
        TestUser user = auth.user();
        auth.assign(user, auth.customRole(), h.company());
        auth.invalidatePermissionCache();
        MvcResult linked = mvc.perform(unsafe(MockMvcRequestBuilders.put(h.path("/employees/" + employeeId + "/user")))
                        .cookie(h.session())
                        .header("If-Match", OrgFixtures.etag(version(h, "/employees/" + employeeId)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OrgFixtures.json("userId", user.id())))
                .andReturn();
        if (linked.getResponse().getStatus() != 200) {
            throw new AssertionError("Link failed: " + linked.getResponse().getContentAsString());
        }
        return new Person(employeeId, user, auth.login(user));
    }

    public UUID leaveType(
            Hr h, String code, String entitlement, String accrualMethod, String carry, boolean allowNegative)
            throws Exception {
        return create(
                h.session(),
                h.path("/leave-types"),
                OrgFixtures.map(
                        "code",
                        code,
                        "name",
                        "Leave " + code,
                        "annualEntitlementDays",
                        entitlement,
                        "accrualMethod",
                        accrualMethod,
                        "maxCarryForwardDays",
                        carry,
                        "allowNegativeBalance",
                        allowNegative));
    }

    public void accrue(Hr h, LocalDate asOf) throws Exception {
        expect(
                mvc.perform(unsafe(MockMvcRequestBuilders.post(h.path("/leave-accruals")))
                        .cookie(h.session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OrgFixtures.json("asOf", asOf))),
                200);
    }

    /** The first Monday on or after the first of the month. */
    public static LocalDate firstMonday(int year, int month) {
        return LocalDate.of(year, month, 1).with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
    }

    // ---------------------------------------------------------------------------- HTTP helpers

    public UUID create(Cookie session, String path, Object body) throws Exception {
        MvcResult result = post(session, path, body).andReturn();
        if (result.getResponse().getStatus() != 201) {
            throw new AssertionError(
                    "POST " + path + " failed: " + result.getResponse().getStatus() + " "
                            + result.getResponse().getContentAsString());
        }
        return UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), "$.id"));
    }

    public ResultActions post(Cookie session, String path, Object body) throws Exception {
        return mvc.perform(unsafe(MockMvcRequestBuilders.post(path))
                .cookie(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body instanceof String s ? s : OrgFixtures.JSON_MAPPER.writeValueAsString(body)));
    }

    /** POST {path} with If-Match (when {@code version ≥ 0}), an optional Idempotency-Key and JSON body. */
    public ResultActions action(Cookie session, String path, int version, @Nullable String key, @Nullable Object body)
            throws Exception {
        MockHttpServletRequestBuilder request =
                unsafe(MockMvcRequestBuilders.post(path)).cookie(session);
        if (version >= 0) {
            request.header("If-Match", OrgFixtures.etag(version));
        }
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON)
                    .content(body instanceof String s ? s : OrgFixtures.JSON_MAPPER.writeValueAsString(body));
        }
        return mvc.perform(request);
    }

    public String body(Cookie session, String path) throws Exception {
        MvcResult result = mvc.perform(get(path).cookie(session)).andReturn();
        if (result.getResponse().getStatus() != 200) {
            throw new AssertionError(path + ": " + result.getResponse().getStatus() + " "
                    + result.getResponse().getContentAsString());
        }
        return result.getResponse().getContentAsString();
    }

    public int version(Hr h, String suffix) throws Exception {
        return JsonPath.read(body(h.session(), h.path(suffix)), "$.version");
    }

    public <T> T read(Cookie session, String path, String jsonPath) throws Exception {
        return JsonPath.read(body(session, path), jsonPath);
    }

    public static MvcResult expect(ResultActions actions, int status) throws Exception {
        return ProcurementFixtures.expect(actions, status);
    }

    public static Map<String, Object> leave(UUID leaveType, LocalDate start, LocalDate end) {
        return OrgFixtures.map("leaveTypeId", leaveType, "startDate", start, "endDate", end);
    }
}
