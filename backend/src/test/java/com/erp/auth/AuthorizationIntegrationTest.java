package com.erp.auth;

import static com.erp.db.auth.Tables.ROLES;
import static com.erp.db.auth.Tables.ROLE_ASSIGNMENTS;
import static com.erp.db.auth.Tables.SESSIONS;
import static com.erp.db.auth.Tables.USERS;
import static com.erp.support.AuthTestSupport.unsafe;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erp.support.AuthTestSupport;
import com.erp.support.AuthTestSupport.TestUser;
import com.erp.support.IntegrationTest;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Server-side authorization (SECURITY.md §4): company boundaries (404), permissions (403), branch
 * scope, IDOR attempts, privilege escalation and the system administrator's separation from
 * business data.
 */
class AuthorizationIntegrationTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    AuthTestSupport auth;

    @Autowired
    DSLContext dsl;

    private String branches(UUID company) {
        return "/api/v1/companies/" + company + "/branches";
    }

    @Test
    void anonymousRequestsToProtectedEndpointsAre401() throws Exception {
        UUID company = auth.company();

        for (String path : new String[] {branches(company), "/api/v1/me", "/api/v1/companies", "/api/v1/admin/users"}) {
            mvc.perform(get(path))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        }
    }

    @Test
    void permissionsAreCheckedPerEndpoint() throws Exception {
        UUID company = auth.company();
        TestUser reader = auth.user();
        auth.assign(reader, auth.customRole("org.branch.read"), company);
        Cookie session = auth.login(reader);

        mvc.perform(get(branches(company)).cookie(session)).andExpect(status().isOk());
        mvc.perform(unsafe(post(branches(company)))
                        .cookie(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"X1\",\"name\":\"Nope\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void membershipIsRequiredForEveryCompanyAnd404HidesOtherCompanies() throws Exception {
        UUID mine = auth.company();
        UUID theirs = auth.company();
        UUID theirBranch = auth.branch(theirs, "HQ");
        TestUser user = auth.user();
        auth.assign(user, auth.customRole("org.branch.read", "org.branch.manage", "org.company.manage"), mine);
        Cookie session = auth.login(user);

        mvc.perform(get(branches(theirs)).cookie(session)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/companies/" + theirs).cookie(session)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/companies/" + UUID.randomUUID()).cookie(session))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/companies/not-a-uuid/branches").cookie(session))
                .andExpect(status().isNotFound());
        // IDOR: another company's branch ID through my own company's path is "not found" as well.
        mvc.perform(get(branches(mine) + "/" + theirBranch).cookie(session)).andExpect(status().isNotFound());
        mvc.perform(unsafe(patch(branches(mine) + "/" + theirBranch))
                        .cookie(session)
                        .header("If-Match", "W/\"0\"")
                        .contentType("application/merge-patch+json")
                        .content("{\"name\":\"Hijacked\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(unsafe(post(branches(mine) + "/" + theirBranch + "/deactivate"))
                        .cookie(session)
                        .header("If-Match", "W/\"0\""))
                .andExpect(status().isNotFound());
        // My company list contains only my company.
        mvc.perform(get("/api/v1/companies").cookie(session))
                .andExpect(jsonPath("$.data[*].id").value(containsInAnyOrder(mine.toString())));
    }

    @Test
    void systemAdministratorsHaveNoBusinessAccessWithoutAnAssignment() throws Exception {
        TestUser admin = auth.systemAdmin();
        UUID company = auth.company();
        Cookie session = auth.login(admin);

        mvc.perform(get(branches(company)).cookie(session)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/admin/users").cookie(session)).andExpect(status().isOk());

        auth.assign(admin, "COMPANY_ADMIN", company);
        mvc.perform(get(branches(company)).cookie(session)).andExpect(status().isOk());
    }

    @Test
    void branchScopeLimitsVisibleBranches() throws Exception {
        UUID company = auth.company();
        UUID north = auth.branch(company, "NORTH");
        UUID south = auth.branch(company, "SOUTH");
        TestUser user = auth.user();
        auth.assign(user, auth.customRole("org.branch.read", "org.branch.manage"), company, north);
        Cookie session = auth.login(user);

        mvc.perform(get(branches(company)).cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].code").value(containsInAnyOrder("NORTH")));
        mvc.perform(get(branches(company) + "/" + north).cookie(session)).andExpect(status().isOk());
        mvc.perform(get(branches(company) + "/" + south).cookie(session)).andExpect(status().isNotFound());
        // Creating branches needs access to all branches.
        mvc.perform(unsafe(post(branches(company)))
                        .cookie(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"EAST\",\"name\":\"East\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/me").cookie(session))
                .andExpect(jsonPath("$.companies[0].branchScope").value(containsInAnyOrder(north.toString())));
    }

    @Test
    void removingPermissionsTakesEffectImmediatelyOnThisInstance() throws Exception {
        UUID company = auth.company();
        TestUser user = auth.user();
        TestUser admin = auth.systemAdmin();
        UUID assignment = auth.assign(user, auth.customRole("org.branch.read"), company);
        Cookie session = auth.login(user);
        mvc.perform(get(branches(company)).cookie(session)).andExpect(status().isOk());

        mvc.perform(unsafe(delete("/api/v1/admin/users/" + user.id() + "/role-assignments/" + assignment))
                        .cookie(auth.login(admin)))
                .andExpect(status().isNoContent());

        mvc.perform(get(branches(company)).cookie(session)).andExpect(status().isUnauthorized());
        // The last assignment is gone, so the session ended (SECURITY.md §3.3).
        assertThat(dsl.fetchCount(SESSIONS, SESSIONS.USER_ID.eq(user.id()))).isZero();
    }

    @Test
    void validityDatesOfAssignmentsAreRespected() throws Exception {
        UUID company = auth.company();
        TestUser user = auth.user();
        UUID assignment = auth.assign(user, auth.customRole("org.branch.read"), company);
        dsl.update(ROLE_ASSIGNMENTS)
                .set(ROLE_ASSIGNMENTS.VALID_FROM, java.time.LocalDate.now().plusDays(10))
                .where(ROLE_ASSIGNMENTS.ID.eq(assignment))
                .execute();
        auth.invalidatePermissionCache();

        mvc.perform(get(branches(company)).cookie(auth.login(user))).andExpect(status().isNotFound());
    }

    @Test
    void gainingASensitivePermissionWithoutMfaEndsTheSession() throws Exception {
        UUID company = auth.company();
        TestUser admin = auth.systemAdmin();
        Cookie adminSession = auth.login(admin);

        // Through a new assignment of a custom role that holds a sensitive permission.
        TestUser assignee = auth.user();
        auth.assign(assignee, auth.customRole("org.branch.read"), company);
        Cookie assigneeSession = auth.login(assignee);
        mvc.perform(unsafe(post("/api/v1/admin/users/" + assignee.id() + "/role-assignments"))
                        .cookie(adminSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"companyId\":\"" + company + "\",\"roleId\":\""
                                + auth.customRole("partners.partner.read_bank") + "\"}"))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/v1/me").cookie(assigneeSession)).andExpect(status().isUnauthorized());

        // Through a sensitive permission added to a role the user already holds.
        TestUser holder = auth.user();
        UUID role = auth.customRole("org.branch.read");
        auth.assign(holder, role, company);
        Cookie holderSession = auth.login(holder);
        int version = dsl.fetchValue(ROLES.VERSION, ROLES.ID.eq(role));
        mvc.perform(unsafe(put("/api/v1/admin/roles/" + role + "/permissions"))
                        .cookie(adminSession)
                        .header("If-Match", "W/\"" + version + "\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"permissions\":[\"org.branch.read\",\"hr.employee.read_sensitive\"]}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/me").cookie(holderSession)).andExpect(status().isUnauthorized());

        // Signing in again yields an enrollment-only session.
        mvc.perform(get(branches(company)).cookie(auth.login(holder)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MFA_ENROLLMENT_REQUIRED"));
    }

    @Test
    void disablingAUserEndsTheirSessionsImmediately() throws Exception {
        TestUser admin = auth.systemAdmin();
        TestUser user = auth.user();
        Cookie session = auth.login(user);
        Cookie adminSession = auth.login(admin);
        int version = dsl.fetchValue(USERS.VERSION, USERS.ID.eq(user.id()));

        mvc.perform(unsafe(post("/api/v1/admin/users/" + user.id() + "/disable"))
                        .cookie(adminSession)
                        .header("If-Match", "W/\"" + version + "\""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISABLED"));

        mvc.perform(get("/api/v1/me").cookie(session)).andExpect(status().isUnauthorized());
    }

    @Test
    void regularUsersCannotUseAdministrationEndpoints() throws Exception {
        UUID company = auth.company();
        TestUser user = auth.user();
        auth.assign(user, "COMPANY_ADMIN", company);
        Cookie session = auth.login(auth.enrollMfa(user));

        mvc.perform(get("/api/v1/admin/users").cookie(session)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/roles").cookie(session)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/audit-log").cookie(session)).andExpect(status().isForbidden());
        mvc.perform(unsafe(post("/api/v1/companies"))
                        .cookie(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"HACK\",\"legalName\":\"x\",\"displayName\":\"x\",\"countryCode\":\"US\","
                                + "\"baseCurrency\":\"USD\",\"timezone\":\"UTC\"}"))
                .andExpect(status().isForbidden());
        // Self-promotion to system administrator is impossible.
        mvc.perform(unsafe(patch("/api/v1/admin/users/" + user.id()))
                        .cookie(session)
                        .header("If-Match", "W/\"0\"")
                        .contentType("application/merge-patch+json")
                        .content("{\"isSystemAdmin\":true}"))
                .andExpect(status().isForbidden());
        assertThat(dsl.fetchValue(USERS.IS_SYSTEM_ADMIN, USERS.ID.eq(user.id())))
                .isFalse();
    }

    @Test
    void companyAdministratorsCannotEscalatePrivileges() throws Exception {
        UUID company = auth.company();
        TestUser companyAdmin = auth.enrollMfa(auth.user());
        auth.assign(companyAdmin, "COMPANY_ADMIN", company);
        TestUser colleague = auth.user();
        Cookie session = auth.login(companyAdmin);
        String assignments = "/api/v1/companies/" + company + "/role-assignments";

        // A role the company admin holds entirely: allowed.
        UUID branchReader = auth.customRole("org.branch.read");
        mvc.perform(unsafe(post(assignments))
                        .cookie(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userEmail\":\"" + colleague.email() + "\",\"roleId\":\"" + branchReader + "\"}"))
                .andExpect(status().isCreated());
        // A role with permissions the company admin lacks (accounting): denied.
        mvc.perform(unsafe(post(assignments))
                        .cookie(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"" + colleague.id() + "\",\"roleId\":\""
                                + auth.roleId("FINANCIAL_CONTROLLER") + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PRIVILEGE_ESCALATION"));
        // Changing one's own assignments: denied (segregation of duties).
        mvc.perform(unsafe(post(assignments))
                        .cookie(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"" + companyAdmin.id() + "\",\"roleId\":\"" + branchReader + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SOD_VIOLATION"));
        // Branches of another company cannot be put into the scope.
        UUID foreignBranch = auth.branch(auth.company(), "FOREIGN");
        mvc.perform(unsafe(post(assignments))
                        .cookie(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"" + colleague.id() + "\",\"roleId\":\""
                                + auth.customRole("org.branch.read") + "\",\"branchIds\":[\"" + foreignBranch + "\"]}"))
                .andExpect(status().isUnprocessableContent());
        // Assignments of another company are invisible.
        UUID otherCompany = auth.company();
        UUID foreignAssignment = auth.assign(colleague, auth.customRole("org.branch.read"), otherCompany);
        mvc.perform(unsafe(delete(assignments + "/" + foreignAssignment)).cookie(session))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/companies/" + otherCompany + "/role-assignments")
                        .cookie(session))
                .andExpect(status().isNotFound());
    }

    @Test
    void branchRestrictedCompanyAdministratorsGrantOnlyWithinTheirBranches() throws Exception {
        UUID company = auth.company();
        UUID north = auth.branch(company, "NORTH");
        UUID south = auth.branch(company, "SOUTH");
        TestUser companyAdmin = auth.enrollMfa(auth.user());
        auth.assign(companyAdmin, "COMPANY_ADMIN", company, north);
        TestUser colleague = auth.user();
        Cookie session = auth.login(companyAdmin);
        String assignments = "/api/v1/companies/" + company + "/role-assignments";
        UUID branchReader = auth.customRole("org.branch.read");
        java.util.function.Function<String, String> body = branches ->
                "{\"userId\":\"" + colleague.id() + "\",\"roleId\":\"" + branchReader + "\"" + branches + "}";

        // All branches (no branch list) or another branch would widen the administrator's own reach.
        mvc.perform(unsafe(post(assignments))
                        .cookie(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.apply("")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PRIVILEGE_ESCALATION"));
        mvc.perform(unsafe(post(assignments))
                        .cookie(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.apply(",\"branchIds\":[\"" + north + "\",\"" + south + "\"]")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PRIVILEGE_ESCALATION"));
        // Within the administrator's branch: allowed, and removable again.
        String created = mvc.perform(unsafe(post(assignments))
                        .cookie(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.apply(",\"branchIds\":[\"" + north + "\"]")))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        // An assignment for all branches made by someone else cannot be removed by the restricted admin.
        UUID wide = auth.assign(colleague, auth.customRole("org.branch.read"), company);
        mvc.perform(unsafe(delete(assignments + "/" + wide)).cookie(session))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PRIVILEGE_ESCALATION"));
        mvc.perform(unsafe(delete(assignments + "/" + com.jayway.jsonpath.JsonPath.read(created, "$.id")))
                        .cookie(session))
                .andExpect(status().isNoContent());
    }

    @Test
    void systemRolesAreImmutableAndCustomRolesCannotHoldGlobalPermissions() throws Exception {
        TestUser admin = auth.systemAdmin();
        Cookie session = auth.login(admin);
        UUID auditor = auth.roleId("AUDITOR");

        mvc.perform(unsafe(put("/api/v1/admin/roles/" + auditor + "/permissions"))
                        .cookie(session)
                        .header(
                                "If-Match",
                                "W/\"" + dsl.fetchValue("select version from auth.roles where id = ?", auditor) + "\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"permissions\":[\"accounting.payment.void\"]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SYSTEM_ROLE_IMMUTABLE"));
        mvc.perform(
                        unsafe(post("/api/v1/admin/roles"))
                                .cookie(session)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"code\":\"SNEAKY_"
                                                + UUID.randomUUID()
                                                        .toString()
                                                        .substring(0, 6)
                                                        .toUpperCase()
                                                + "\",\"name\":\"Sneaky\",\"permissions\":[\"auth.user.manage\",\"no.such.permission\"]}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[*].code")
                        .value(containsInAnyOrder("GLOBAL_PERMISSION", "UNKNOWN_PERMISSION")));
    }

    @Test
    void administratorsCannotLockThemselvesOut() throws Exception {
        TestUser admin = auth.systemAdmin();
        Cookie session = auth.login(admin);
        int version = dsl.fetchValue(USERS.VERSION, USERS.ID.eq(admin.id()));

        mvc.perform(unsafe(post("/api/v1/admin/users/" + admin.id() + "/disable"))
                        .cookie(session)
                        .header("If-Match", "W/\"" + version + "\""))
                .andExpect(status().isForbidden());
        mvc.perform(unsafe(patch("/api/v1/admin/users/" + admin.id()))
                        .cookie(session)
                        .header("If-Match", "W/\"" + version + "\"")
                        .contentType("application/merge-patch+json")
                        .content("{\"isSystemAdmin\":false}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void auditLogIsCompanyScoped() throws Exception {
        UUID company = auth.company();
        UUID otherCompany = auth.company();
        TestUser user = auth.enrollMfa(auth.user());
        auth.assign(user, "COMPANY_ADMIN", company);
        auth.assign(user, "COMPANY_ADMIN", otherCompany);
        Cookie session = auth.login(user);
        mvc.perform(unsafe(post(branches(otherCompany)))
                        .cookie(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"OTHER\",\"name\":\"In the other company\"}"))
                .andExpect(status().isCreated());
        mvc.perform(unsafe(post(branches(company)))
                        .cookie(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"MINE\",\"name\":\"In my company\"}"))
                .andExpect(status().isCreated());

        mvc.perform(get("/api/v1/companies/" + company + "/audit-log").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].entityLabel").value(hasItem("MINE")))
                .andExpect(jsonPath("$.data[*].entityLabel").value(not(hasItem("OTHER"))))
                .andExpect(
                        jsonPath("$.data[*].companyId").value(everyItem(org.hamcrest.Matchers.is(company.toString()))));
        // Global events (logins) are not visible through a company audit log.
        mvc.perform(get("/api/v1/companies/" + company + "/audit-log")
                        .cookie(session)
                        .param("filter[action]", "LOGIN"))
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void systemAdministratorsSeeTheGlobalAuditTrail() throws Exception {
        TestUser admin = auth.systemAdmin();
        Cookie session = auth.login(admin);

        String body = mvc.perform(get("/api/v1/admin/audit-log")
                        .cookie(session)
                        .param("filter[actorUserId]", admin.id().toString()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(JsonPath.<java.util.List<String>>read(body, "$.data[*].action"))
                .contains("LOGIN");
    }
}
