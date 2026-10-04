package com.erp.support;

import static com.erp.db.auth.Tables.MFA_TOTP;
import static com.erp.db.auth.Tables.ROLES;
import static com.erp.db.auth.Tables.ROLE_ASSIGNMENTS;
import static com.erp.db.auth.Tables.ROLE_ASSIGNMENT_BRANCHES;
import static com.erp.db.auth.Tables.ROLE_PERMISSIONS;
import static com.erp.db.auth.Tables.USERS;
import static com.erp.db.org.Tables.BRANCHES;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.erp.auth.application.Passwords;
import com.erp.auth.application.PermissionResolver;
import com.erp.auth.domain.SecureTokens;
import com.erp.auth.domain.Totp;
import com.erp.org.events.CompanyCreated;
import com.erp.platform.context.CurrentContext;
import com.erp.platform.context.RequestContext;
import com.erp.platform.crypto.FieldEncryptor;
import com.erp.platform.events.DomainEvents;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Fixtures for authentication and authorization tests. Users are created directly in the database
 * (fast); logins go through the real HTTP endpoints (CSRF, Origin, cookies, MFA).
 */
@TestComponent
public class AuthTestSupport {

    public static final String ORIGIN = "http://localhost";
    public static final String SESSION_COOKIE = "__Host-erp_session";
    public static final String CHALLENGE_COOKIE = "__Host-erp_mfa";
    public static final String DEFAULT_PASSWORD_PREFIX = "Plausible-Granite-Teapot-";

    /** A created user and the secrets the test knows. */
    public record TestUser(UUID id, String email, String displayName, String password, byte[] totpSecret) {

        public TestUser withTotp(byte[] secret) {
            return new TestUser(id, email, displayName, password, secret);
        }
    }

    private final DSLContext dsl;
    private final Passwords passwords;
    private final FieldEncryptor encryptor;
    private final PermissionResolver permissionResolver;
    private final TransactionTemplate tx;
    private final MockMvc mvc;
    private final ApplicationEventPublisher events;

    public AuthTestSupport(
            DSLContext dsl,
            Passwords passwords,
            FieldEncryptor encryptor,
            PermissionResolver permissionResolver,
            TransactionTemplate tx,
            MockMvc mvc,
            ApplicationEventPublisher events) {
        this.events = events;
        this.dsl = dsl;
        this.passwords = passwords;
        this.encryptor = encryptor;
        this.permissionResolver = permissionResolver;
        this.tx = tx;
        this.mvc = mvc;
    }

    // ------------------------------------------------------------------------- data fixtures

    public TestUser user() {
        return user(false);
    }

    public TestUser user(boolean systemAdmin) {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        String email = "user-" + unique + "@example.test";
        String displayName = "Test User";
        String password = DEFAULT_PASSWORD_PREFIX + unique;
        UUID id = dsl.insertInto(USERS)
                .set(USERS.EMAIL, email)
                .set(USERS.DISPLAY_NAME, displayName)
                .set(USERS.STATUS, "ACTIVE")
                .set(USERS.PASSWORD_HASH, passwords.hash(password))
                .set(USERS.PASSWORD_CHANGED_AT, OffsetDateTime.now())
                .set(USERS.IS_SYSTEM_ADMIN, systemAdmin)
                .returning(USERS.ID)
                .fetchOne(USERS.ID);
        return new TestUser(id, email, displayName, password, null);
    }

    /** A system administrator with confirmed MFA (MFA is mandatory for system admins). */
    public TestUser systemAdmin() {
        return enrollMfa(user(true));
    }

    /** Stores a confirmed TOTP secret for the user (as MfaService would) and returns it with the user. */
    public TestUser enrollMfa(TestUser user) {
        byte[] secret = SecureTokens.randomBytes(Totp.SECRET_BYTES);
        dsl.insertInto(MFA_TOTP)
                .set(MFA_TOTP.USER_ID, user.id())
                .set(MFA_TOTP.SECRET_ENCRYPTED, encryptor.encrypt(secret, "auth.mfa_totp.secret:" + user.id()))
                .set(MFA_TOTP.SECRET_KEY_VERSION, (short) encryptor.activeKeyVersion())
                .set(MFA_TOTP.CONFIRMED_AT, OffsetDateTime.now())
                .execute();
        dsl.update(USERS)
                .set(USERS.MFA_ENABLED, true)
                .where(USERS.ID.eq(user.id()))
                .execute();
        return user.withTotp(secret);
    }

    /**
     * A new company, set up as the API would set it up: {@code org.company.created} is published in
     * the creating transaction, so Accounting seeds its chart of accounts and fiscal year (ADR-038).
     */
    public UUID company() {
        return tx.execute(status -> {
            UUID id = TestCompanies.create(dsl);
            events.publishEvent(new CompanyCreated(
                    DomainEvents.metadata(CompanyCreated.TYPE, CompanyCreated.SCHEMA_VERSION, id, Clock.systemUTC()),
                    id,
                    "T",
                    "USD",
                    "US",
                    1,
                    "America/New_York"));
            return id;
        });
    }

