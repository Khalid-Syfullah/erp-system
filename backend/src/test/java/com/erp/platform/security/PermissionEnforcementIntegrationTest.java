package com.erp.platform.security;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erp.support.IntegrationTest;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@link RequiresPermission} is evaluated against the registered {@link PermissionCheck}. The Auth
 * module provides the real implementation in Phase 3; this test registers a fixed grant set.
 */
@Import(PermissionEnforcementIntegrationTest.GrantReadOnly.class)
class PermissionEnforcementIntegrationTest extends IntegrationTest {

    @TestConfiguration
    static class GrantReadOnly {
        @Bean
        PermissionCheck permissionCheck() {
            Set<String> granted = Set.of("test.resource.read");
            return (context, permission) -> context.requestId() != null && granted.contains(permission);
        }
    }

    @Autowired
    MockMvc mvc;

    @Test
    void grantedPermissionIsAllowed() throws Exception {
        mvc.perform(get("/api/v1/_test/permission/read").with(user("tester")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));
    }

    @Test
    void allListedPermissionsAreRequired() throws Exception {
        mvc.perform(get("/api/v1/_test/permission/write").with(user("tester")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void authenticationIsCheckedBeforePermissions() throws Exception {
        mvc.perform(get("/api/v1/_test/permission/read")).andExpect(status().isUnauthorized());
    }
}
