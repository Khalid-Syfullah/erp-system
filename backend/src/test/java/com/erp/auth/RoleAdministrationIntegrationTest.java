package com.erp.auth;

import static com.erp.support.AuthTestSupport.unsafe;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erp.support.AuthTestSupport;
import com.erp.support.AuthTestSupport.TestUser;
import com.erp.support.IntegrationTest;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** Custom role administration (API.md §17.2, ADR-030). */
class RoleAdministrationIntegrationTest extends IntegrationTest {

    private static final String MERGE_PATCH = "application/merge-patch+json";

    @Autowired
    MockMvc mvc;

    @Autowired
    AuthTestSupport auth;

    @Test
    void customRoleLifecycle() throws Exception {
        Cookie session = auth.login(auth.systemAdmin());
        String code = "CLERK_" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();

        String body = mvc.perform(unsafe(post("/api/v1/admin/roles"))
                        .cookie(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                "{\"code\":\"" + code + "\",\"name\":\"Clerk\",\"permissions\":[\"org.branch.read\"]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.isSystem").value(false))
                .andExpect(jsonPath("$.permissions").value(containsInAnyOrder("org.branch.read")))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String role = "/api/v1/admin/roles/" + JsonPath.read(body, "$.id");

        mvc.perform(get("/api/v1/admin/roles").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].code").value(hasItem(code)));
        mvc.perform(get(role).cookie(session)).andExpect(status().isOk()).andExpect(header().string("ETag", "W/\"1\""));
        mvc.perform(unsafe(post("/api/v1/admin/roles"))
                        .cookie(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\",\"name\":\"Again\",\"permissions\":[]}"))
                .andExpect(status().isConflict());

        mvc.perform(unsafe(patch(role))
                        .cookie(session)
                        .header("If-Match", "W/\"1\"")
                        .contentType(MERGE_PATCH)
                        .content("{\"name\":\"Senior clerk\",\"description\":\"Reads branches\",\"requiresMfa\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Senior clerk"))
                .andExpect(jsonPath("$.requiresMfa").value(true));
        mvc.perform(unsafe(put(role + "/permissions"))
                        .cookie(session)
                        .header("If-Match", "W/\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"permissions\":[]}"))
                .andExpect(status().isPreconditionFailed());
        mvc.perform(unsafe(put(role + "/permissions"))
                        .cookie(session)
                        .header("If-Match", "W/\"2\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"permissions\":[\"org.branch.read\",\"org.branch.manage\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions").value(containsInAnyOrder("org.branch.read", "org.branch.manage")));

        mvc.perform(unsafe(delete(role)).cookie(session).header("If-Match", "W/\"3\""))
                .andExpect(status().isNoContent());
        mvc.perform(get(role).cookie(session)).andExpect(status().isNotFound());
    }

    @Test
    void assignedRolesCannotBeDeletedAndSystemRolesNeverCanBe() throws Exception {
        Cookie session = auth.login(auth.systemAdmin());
        UUID role = auth.customRole("org.branch.read");
        TestUser holder = auth.user();
        auth.assign(holder, role, auth.company());

        mvc.perform(unsafe(delete("/api/v1/admin/roles/" + role))
                        .cookie(session)
                        .header("If-Match", "W/\"0\""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESOURCE_IN_USE"));
        UUID auditor = auth.roleId("AUDITOR");
        mvc.perform(unsafe(delete("/api/v1/admin/roles/" + auditor))
                        .cookie(session)
                        .header("If-Match", "W/\"0\""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SYSTEM_ROLE_IMMUTABLE"));
    }

    @Test
    void permissionCatalogueIsListed() throws Exception {
        mvc.perform(get("/api/v1/admin/permissions").cookie(auth.login(auth.systemAdmin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.code == 'payroll.run.approve')].isSensitive")
                        .value(containsInAnyOrder(true)))
                .andExpect(jsonPath("$.data[?(@.code == 'org.branch.read')].isSensitive")
                        .value(containsInAnyOrder(false)));
    }

    @Test
    void companyAdministratorsListAssignableRolesAndAssignments() throws Exception {
        UUID company = auth.company();
        TestUser admin = auth.enrollMfa(auth.user());
        auth.assign(admin, "COMPANY_ADMIN", company);
        TestUser colleague = auth.user();
        UUID assignment = auth.assign(colleague, auth.customRole("org.branch.read"), company);
        Cookie session = auth.login(admin);
        String base = "/api/v1/companies/" + company;

        mvc.perform(get(base + "/roles").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].code").value(hasItem("COMPANY_ADMIN")));
        mvc.perform(get(base + "/role-assignments").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].id").value(hasItem(assignment.toString())));
        mvc.perform(unsafe(delete(base + "/role-assignments/" + assignment)).cookie(session))
                .andExpect(status().isNoContent());
        mvc.perform(unsafe(delete(base + "/role-assignments/" + assignment)).cookie(session))
                .andExpect(status().isNotFound());
    }
}