    public UUID branch(UUID companyId, String code) {
        return CurrentContext.callWith(
                RequestContext.forRequest("fixture-" + UUID.randomUUID()).withCompany(companyId),
                () -> tx.execute(status -> dsl.insertInto(BRANCHES)
                        .set(BRANCHES.COMPANY_ID, companyId)
                        .set(BRANCHES.CODE, code)
                        .set(BRANCHES.NAME, "Branch " + code)
                        .returning(BRANCHES.ID)
                        .fetchOne(BRANCHES.ID)));
    }

    public UUID roleId(String code) {
        return dsl.select(ROLES.ID).from(ROLES).where(ROLES.CODE.eq(code)).fetchOne(ROLES.ID);
    }

    /** A custom role with exactly the given permissions. */
    public UUID customRole(String... permissions) {
        String code = "TEST_"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        UUID id = dsl.insertInto(ROLES)
                .set(ROLES.CODE, code)
                .set(ROLES.NAME, "Test role " + code)
                .returning(ROLES.ID)
                .fetchOne(ROLES.ID);
        for (String permission : permissions) {
            dsl.insertInto(ROLE_PERMISSIONS)
                    .set(ROLE_PERMISSIONS.ROLE_ID, id)
                    .set(ROLE_PERMISSIONS.PERMISSION_CODE, permission)
                    .execute();
        }
        return id;
    }

    public UUID assign(TestUser user, UUID roleId, UUID companyId, UUID... branches) {
        UUID id = dsl.insertInto(ROLE_ASSIGNMENTS)
                .set(ROLE_ASSIGNMENTS.USER_ID, user.id())
                .set(ROLE_ASSIGNMENTS.ROLE_ID, roleId)
                .set(ROLE_ASSIGNMENTS.COMPANY_ID, companyId)
                .returning(ROLE_ASSIGNMENTS.ID)
                .fetchOne(ROLE_ASSIGNMENTS.ID);
        for (UUID branch : branches) {
            dsl.insertInto(ROLE_ASSIGNMENT_BRANCHES)
                    .set(ROLE_ASSIGNMENT_BRANCHES.ROLE_ASSIGNMENT_ID, id)
                    .set(ROLE_ASSIGNMENT_BRANCHES.COMPANY_ID, companyId)
                    .set(ROLE_ASSIGNMENT_BRANCHES.BRANCH_ID, branch)
                    .execute();
        }
        permissionResolver.invalidateUser(user.id());
        return id;
    }

    public UUID assign(TestUser user, String roleCode, UUID companyId, UUID... branches) {
        return assign(user, roleId(roleCode), companyId, branches);
    }

    // ------------------------------------------------------------------------------- HTTP

    /** Logs in through the API (second factor included when enrolled) and returns the session cookie. */
    public Cookie login(TestUser user) throws Exception {
        return login(user, "127.0.0.1");
    }

    /** Logs in from the given client address (login attempts are limited per address). */
    public Cookie login(TestUser user, String clientIp) throws Exception {
        MvcResult result = mvc.perform(unsafe(post("/api/v1/auth/login"))
                        .with(request -> {
                            request.setRemoteAddr(clientIp);
                            return request;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + user.email() + "\",\"password\":\"" + user.password() + "\"}"))
                .andReturn();
        if (result.getResponse().getStatus() == 200) {
            return sessionCookie(result);
        }
        if (user.totpSecret() == null || result.getResponse().getStatus() != 401) {
            throw new AssertionError("Login failed: " + result.getResponse().getStatus() + " "
                    + result.getResponse().getContentAsString());
        }
        Cookie challenge = result.getResponse().getCookie(CHALLENGE_COOKIE);
        // Tests log in repeatedly within one TOTP step: forget the last used step first.
        dsl.update(MFA_TOTP)
                .setNull(MFA_TOTP.LAST_USED_STEP)
                .where(MFA_TOTP.USER_ID.eq(user.id()))
                .execute();
        MvcResult second = mvc.perform(unsafe(post("/api/v1/auth/login/mfa"))
                        .cookie(challenge)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + currentCode(user) + "\"}"))
                .andReturn();
        if (second.getResponse().getStatus() != 200) {
            throw new AssertionError("MFA step failed: " + second.getResponse().getContentAsString());
        }
        return sessionCookie(second);
    }

    public static String currentCode(TestUser user) {
        return Totp.code(user.totpSecret(), Totp.stepAt(Instant.now()));
    }

    /** CSRF token, allowed Origin: what the SPA sends with every unsafe request. */
    public static MockHttpServletRequestBuilder unsafe(MockHttpServletRequestBuilder request) {
        return request.with(csrf()).header("Origin", ORIGIN);
    }

    public static Cookie sessionCookie(MvcResult result) {
        Cookie cookie = result.getResponse().getCookie(SESSION_COOKIE);
        if (cookie == null || cookie.getValue().isEmpty()) {
            throw new AssertionError("No session cookie in response");
        }
        return cookie;
    }

    public void invalidatePermissionCache() {
        permissionResolver.invalidateAll();
    }
}
