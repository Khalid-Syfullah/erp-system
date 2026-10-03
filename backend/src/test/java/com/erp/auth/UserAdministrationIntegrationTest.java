package com.erp.auth;

import static com.erp.db.auth.Tables.USERS;
import static com.erp.support.AuthTestSupport.unsafe;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erp.auth.persistence.UserRepository;
import com.erp.support.AuthTestSupport;
import com.erp.support.AuthTestSupport.TestUser;
import com.erp.support.IntegrationTest;
import com.erp.support.RecordingAccountNotifier;
import com.erp.support.TestDatabase;
import jakarta.servlet.http.Cookie;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

/** User administration rules and their error codes (API.md §6.1, §17.2; ADR-032). */
class UserAdministrationIntegrationTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    AuthTestSupport auth;

    @Autowired
    DSLContext dsl;

    @Autowired
    RecordingAccountNotifier mail;

    @Test
    void administratorsInviteUsersAndEmailsAreUnique() throws Exception {
        Cookie session = auth.login(auth.systemAdmin());
        String email = "new.colleague." + UUID.randomUUID() + "@example.test";

        mvc.perform(unsafe(post("/api/v1/admin/users"))
                        .cookie(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email.toUpperCase() + "\",\"displayName\":\"New Colleague\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.status").value("INVITED"));
        assertThat(mail.last(email, "INVITATION")).isPresent();

        mvc.perform(unsafe(post("/api/v1/admin/users"))
                        .cookie(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"displayName\":\"Duplicate\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_EMAIL"));
    }

    @Test
    void aRoleCanBeAssignedOnlyOncePerCompany() throws Exception {
        Cookie session = auth.login(auth.systemAdmin());
        UUID company = auth.company();
        TestUser user = auth.user();
        String body = "{\"companyId\":\"" + company + "\",\"roleId\":\"" + auth.roleId("AUDITOR") + "\"}";

        mvc.perform(unsafe(post("/api/v1/admin/users/" + user.id() + "/role-assignments"))
                        .cookie(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());
        mvc.perform(unsafe(post("/api/v1/admin/users/" + user.id() + "/role-assignments"))
                        .cookie(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_ASSIGNMENT"));
    }

    @Test
    void mfaCannotBeRemovedWhenNotEnrolled() throws Exception {
        Cookie session = auth.login(auth.user());

        mvc.perform(unsafe(delete("/api/v1/me/mfa/totp")).cookie(session))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MFA_NOT_ENROLLED"));
    }

    @Test
    void administrativeUserActions() throws Exception {
        Cookie session = auth.login(auth.systemAdmin());
        TestUser user = auth.enrollMfa(auth.user());
        String path = "/api/v1/admin/users/" + user.id();

        mvc.perform(get("/api/v1/admin/users").cookie(session).param("filter[email]", user.email()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(user.id().toString()));
        mvc.perform(get(path).cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mfaEnabled").value(true));

        // Locks: an administrative lock is lifted by unlock; an unlocked user cannot be unlocked.
        dsl.update(USERS)
                .set(USERS.STATUS, "LOCKED")
                .where(USERS.ID.eq(user.id()))
                .execute();
        action(session, user, "unlock")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        action(session, user, "unlock")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE"));
        dsl.update(USERS)
                .set(USERS.LOCKED_UNTIL, java.time.OffsetDateTime.now().plusMinutes(10))
                .where(USERS.ID.eq(user.id()))
                .execute();
        action(session, user, "unlock").andExpect(status().isOk());

        // MFA reset removes the authenticator; a second reset has nothing to remove.
        action(session, user, "reset-mfa")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mfaEnabled").value(false));
        action(session, user, "reset-mfa")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MFA_NOT_ENROLLED"));

        // Disable, enable, and the state rules around them.
        action(session, user, "enable").andExpect(status().isConflict());
        action(session, user, "disable").andExpect(status().isOk());
        action(session, user, "disable").andExpect(status().isConflict());
        action(session, user, "enable")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        // Invitations can be resent only to invited users.
        action(session, user, "resend-invite").andExpect(status().isConflict());
        dsl.update(USERS)
                .set(USERS.STATUS, "INVITED")
                .setNull(USERS.PASSWORD_HASH)
                .where(USERS.ID.eq(user.id()))
                .execute();
        action(session, user, "resend-invite").andExpect(status().isOk());
        assertThat(mail.last(user.email(), "INVITATION")).isPresent();
    }

    @Test
    void administratorsRevokeSessionsAndEditProfiles() throws Exception {
        TestUser admin = auth.systemAdmin();
        Cookie session = auth.login(admin);
        TestUser user = auth.user();
        Cookie userSession = auth.login(user);
        String path = "/api/v1/admin/users/" + user.id();

        mvc.perform(unsafe(post(path + "/revoke-sessions")).cookie(session)).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/me").cookie(userSession)).andExpect(status().isUnauthorized());

        mvc.perform(unsafe(patch(path))
                        .cookie(session)
                        .header("If-Match", etag(user))
                        .contentType("application/merge-patch+json")
                        .content("{\"locale\":\"english\",\"timezone\":\"Atlantis/Lost\"}"))
                .andExpect(status().isUnprocessableContent());
        mvc.perform(unsafe(patch(path))
                        .cookie(session)
                        .header("If-Match", etag(user))
                        .contentType("application/merge-patch+json")
                        .content("{\"displayName\":\"Renamed\",\"locale\":\"de-DE\",\"timezone\":\"Europe/Berlin\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Renamed"))
                .andExpect(jsonPath("$.locale").value("de-DE"));

        // Promotion to system administrator ends the user's sessions (they must sign in with MFA).
        Cookie again = auth.login(user);
        mvc.perform(unsafe(patch(path))
                        .cookie(session)
                        .header("If-Match", etag(user))
                        .contentType("application/merge-patch+json")
                        .content("{\"isSystemAdmin\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isSystemAdmin").value(true));
        mvc.perform(get("/api/v1/me").cookie(again)).andExpect(status().isUnauthorized());
    }

    @Test
    void serviceAccountsCannotBecomeSystemAdministrators() throws Exception {
        Cookie session = auth.login(auth.systemAdmin());
        String body = mvc.perform(unsafe(post("/api/v1/admin/service-accounts"))
                        .cookie(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"svc-" + UUID.randomUUID() + "@example.test\",\"displayName\":\"Svc\"}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String id = com.jayway.jsonpath.JsonPath.read(body, "$.id");

        mvc.perform(get("/api/v1/admin/service-accounts").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].id").value(org.hamcrest.Matchers.hasItem(id)));
        mvc.perform(unsafe(patch("/api/v1/admin/users/" + id))
                        .cookie(session)
                        .header("If-Match", "W/\"0\"")
                        .contentType("application/merge-patch+json")
                        .content("{\"isSystemAdmin\":true}"))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void usersEditTheirOwnProfileAndStepUpWithTheirPassword() throws Exception {
        TestUser user = auth.user();
        Cookie session = auth.login(user);

        mvc.perform(unsafe(patch("/api/v1/me"))
                        .cookie(session)
                        .header("If-Match", etag(user))
                        .contentType("application/merge-patch+json")
                        .content("{\"isSystemAdmin\":true}"))
                .andExpect(status().isBadRequest());
        mvc.perform(unsafe(patch("/api/v1/me"))
                        .cookie(session)
                        .header("If-Match", etag(user))
                        .contentType("application/merge-patch+json")
                        .content("{\"displayName\":\"Me Myself\",\"timezone\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Me Myself"));

        mvc.perform(unsafe(post("/api/v1/me/reauthenticate"))
                        .cookie(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"Not-My-Password-123\"}"))
                .andExpect(status().isUnprocessableContent());
        var stepUp = mvc.perform(unsafe(post("/api/v1/me/reauthenticate"))
                        .cookie(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"" + user.password() + "\"}"))
                .andExpect(status().isNoContent())
                .andReturn();
        // The step-up rotated the session secret: the old cookie is dead, the new one works.
        mvc.perform(get("/api/v1/me").cookie(session)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/me").cookie(AuthTestSupport.sessionCookie(stepUp)))
                .andExpect(status().isOk());
    }

    private org.springframework.test.web.servlet.ResultActions action(Cookie session, TestUser user, String action)
            throws Exception {
        return mvc.perform(unsafe(post("/api/v1/admin/users/" + user.id() + "/" + action))
                .cookie(session)
                .header("If-Match", etag(user)));
    }

    private String etag(TestUser user) {
        return "W/\"" + dsl.fetchValue(USERS.VERSION, USERS.ID.eq(user.id())) + "\"";
    }

    /**
     * Two administrators disable each other at the same moment. The guard serializes on an advisory
     * lock (DATABASE.md §9): exactly one succeeds, the other gets LAST_SYSTEM_ADMIN.
     */
    @Test
    void concurrentMutualDisablingLeavesOneSystemAdministrator() throws Exception {
        TestUser first = auth.systemAdmin();
        TestUser second = auth.systemAdmin();
        Cookie firstSession = auth.login(first);
        Cookie secondSession = auth.login(second);
        dsl.update(USERS)
                .set(USERS.STATUS, "DISABLED")
                .where(USERS.IS_SYSTEM_ADMIN.isTrue())
                .and(USERS.STATUS.eq("ACTIVE"))
                .and(USERS.ID.notIn(first.id(), second.id()))
                .execute();

        List<MockHttpServletResponse> responses = new ArrayList<>();
        try (Connection holder = TestDatabase.connectAs("erp_app", TestDatabase.APP_PASSWORD)) {
            holder.setAutoCommit(false);
            try (PreparedStatement lock = holder.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {
                lock.setLong(1, UserRepository.SYSTEM_ADMIN_SET_LOCK);
                lock.execute();
            }
            CompletableFuture<MockHttpServletResponse> firstDisablesSecond =
                    CompletableFuture.supplyAsync(() -> disable(firstSession, second));
            CompletableFuture<MockHttpServletResponse> secondDisablesFirst =
                    CompletableFuture.supplyAsync(() -> disable(secondSession, first));
            awaitAdvisoryLockWaiters(2);
            holder.commit();
            responses.add(firstDisablesSecond.get(30, TimeUnit.SECONDS));
            responses.add(secondDisablesFirst.get(30, TimeUnit.SECONDS));
        }

        assertThat(responses).extracting(MockHttpServletResponse::getStatus).containsExactlyInAnyOrder(200, 409);
        assertThat(responses.stream()
                        .filter(r -> r.getStatus() == 409)
                        .findFirst()
                        .orElseThrow()
                        .getContentAsString())
                .contains("\"LAST_SYSTEM_ADMIN\"");
        assertThat(dsl.fetchCount(USERS, USERS.IS_SYSTEM_ADMIN.isTrue().and(USERS.STATUS.eq("ACTIVE"))))
                .isEqualTo(1);
    }

    private MockHttpServletResponse disable(Cookie session, TestUser target) {
        try {
            int version = dsl.fetchValue(USERS.VERSION, USERS.ID.eq(target.id()));
            return mvc.perform(unsafe(post("/api/v1/admin/users/" + target.id() + "/disable"))
                            .cookie(session)
                            .header("If-Match", "W/\"" + version + "\""))
                    .andReturn()
                    .getResponse();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void awaitAdvisoryLockWaiters(int expected) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
        try (Connection c = TestDatabase.superuserConnection(TestDatabase.DATABASE);
                Statement s = c.createStatement()) {
            while (Instant.now().isBefore(deadline)) {
                try (ResultSet rs =
                        s.executeQuery("SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND NOT granted")) {
                    rs.next();
                    if (rs.getInt(1) >= expected) {
                        return;
                    }
                }
                Thread.onSpinWait();
            }
        }
        throw new AssertionError("Requests did not wait for the system administrator lock");
    }
}
