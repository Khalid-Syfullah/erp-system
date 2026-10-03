package com.erp.auth;

import static com.erp.db.auth.Tables.MFA_TOTP;
import static com.erp.db.auth.Tables.SESSIONS;
import static com.erp.db.auth.Tables.USERS;
import static com.erp.support.AuthTestSupport.CHALLENGE_COOKIE;
import static com.erp.support.AuthTestSupport.unsafe;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erp.auth.domain.Base32;
import com.erp.auth.domain.Totp;
import com.erp.support.AuthTestSupport;
import com.erp.support.AuthTestSupport.TestUser;
import com.erp.support.IntegrationTest;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.List;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/** TOTP enrollment, the login second step, recovery codes and mandatory MFA (SECURITY.md §3.5). */
class MfaIntegrationTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    AuthTestSupport auth;

    @Autowired
    DSLContext dsl;

    @Test
    void enrollmentStoresAnEncryptedSecretAndReturnsRecoveryCodesOnce() throws Exception {
        TestUser user = auth.user();
        Cookie session = auth.login(user);

        String setup = mvc.perform(unsafe(post("/api/v1/me/mfa/totp/setup")).cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.otpauthUri").value(org.hamcrest.Matchers.startsWith("otpauth://totp/")))
                .andReturn()
                .getResponse()
                .getContentAsString();
        byte[] secret = Base32.decode(JsonPath.read(setup, "$.secret"));
        byte[] stored = dsl.fetchValue(MFA_TOTP.SECRET_ENCRYPTED, MFA_TOTP.USER_ID.eq(user.id()));
        assertThat(new String(stored, java.nio.charset.StandardCharsets.ISO_8859_1))
                .doesNotContain(new String(secret, java.nio.charset.StandardCharsets.ISO_8859_1));

        confirm(session, "000000").andExpect(status().isUnprocessableContent());
        String confirmed = confirm(session, Totp.code(secret, Totp.stepAt(Instant.now())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recoveryCodes.length()").value(10))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(dsl.fetchValue(USERS.MFA_ENABLED, USERS.ID.eq(user.id()))).isTrue();
        assertThat(JsonPath.<List<String>>read(confirmed, "$.recoveryCodes"))
                .allMatch(c -> c.matches("^[a-z2-9]{5}-[a-z2-9]{5}$"));
        // A second enrollment while enrolled is refused.
        mvc.perform(unsafe(post("/api/v1/me/mfa/totp/setup")).cookie(session)).andExpect(status().isConflict());
    }

    @Test
    void loginWithMfaNeedsTheSecondFactorAndRejectsReplays() throws Exception {
        TestUser user = auth.enrollMfa(auth.user());

        MvcResult first = password(user)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("MFA_REQUIRED"))
                .andReturn();
        Cookie challenge = first.getResponse().getCookie(CHALLENGE_COOKIE);
        assertThat(challenge).isNotNull();
        assertThat(first.getResponse().getCookie(AuthTestSupport.SESSION_COOKIE))
                .isNull();

        secondFactor(challenge, "{\"code\":\"000000\"}")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_MFA_CODE"));
        String code = AuthTestSupport.currentCode(user);
        MvcResult ok = secondFactor(challenge, "{\"code\":\"" + code + "\"}")
                .andExpect(status().isOk())
                .andReturn();
        mvc.perform(get("/api/v1/me").cookie(AuthTestSupport.sessionCookie(ok))).andExpect(status().isOk());

        // The challenge is consumed, and the same code cannot be used for a new login (replay).
        secondFactor(challenge, "{\"code\":\"" + code + "\"}").andExpect(status().isUnauthorized());
        Cookie newChallenge = password(user).andReturn().getResponse().getCookie(CHALLENGE_COOKIE);
        secondFactor(newChallenge, "{\"code\":\"" + code + "\"}")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_MFA_CODE"));
    }

    @Test
    void challengesAllowFiveAttemptsOnly() throws Exception {
        TestUser user = auth.enrollMfa(auth.user());
        Cookie challenge = password(user).andReturn().getResponse().getCookie(CHALLENGE_COOKIE);

        for (int i = 0; i < 5; i++) {
            secondFactor(challenge, "{\"code\":\"00000" + i + "\"}").andExpect(status().isUnauthorized());
        }

        dsl.update(MFA_TOTP)
                .setNull(MFA_TOTP.LAST_USED_STEP)
                .where(MFA_TOTP.USER_ID.eq(user.id()))
                .execute();
        secondFactor(challenge, "{\"code\":\"" + AuthTestSupport.currentCode(user) + "\"}")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void recoveryCodesWorkExactlyOnce() throws Exception {
        TestUser user = auth.user();
        Cookie session = auth.login(user);
        String setup = mvc.perform(unsafe(post("/api/v1/me/mfa/totp/setup")).cookie(session))
                .andReturn()
                .getResponse()
                .getContentAsString();
        byte[] secret = Base32.decode(JsonPath.read(setup, "$.secret"));
        String codes = confirm(session, Totp.code(secret, Totp.stepAt(Instant.now())))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String recovery = JsonPath.read(codes, "$.recoveryCodes[0]");

        Cookie challenge = password(user).andReturn().getResponse().getCookie(CHALLENGE_COOKIE);
        secondFactor(challenge, "{\"recoveryCode\":\"" + recovery.toUpperCase() + "\"}")
                .andExpect(status().isOk());

        Cookie again = password(user).andReturn().getResponse().getCookie(CHALLENGE_COOKIE);
        secondFactor(again, "{\"recoveryCode\":\"" + recovery + "\"}").andExpect(status().isUnauthorized());
    }

    @Test
    void mandatoryMfaRestrictsTheSessionUntilEnrollment() throws Exception {
        // System administrators must use MFA; this one has not enrolled yet.
        TestUser admin = auth.user(true);

        Cookie session = auth.login(admin);

        mvc.perform(get("/api/v1/me").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mfaRequired").value(true))
                .andExpect(jsonPath("$.mfaEnrollmentRequired").value(true));
        mvc.perform(get("/api/v1/admin/users").cookie(session))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MFA_ENROLLMENT_REQUIRED"));
        mvc.perform(get("/api/v1/me/sessions").cookie(session))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MFA_ENROLLMENT_REQUIRED"));

        String setup = mvc.perform(unsafe(post("/api/v1/me/mfa/totp/setup")).cookie(session))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        byte[] secret = Base32.decode(JsonPath.read(setup, "$.secret"));
        confirm(session, Totp.code(secret, Totp.stepAt(Instant.now()))).andExpect(status().isOk());

        mvc.perform(get("/api/v1/admin/users").cookie(session)).andExpect(status().isOk());
        assertThat(dsl.fetchValue(SESSIONS.MFA_ENROLLMENT_REQUIRED, SESSIONS.USER_ID.eq(admin.id())))
                .isFalse();
    }

    @Test
    void mandatoryMfaCannotBeRemovedAndOptionalMfaNeedsStepUp() throws Exception {
        TestUser admin = auth.systemAdmin();
        Cookie adminSession = auth.login(admin);
        mvc.perform(unsafe(delete("/api/v1/me/mfa/totp")).cookie(adminSession))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MFA_MANDATORY"));

        TestUser user = auth.enrollMfa(auth.user());
        Cookie session = auth.login(user);
        dsl.update(SESSIONS)
                .set(SESSIONS.AUTHENTICATED_AT, java.time.OffsetDateTime.now().minusMinutes(10))
                .where(SESSIONS.USER_ID.eq(user.id()))
                .execute();
        mvc.perform(unsafe(delete("/api/v1/me/mfa/totp")).cookie(session))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("REAUTHENTICATION_REQUIRED"));

        MvcResult stepUp = mvc.perform(unsafe(post("/api/v1/me/reauthenticate"))
                        .cookie(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"" + user.password() + "\"}"))
                .andExpect(status().isNoContent())
                .andReturn();
        mvc.perform(unsafe(delete("/api/v1/me/mfa/totp")).cookie(AuthTestSupport.sessionCookie(stepUp)))
                .andExpect(status().isNoContent());
        assertThat(dsl.fetchValue(USERS.MFA_ENABLED, USERS.ID.eq(user.id()))).isFalse();
    }

    private ResultActions password(TestUser user) throws Exception {
        return mvc.perform(unsafe(post("/api/v1/auth/login"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + user.email() + "\",\"password\":\"" + user.password() + "\"}"));
    }

    private ResultActions secondFactor(Cookie challenge, String body) throws Exception {
        return mvc.perform(unsafe(post("/api/v1/auth/login/mfa"))
                .cookie(challenge)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions confirm(Cookie session, String code) throws Exception {
        return mvc.perform(unsafe(post("/api/v1/me/mfa/totp/confirm"))
                .cookie(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"" + code + "\"}"));
    }
}
