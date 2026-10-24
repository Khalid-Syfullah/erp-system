package com.erp.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erp.support.AuthTestSupport;
import com.erp.support.AuthTestSupport.TestUser;
import com.erp.support.IntegrationTest;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** Accounts exempted from mandatory MFA by configuration (ADR-045, SECURITY.md §3.5). */
@TestPropertySource(
        properties = "erp.auth.mfa.exempt-emails= Exempt-Admin@MFA-Exemption.test ,exempt-clerk@mfa-exemption.test,")
class MfaExemptionIntegrationTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    AuthTestSupport auth;

    @Test
    void exemptedAccountsSignInWithoutEnrollingWhileEveryoneElseStillMust() throws Exception {
        // A system administrator on the list (matched case-insensitively) gets a full session.
        TestUser exemptAdmin = auth.user("exempt-admin@mfa-exemption.test", true);
        Cookie adminSession = auth.login(exemptAdmin);
        mvc.perform(get("/api/v1/me").cookie(adminSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mfaRequired").value(false))
                .andExpect(jsonPath("$.mfaEnrollmentRequired").value(false));
        mvc.perform(get("/api/v1/admin/users").cookie(adminSession)).andExpect(status().isOk());

        // So does a listed user holding a role that requires MFA.
        UUID company = auth.company();
        TestUser exemptClerk = auth.user("exempt-clerk@mfa-exemption.test", false);
        auth.assign(exemptClerk, "COMPANY_ADMIN", company);
        mvc.perform(get("/api/v1/me").cookie(auth.login(exemptClerk)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mfaRequired").value(false))
                .andExpect(jsonPath("$.mfaEnrollmentRequired").value(false));

        // Anyone not on the list is still forced to enroll.
        TestUser otherAdmin = auth.user(true);
        Cookie otherSession = auth.login(otherAdmin);
        mvc.perform(get("/api/v1/me").cookie(otherSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mfaRequired").value(true))
                .andExpect(jsonPath("$.mfaEnrollmentRequired").value(true));
        mvc.perform(get("/api/v1/admin/users").cookie(otherSession))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MFA_ENROLLMENT_REQUIRED"));
    }
}
