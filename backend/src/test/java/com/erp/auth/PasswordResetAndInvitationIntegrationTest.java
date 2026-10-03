package com.erp.auth;

import static com.erp.db.auth.Tables.USERS;
import static com.erp.db.auth.Tables.USER_TOKENS;
import static com.erp.support.AuthTestSupport.unsafe;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erp.auth.domain.SecureTokens;
import com.erp.support.AuthTestSupport;
import com.erp.support.AuthTestSupport.TestUser;
import com.erp.support.IntegrationTest;
import com.erp.support.RecordingAccountNotifier;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Forgotten-password reset and invitation acceptance (SECURITY.md §3.2). */
class PasswordResetAndInvitationIntegrationTest extends IntegrationTest {

    static final String NEW_PASSWORD = "Freshly-Minted-Teapot-77"; // gitleaks:allow (test-only password)

    @Autowired
    MockMvc mvc;

    @Autowired
    AuthTestSupport auth;

    @Autowired
    RecordingAccountNotifier mail;

    @Autowired
    DSLContext dsl;

    @Test
    void forgotPasswordAnswersTheSameForUnknownAccounts() throws Exception {
        String unknown = "ghost-" + UUID.randomUUID() + "@example.test";

        forgot(unknown).andExpect(status().isAccepted());

        assertThat(mail.to(unknown)).isEmpty();
    }

    @Test
    void resetLinkSetsANewPasswordOnceAndEndsAllSessions() throws Exception {
        TestUser user = auth.user();
        Cookie session = auth.login(user);

        forgot(user.email()).andExpect(status().isAccepted());
        RecordingAccountNotifier.Sent sent =
                mail.last(user.email(), "PASSWORD_RESET").orElseThrow();
        assertThat(sent.link()).startsWith("http://localhost:5173/reset-password#token=");
        String token = sent.token();
        // Only the token's hash is stored.
        assertThat(dsl.fetchExists(USER_TOKENS, USER_TOKENS.TOKEN_HASH.eq(SecureTokens.sha256(token))))
                .isTrue();

        reset(token, NEW_PASSWORD).andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/me").cookie(session)).andExpect(status().isUnauthorized());
        auth.login(new TestUser(user.id(), user.email(), user.displayName(), NEW_PASSWORD, null));
        reset(token, "Another-Granite-Teapot-88")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TOKEN"));
        assertThat(mail.last(user.email(), "PASSWORD_CHANGED")).isPresent();
    }

    @Test
    void expiredAndForgedResetTokensAreRejected() throws Exception {
        TestUser user = auth.user();
        forgot(user.email()).andExpect(status().isAccepted());
        String token = mail.last(user.email(), "PASSWORD_RESET").orElseThrow().token();
        dsl.update(USER_TOKENS)
                .set(USER_TOKENS.CREATED_AT, OffsetDateTime.now().minusHours(1))
                .set(USER_TOKENS.EXPIRES_AT, OffsetDateTime.now().minusSeconds(1))
                .where(USER_TOKENS.TOKEN_HASH.eq(SecureTokens.sha256(token)))
                .execute();

        reset(token, NEW_PASSWORD)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TOKEN"));
        reset(SecureTokens.newSecret(), NEW_PASSWORD).andExpect(status().isBadRequest());
        reset("short", NEW_PASSWORD).andExpect(status().isBadRequest());
    }

    @Test
    void aNewResetRequestInvalidatesTheEarlierLink() throws Exception {
        TestUser user = auth.user();
        forgot(user.email());
        String first = mail.last(user.email(), "PASSWORD_RESET").orElseThrow().token();
        forgot(user.email());
        String second = mail.last(user.email(), "PASSWORD_RESET").orElseThrow().token();

        reset(first, NEW_PASSWORD).andExpect(status().isBadRequest());
        reset(second, NEW_PASSWORD).andExpect(status().isNoContent());
    }

    @Test
    void resetEnforcesThePasswordPolicy() throws Exception {
        TestUser user = auth.user();
        forgot(user.email());
        String token = mail.last(user.email(), "PASSWORD_RESET").orElseThrow().token();

        reset(token, "short")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].code").value("TOO_SHORT"));
        // The failed attempt did not consume the token.
        reset(token, NEW_PASSWORD).andExpect(status().isNoContent());
    }

    @Test
    void invitedUsersActivateThroughTheEmailedLink() throws Exception {
        TestUser admin = auth.systemAdmin();
        Cookie adminSession = auth.login(admin);
        String email = "invitee-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test";

        String created = mvc.perform(unsafe(post("/api/v1/admin/users"))
                        .cookie(adminSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"displayName\":\"New Colleague\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("INVITED"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        UUID userId = UUID.fromString(JsonPath.read(created, "$.id"));
        String token = mail.last(email, "INVITATION").orElseThrow().token();

        mvc.perform(unsafe(post("/api/v1/auth/invitations/accept"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\",\"password\":\"" + NEW_PASSWORD + "\"}"))
                .andExpect(status().isNoContent());

        assertThat(dsl.fetchValue(USERS.STATUS, USERS.ID.eq(userId))).isEqualTo("ACTIVE");
        auth.login(new TestUser(userId, email, "New Colleague", NEW_PASSWORD, null));
        mvc.perform(unsafe(post("/api/v1/auth/invitations/accept"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\",\"password\":\"" + NEW_PASSWORD + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void invitationRejectsPasswordsContainingTheName() throws Exception {
        TestUser admin = auth.systemAdmin();
        Cookie adminSession = auth.login(admin);
        String email = "named-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test";
        mvc.perform(unsafe(post("/api/v1/admin/users"))
                        .cookie(adminSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"displayName\":\"Bartholomew Smith\"}"))
                .andExpect(status().isCreated());
        String token = mail.last(email, "INVITATION").orElseThrow().token();

        mvc.perform(unsafe(post("/api/v1/auth/invitations/accept"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\",\"password\":\"bartholomew-rules-2026\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].code").value("CONTAINS_PERSONAL_INFO"));
    }

    @Test
    void passwordsAreNotTrimmed() throws Exception {
        TestUser user = auth.user();
        forgot(user.email());
        String token = mail.last(user.email(), "PASSWORD_RESET").orElseThrow().token();

        reset(token, "  " + NEW_PASSWORD + "  ").andExpect(status().isNoContent());

        auth.login(new TestUser(user.id(), user.email(), user.displayName(), "  " + NEW_PASSWORD + "  ", null));
    }

    private ResultActions forgot(String email) throws Exception {
        return mvc.perform(unsafe(post("/api/v1/auth/password/forgot"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\"}"));
    }

    private ResultActions reset(String token, String password) throws Exception {
        return mvc.perform(unsafe(post("/api/v1/auth/password/reset"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token + "\",\"newPassword\":\"" + password + "\"}"));
    }
}
