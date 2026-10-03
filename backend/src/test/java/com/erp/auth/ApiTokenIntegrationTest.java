package com.erp.auth;

import static com.erp.db.auth.Tables.API_TOKENS;
import static com.erp.db.auth.Tables.USERS;
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
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** API tokens: issuance, bearer authentication, scoping, expiry and revocation (SECURITY.md §3.6). */
class ApiTokenIntegrationTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    AuthTestSupport auth;

    @Autowired
    DSLContext dsl;

    /** A user who may hold personal tokens in a company with branch-read rights. */
    private record Fixture(TestUser user, UUID company, Cookie session) {}

    private Fixture tokenHolder() throws Exception {
        TestUser user = auth.user();
        UUID company = auth.company();
        auth.assign(
                user, auth.customRole("auth.api_token.manage_own", "org.branch.read", "org.branch.manage"), company);
        return new Fixture(user, company, auth.login(user));
    }

    private String createToken(Cookie session, String body) throws Exception {
        String response = mvc.perform(unsafe(post("/api/v1/me/api-tokens"))
                        .cookie(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JsonPath.read(response, "$.token");
    }

    @Test
    void personalTokensAuthenticateBearerRequestsWithoutCsrf() throws Exception {
        Fixture f = tokenHolder();
        String token = createToken(f.session(), "{\"name\":\"ci\",\"expiresInDays\":30}");

        assertThat(token).matches("^erp_pat_[A-Za-z0-9]{8}_[A-Za-z0-9_-]{43}$");
        assertThat(dsl.fetchExists(API_TOKENS, API_TOKENS.TOKEN_HASH.eq(SecureTokens.sha256(token))))
                .isTrue();
        mvc.perform(get("/api/v1/companies/" + f.company() + "/branches").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        // Unsafe bearer requests need neither CSRF token nor Origin.
        mvc.perform(post("/api/v1/companies/" + f.company() + "/branches")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"API1\",\"name\":\"Created by token\"}"))
                .andExpect(status().isCreated());
        // Tokens cannot manage the owner's sessions or mint further (possibly less restricted) tokens.
        mvc.perform(get("/api/v1/me/sessions").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/me/api-tokens")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"minted\",\"expiresInDays\":365}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("REAUTHENTICATION_REQUIRED"));
        // The secret is never listed again.
        mvc.perform(get("/api/v1/me/api-tokens").cookie(f.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].token").doesNotExist())
                .andExpect(jsonPath("$.data[0].prefix").value(token.substring(8, 16)));
    }

    @Test
    void creatingPersonalTokensNeedsThePermissionAndARecentPassword() throws Exception {
        TestUser plain = auth.user();
        UUID company = auth.company();
        auth.assign(plain, auth.customRole("org.branch.read"), company);
        mvc.perform(unsafe(post("/api/v1/me/api-tokens"))
                        .cookie(auth.login(plain))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\"}"))
                .andExpect(status().isForbidden());

        Fixture f = tokenHolder();
        dsl.execute(
                "UPDATE auth.sessions SET authenticated_at = now() - interval '10 minutes' WHERE user_id = ?",
                f.user().id());
        mvc.perform(unsafe(post("/api/v1/me/api-tokens"))
                        .cookie(f.session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("REAUTHENTICATION_REQUIRED"));
    }

    @Test
    void invalidExpiredAndRevokedTokensAreRejected() throws Exception {
        Fixture f = tokenHolder();
        String token = createToken(f.session(), "{\"name\":\"t\"}");
        String path = "/api/v1/companies/" + f.company() + "/branches";

        mvc.perform(get(path).header("Authorization", "Bearer erp_pat_AAAAAAAA_" + "A".repeat(43)))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(path).header("Authorization", "Basic dXNlcjpwYXNz")).andExpect(status().isUnauthorized());

        dsl.update(API_TOKENS)
                .set(API_TOKENS.CREATED_AT, OffsetDateTime.now().minusDays(2))
                .set(API_TOKENS.EXPIRES_AT, OffsetDateTime.now().minusSeconds(1))
                .where(API_TOKENS.TOKEN_HASH.eq(SecureTokens.sha256(token)))
                .execute();
        mvc.perform(get(path).header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());

        String second = createToken(f.session(), "{\"name\":\"t2\"}");
        UUID secondId = dsl.fetchValue(API_TOKENS.ID, API_TOKENS.TOKEN_HASH.eq(SecureTokens.sha256(second)));
        mvc.perform(unsafe(delete("/api/v1/me/api-tokens/" + secondId)).cookie(f.session()))
                .andExpect(status().isNoContent());
        mvc.perform(get(path).header("Authorization", "Bearer " + second)).andExpect(status().isUnauthorized());
    }

    @Test
    void bearerRequestsIgnoreSessionCookies() throws Exception {
        Fixture f = tokenHolder();

        mvc.perform(get("/api/v1/me")
                        .cookie(f.session())
                        .header("Authorization", "Bearer erp_pat_AAAAAAAA_" + "A".repeat(43)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void companyRestrictionAndPermissionDownScopingApply() throws Exception {
        Fixture f = tokenHolder();
        UUID otherCompany = auth.company();
        auth.assign(f.user(), auth.customRole("org.branch.read"), otherCompany);

        String restricted = createToken(
                f.session(),
                "{\"name\":\"r\",\"companyId\":\"" + f.company() + "\",\"allowedPermissions\":[\"org.branch.read\"]}");

        mvc.perform(get("/api/v1/companies/" + f.company() + "/branches")
                        .header("Authorization", "Bearer " + restricted))
                .andExpect(status().isOk());
        // Another company the user belongs to is invisible to the restricted token.
        mvc.perform(get("/api/v1/companies/" + otherCompany + "/branches")
                        .header("Authorization", "Bearer " + restricted))
                .andExpect(status().isNotFound());
        // The user holds org.branch.manage, the token does not.
        mvc.perform(post("/api/v1/companies/" + f.company() + "/branches")
                        .header("Authorization", "Bearer " + restricted)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"NOPE\",\"name\":\"Denied\"}"))
                .andExpect(status().isForbidden());
        // Tokens cannot mint tokens (no step-up possible).
        mvc.perform(post("/api/v1/me/api-tokens")
                        .header("Authorization", "Bearer " + restricted)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"child\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void tokensCannotBeScopedBeyondTheOwnersPermissions() throws Exception {
        Fixture f = tokenHolder();

        mvc.perform(unsafe(post("/api/v1/me/api-tokens"))
                        .cookie(f.session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\",\"allowedPermissions\":[\"accounting.payment.void\"]}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].code").value("NOT_HELD"));
        mvc.perform(unsafe(post("/api/v1/me/api-tokens"))
                        .cookie(f.session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\",\"expiresInDays\":400}"))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void disablingTheOwnerRevokesTokensImmediately() throws Exception {
        Fixture f = tokenHolder();
        String token = createToken(f.session(), "{\"name\":\"t\"}");
        dsl.update(USERS)
                .set(USERS.STATUS, "DISABLED")
                .where(USERS.ID.eq(f.user().id()))
                .execute();

        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void serviceAccountsGetTokensFromSystemAdministrators() throws Exception {
        TestUser admin = auth.systemAdmin();
        Cookie adminSession = auth.login(admin);
        String email = "svc-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test";
        String account = mvc.perform(unsafe(post("/api/v1/admin/service-accounts"))
                        .cookie(adminSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"displayName\":\"Integration\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userType").value("SERVICE"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        UUID serviceId = UUID.fromString(JsonPath.read(account, "$.id"));
        UUID company = auth.company();
        auth.assign(
                new TestUser(serviceId, email, "Integration", null, null), auth.customRole("org.branch.read"), company);

        String issued = mvc.perform(unsafe(post("/api/v1/admin/service-accounts/" + serviceId + "/api-tokens"))
                        .cookie(adminSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"erp-sync\",\"expiresInDays\":7}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String token = JsonPath.read(issued, "$.token");

        mvc.perform(get("/api/v1/companies/" + company + "/branches").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        // Service accounts cannot log in with a password.
        mvc.perform(unsafe(post("/api/v1/auth/login"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"Anything-Granite-Teapot\"}"))
                .andExpect(status().isUnauthorized());
    }
}
