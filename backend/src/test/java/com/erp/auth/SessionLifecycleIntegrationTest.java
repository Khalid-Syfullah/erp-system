package com.erp.auth;

import static com.erp.db.auth.Tables.SESSIONS;
import static com.erp.support.AuthTestSupport.unsafe;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erp.auth.domain.SecureTokens;
import com.erp.support.AuthTestSupport;
import com.erp.support.AuthTestSupport.TestUser;
import com.erp.support.IntegrationTest;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** Session expiry, rotation, concurrency cap and revocation (SECURITY.md §3.3). */
class SessionLifecycleIntegrationTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    AuthTestSupport auth;

    @Autowired
    DSLContext dsl;

    @Test
    void idleSessionsExpire() throws Exception {
        Cookie session = auth.login(auth.user());
        mvc.perform(get("/api/v1/me").cookie(session)).andExpect(status().isOk());

        dsl.update(SESSIONS)
                .set(SESSIONS.LAST_SEEN_AT, OffsetDateTime.now().minusMinutes(31))
                .where(SESSIONS.TOKEN_HASH.eq(SecureTokens.sha256(session.getValue())))
                .execute();

        mvc.perform(get("/api/v1/me").cookie(session)).andExpect(status().isUnauthorized());
        // The expired session is removed, not just ignored.
        assertThat(dsl.fetchExists(SESSIONS, SESSIONS.TOKEN_HASH.eq(SecureTokens.sha256(session.getValue()))))
                .isFalse();
    }

    @Test
    void sessionsExpireAfterTheAbsoluteTimeoutDespiteActivity() throws Exception {
        Cookie session = auth.login(auth.user());

        dsl.update(SESSIONS)
                .set(SESSIONS.CREATED_AT, OffsetDateTime.now().minusHours(13))
                .set(SESSIONS.ABSOLUTE_EXPIRES_AT, OffsetDateTime.now().minusMinutes(1))
                .where(SESSIONS.TOKEN_HASH.eq(SecureTokens.sha256(session.getValue())))
                .execute();

        mvc.perform(get("/api/v1/me").cookie(session)).andExpect(status().isUnauthorized());
    }

    @Test
    void atMostFiveConcurrentSessionsTheOldestIsEvicted() throws Exception {
        TestUser user = auth.user();
        List<Cookie> sessions = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            sessions.add(auth.login(user));
        }

        mvc.perform(get("/api/v1/me").cookie(sessions.getFirst())).andExpect(status().isUnauthorized());
        for (Cookie newer : sessions.subList(1, 6)) {
            mvc.perform(get("/api/v1/me").cookie(newer)).andExpect(status().isOk());
        }
        assertThat(dsl.fetchCount(SESSIONS, SESSIONS.USER_ID.eq(user.id()))).isEqualTo(5);
    }

    @Test
    void everyLoginIssuesAFreshSessionSecret() throws Exception {
        TestUser user = auth.user();

        assertThat(auth.login(user).getValue()).isNotEqualTo(auth.login(user).getValue());
    }

    @Test
    void passwordChangeRotatesTheSessionAndEndsOtherSessions() throws Exception {
        TestUser user = auth.user();
        Cookie other = auth.login(user);
        Cookie current = auth.login(user);

        MvcResult result = mvc.perform(unsafe(post("/api/v1/me/password"))
                        .cookie(current)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"" + user.password()
                                + "\",\"newPassword\":\"Brand-New-Granite-Teapot-9\"}"))
                .andExpect(status().isNoContent())
                .andReturn();
        Cookie rotated = AuthTestSupport.sessionCookie(result);

        assertThat(rotated.getValue()).isNotEqualTo(current.getValue());
        mvc.perform(get("/api/v1/me").cookie(current)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/me").cookie(other)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/me").cookie(rotated)).andExpect(status().isOk());
        auth.login(new TestUser(user.id(), user.email(), user.displayName(), "Brand-New-Granite-Teapot-9", null));
    }

    @Test
    void passwordChangeNeedsTheCurrentPasswordAndAPolicyCompliantNewOne() throws Exception {
        TestUser user = auth.user();
        Cookie session = auth.login(user);

        mvc.perform(
                        unsafe(post("/api/v1/me/password"))
                                .cookie(session)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"currentPassword\":\"wrong-current-password\",\"newPassword\":\"Brand-New-Granite-Teapot-9\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].pointer").value("/currentPassword"))
                .andExpect(jsonPath("$.errors[0].code").value("INCORRECT_PASSWORD"));
        mvc.perform(unsafe(post("/api/v1/me/password"))
                        .cookie(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"" + user.password() + "\",\"newPassword\":\"password1234\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[*].code").value(org.hamcrest.Matchers.hasItem("BREACHED")));
    }

    @Test
    void usersListAndRevokeTheirOwnSessionsOnly() throws Exception {
        TestUser user = auth.user();
        Cookie first = auth.login(user);
        Cookie second = auth.login(user);
        Cookie stranger = auth.login(auth.user());

        String body = mvc.perform(get("/api/v1/me/sessions").cookie(second))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andReturn()
                .getResponse()
                .getContentAsString();
        List<String> ids = JsonPath.read(body, "$.data[?(@.current == false)].id");

        // Another user cannot revoke it (404), the owner can.
        mvc.perform(unsafe(delete("/api/v1/me/sessions/" + ids.getFirst())).cookie(stranger))
                .andExpect(status().isNotFound());
        mvc.perform(unsafe(delete("/api/v1/me/sessions/" + ids.getFirst())).cookie(second))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/me").cookie(first)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/me").cookie(second)).andExpect(status().isOk());
    }

    @Test
    void stepUpReauthenticationRotatesTheSession() throws Exception {
        TestUser user = auth.user();
        Cookie session = auth.login(user);

        MvcResult result = mvc.perform(unsafe(post("/api/v1/me/reauthenticate"))
                        .cookie(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"" + user.password() + "\"}"))
                .andExpect(status().isNoContent())
                .andReturn();

        Cookie rotated = AuthTestSupport.sessionCookie(result);
        assertThat(rotated.getValue()).isNotEqualTo(session.getValue());
        mvc.perform(get("/api/v1/me").cookie(session)).andExpect(status().isUnauthorized());
        assertThat(dsl.fetchValue(
                        SESSIONS.REAUTHENTICATED_AT, SESSIONS.TOKEN_HASH.eq(SecureTokens.sha256(rotated.getValue()))))
                .isNotNull();
    }

    @Test
    void wrongPasswordConfirmationsAreBudgeted() throws Exception {
        TestUser user = auth.user();
        Cookie session = auth.login(user);

        for (int i = 0; i < 5; i++) {
            mvc.perform(unsafe(post("/api/v1/me/reauthenticate"))
                            .cookie(session)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"password\":\"guess-number-" + i + "\"}"))
                    .andExpect(status().isUnprocessableContent());
        }
        // The budget is exhausted: even the right password is refused for a while.
        mvc.perform(unsafe(post("/api/v1/me/reauthenticate"))
                        .cookie(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"" + user.password() + "\"}"))
                .andExpect(status().isTooManyRequests());
    }
}
