package com.erp.auth;

import static com.erp.db.admin.Tables.AUDIT_LOG;
import static com.erp.db.auth.Tables.LOGIN_ATTEMPTS;
import static com.erp.db.auth.Tables.SESSIONS;
import static com.erp.db.auth.Tables.USERS;
import static com.erp.support.AuthTestSupport.SESSION_COOKIE;
import static com.erp.support.AuthTestSupport.unsafe;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erp.auth.domain.SecureTokens;
import com.erp.platform.context.CurrentContext;
import com.erp.platform.context.RequestContext;
import com.erp.support.AuthTestSupport;
import com.erp.support.AuthTestSupport.TestUser;
import com.erp.support.IntegrationTest;
import com.erp.support.RecordingAccountNotifier;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

/** Password login, failure handling, lockout and logout (SECURITY.md §3.2–§3.4). */
class AuthenticationIntegrationTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    AuthTestSupport auth;

    @Autowired
    DSLContext dsl;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    RecordingAccountNotifier mail;

    @Test
    void successfulLoginSetsAHardenedSessionCookieAndAuthenticatesRequests() throws Exception {
        TestUser user = auth.user();

        MvcResult result = login(user.email(), user.password())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.email").value(user.email()))
                .andExpect(jsonPath("$.user.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.mfaEnrollmentRequired").value(false))
                .andReturn();

        String setCookie = result.getResponse().getHeaders("Set-Cookie").stream()
                .filter(h -> h.startsWith(SESSION_COOKIE + "="))
                .findFirst()
                .orElseThrow();
        assertThat(setCookie)
                .contains("HttpOnly", "Secure", "SameSite=Lax", "Path=/")
                .doesNotContain("Domain=");
        Cookie session = AuthTestSupport.sessionCookie(result);
        assertThat(session.getValue()).hasSize(43);

        mvc.perform(get("/api/v1/me").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.id").value(user.id().toString()));
        // Only the hash of the cookie secret is stored.
        assertThat(dsl.fetchExists(SESSIONS, SESSIONS.TOKEN_HASH.eq(SecureTokens.sha256(session.getValue()))))
                .isTrue();
        assertThat(auditActions(user.id())).contains("LOGIN");
    }

    @Test
    void passwordsAreStoredOnlyAsArgon2idHashes() {
        TestUser user = auth.user();

        String stored = dsl.fetchValue(USERS.PASSWORD_HASH, USERS.ID.eq(user.id()));

        assertThat(stored).startsWith("$argon2id$").doesNotContain(user.password());
    }

    @Test
    void wrongPasswordAndUnknownEmailGetTheSameGenericAnswer() throws Exception {
        TestUser user = auth.user();

        String wrongPassword = login(user.email(), "Not-The-Right-Password-1")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String unknownEmail = login("nobody-" + UUID.randomUUID() + "@example.test", "Whatever-Password-123")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(strip(wrongPassword)).isEqualTo(strip(unknownEmail));
        assertThat(dsl.fetchCount(
                        LOGIN_ATTEMPTS, LOGIN_ATTEMPTS.USER_ID.eq(user.id()).and(LOGIN_ATTEMPTS.SUCCEEDED.isFalse())))
                .isEqualTo(1);
        assertThat(auditActions(user.id())).contains("LOGIN_FAILED");
    }

    @Test
    void emailMatchingIsCaseInsensitive() throws Exception {
        TestUser user = auth.user();

        login("  " + user.email().toUpperCase() + " ", user.password()).andExpect(status().isOk());
    }

    @Test
    void disabledInvitedAndLockedAccountsCannotLogIn() throws Exception {
        for (String status : List.of("DISABLED", "LOCKED")) {
            TestUser user = auth.user();
            dsl.update(USERS)
                    .set(USERS.STATUS, status)
                    .where(USERS.ID.eq(user.id()))
                    .execute();

            login(user.email(), user.password())
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        }
        TestUser invited = auth.user();
        dsl.update(USERS)
                .set(USERS.STATUS, "INVITED")
                .setNull(USERS.PASSWORD_HASH)
                .where(USERS.ID.eq(invited.id()))
                .execute();
        login(invited.email(), invited.password()).andExpect(status().isUnauthorized());
    }

    @Test
    void fiveConsecutiveFailuresLockTheAccountTemporarily() throws Exception {
        TestUser user = auth.user();

        for (int i = 0; i < 5; i++) {
            login(user.email(), "Wrong-Password-Attempt-" + i).andExpect(status().isUnauthorized());
        }

        // Even the correct password is refused while locked, with the same generic answer.
        login(user.email(), user.password())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        assertThat(dsl.fetchValue(USERS.LOCKED_UNTIL, USERS.ID.eq(user.id()))).isNotNull();
        assertThat(auditActions(user.id())).contains("LOCKOUT");
        assertThat(mail.last(user.email(), "LOCKED_TEMPORARILY")).isPresent();

        // After the lock expires the correct password works again.
        dsl.update(USERS)
                .set(USERS.LOCKED_UNTIL, java.time.OffsetDateTime.now().minusSeconds(1))
                .where(USERS.ID.eq(user.id()))
                .execute();
        login(user.email(), user.password()).andExpect(status().isOk());
    }

    @Test
    void repeatedLockoutsLockTheAccountUntilAnAdministratorUnlocksIt() throws Exception {
        TestUser user = auth.user();

        for (int lockout = 0; lockout < 3; lockout++) {
            for (int i = 0; i < 5; i++) {
                login(user.email(), "Wrong-Password-Attempt-" + i).andExpect(status().isUnauthorized());
            }
            dsl.update(USERS)
                    .setNull(USERS.LOCKED_UNTIL)
                    .where(USERS.ID.eq(user.id()))
                    .execute();
        }

        assertThat(dsl.fetchValue(USERS.STATUS, USERS.ID.eq(user.id()))).isEqualTo("LOCKED");
        assertThat(mail.last(user.email(), "LOCKED_PERMANENTLY")).isPresent();
        login(user.email(), user.password()).andExpect(status().isUnauthorized());
    }

    @Test
    void logoutEndsTheSession() throws Exception {
        TestUser user = auth.user();
        Cookie session = auth.login(user);

        mvc.perform(unsafe(post("/api/v1/auth/logout")).cookie(session))
                .andExpect(status().isNoContent())
                .andExpect(header().stringValues(
                                "Set-Cookie",
                                org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.allOf(
                                        containsString(SESSION_COOKIE + "="), containsString("Max-Age=0")))));

        mvc.perform(get("/api/v1/me").cookie(session))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        assertThat(auditActions(user.id())).contains("LOGOUT");
    }

    @Test
    void loginRequiresCsrfTokenAndAllowedOrigin() throws Exception {
        TestUser user = auth.user();
        String body = "{\"email\":\"" + user.email() + "\",\"password\":\"" + user.password() + "\"}";

        mvc.perform(post("/api/v1/auth/login")
                        .header("Origin", AuthTestSupport.ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        mvc.perform(
                        post("/api/v1/auth/login")
                                .with(org.springframework.security.test.web.servlet.request
                                        .SecurityMockMvcRequestPostProcessors.csrf())
                                .header("Origin", "https://evil.example")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"));
    }

    @Test
    void csrfBootstrapEndpointIssuesAToken() throws Exception {
        mvc.perform(get("/api/v1/auth/csrf"))
                .andExpect(status().isOk())
                // Header names are case-insensitive; Spring's csrf() test helper may swap in its default spelling.
                .andExpect(jsonPath("$.headerName").value(org.hamcrest.Matchers.equalToIgnoringCase("X-CSRF-Token")))
                .andExpect(jsonPath("$.token").isNotEmpty());
    }

    @Test
    void forgedOrGarbageSessionCookiesAreAnonymous() throws Exception {
        mvc.perform(get("/api/v1/me").cookie(new Cookie(SESSION_COOKIE, SecureTokens.newSecret())))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/me").cookie(new Cookie(SESSION_COOKIE, "x' OR '1'='1")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void responsesNeverContainCredentials() throws Exception {
        TestUser user = auth.user();
        Cookie session = auth.login(user);

        mvc.perform(get("/api/v1/me").cookie(session))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(not(containsString("argon2"))));
    }

    private ResultActions login(String email, String password) throws Exception {
        return mvc.perform(unsafe(post("/api/v1/auth/login"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"));
    }

    private List<String> auditActions(UUID userId) {
        return CurrentContext.callWith(
                RequestContext.forRequest("test-" + UUID.randomUUID()).withGlobalAccess(),
                () -> tx.execute(status -> dsl.select(AUDIT_LOG.ACTION)
                        .from(AUDIT_LOG)
                        .where(AUDIT_LOG.ENTITY_ID.eq(userId).or(AUDIT_LOG.ACTOR_USER_ID.eq(userId)))
                        .fetch(AUDIT_LOG.ACTION)));
    }

    private static String strip(String problemJson) {
        return problemJson.replaceAll("\"requestId\":\"[^\"]*\"", "");
    }
}
