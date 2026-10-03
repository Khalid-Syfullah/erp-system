package com.erp.auth.application;

import com.erp.auth.domain.SecureTokens;
import com.erp.auth.domain.UserStatus;
import com.erp.auth.domain.UserType;
import com.erp.auth.persistence.SessionRepository;
import com.erp.auth.persistence.UserRepository;
import com.erp.auth.persistence.UserTokenRepository;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditPort;
import com.erp.platform.security.AuthenticatedActor;
import com.erp.platform.tx.AfterCommit;
import com.erp.platform.web.ApiException;
import com.erp.platform.web.FieldViolation;
import com.erp.platform.web.PlatformErrorCode;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Password lifecycle (SECURITY.md §3.2): change, forgotten-password reset and invitation acceptance.
 * Reset and invitation tokens are 256-bit secrets stored as SHA-256 hashes, single-use and
 * short-lived. Every password change signs out other sessions and invalidates outstanding reset links.
 */
@Service
public class CredentialService {

    static final String INVITE = "INVITE";
    static final String PASSWORD_RESET = "PASSWORD_RESET";

    private final UserRepository users;
    private final UserTokenRepository tokens;
    private final SessionRepository sessions;
    private final SessionService sessionService;
    private final Passwords passwords;
    private final LoginThrottle throttle;
    private final AuditPort audit;
    private final AccountNotifier notifier;
    private final AuthProperties properties;
    private final Clock clock;

    public CredentialService(
            UserRepository users,
            UserTokenRepository tokens,
            SessionRepository sessions,
            SessionService sessionService,
            Passwords passwords,
            LoginThrottle throttle,
            AuditPort audit,
            AccountNotifier notifier,
            AuthProperties properties,
            Clock clock) {
        this.users = users;
        this.tokens = tokens;
        this.sessions = sessions;
        this.sessionService = sessionService;
        this.passwords = passwords;
        this.throttle = throttle;
        this.audit = audit;
        this.notifier = notifier;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Changes the caller's password; returns the rotated session secret for the cookie. The current
     * password is checked against a small per-user failure budget.
     */
    @Transactional
    public String changePassword(AuthenticatedActor actor, String currentPassword, String newPassword) {
        AuthUser user = users.lock(actor.userId()).orElseThrow(ApiException::notFound);
        requireCurrentPassword(user, currentPassword, "/currentPassword");
        passwords.requireAcceptable(newPassword, user.email(), user.displayName(), "/newPassword");
        if (passwords.matches(newPassword, user.passwordHash())) {
            throw ApiException.validationFailed(
                    "The new password must differ from the current one.",
                    List.of(FieldViolation.atPointer(
                            "/newPassword", "UNCHANGED", "must differ from the current password")));
        }
        OffsetDateTime now = now();
        users.setPassword(user.id(), passwords.hash(newPassword), user.id(), now);
        tokens.invalidateAll(user.id(), PASSWORD_RESET, now);
        sessions.deleteForUserExcept(user.id(), actor.credentialId());
        String rotated = sessionService.rotate(actor.credentialId(), true);
        audit.record(AuditEvent.builder("PASSWORD_CHANGE", "auth")
                .entity("user", user.id(), user.email())
                .redactedChange("password")
                .build());
        AfterCommit.run(() -> notifier.sendPasswordChanged(user.email(), user.displayName()));
        return rotated;
    }

    /** Verifies the password of the signed-in user (password change, step-up); budgeted against guessing. */
    @Transactional
    public void requireCurrentPassword(AuthUser user, String password, String pointer) {
        if (!throttle.passwordConfirmationAllowed(user.id())) {
            throw new ApiException(PlatformErrorCode.RATE_LIMITED, "Too many wrong passwords. Try again later.");
        }
        if (!passwords.matches(password, user.passwordHash())) {
            throttle.recordFailedPasswordConfirmation(user.id());
            throw ApiException.validationFailed(
                    "The password is not correct.",
                    List.of(FieldViolation.atPointer(pointer, "INCORRECT_PASSWORD", "is not correct")));
        }
    }

    /**
     * Starts a password reset. Always succeeds from the caller's point of view (no account
     * enumeration); an email is sent only to an existing active human account within the limits.
     */
    @Transactional
    public void requestPasswordReset(String email, @Nullable String ip) {
        String normalized = email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
        if (!throttle.passwordResetAllowed(normalized, ip)) {
            return;
        }
        Optional<AuthUser> found = users.findByEmail(normalized)
                .filter(u -> u.userType() == UserType.HUMAN && u.status() == UserStatus.ACTIVE);
        if (found.isEmpty()) {
            return;
        }
        AuthUser user = found.get();
        OffsetDateTime now = now();
        tokens.invalidateAll(user.id(), PASSWORD_RESET, now);
        String token = SecureTokens.newSecret();
        OffsetDateTime expiresAt = now.plus(properties.tokens().passwordResetTtl());
        tokens.insert(user.id(), PASSWORD_RESET, SecureTokens.sha256(token), now, expiresAt);
        audit.record(AuditEvent.builder("PASSWORD_RESET_REQUEST", "auth")
                .entity("user", user.id(), user.email())
                .build());
        String link = link("/reset-password", token);
        AfterCommit.run(() -> notifier.sendPasswordReset(user.email(), user.displayName(), link, expiresAt));
    }

    @Transactional
    public void resetPassword(String token, String newPassword, @Nullable String ip) {
        UserTokenRepository.UserToken redeemed = redeem(token, PASSWORD_RESET, ip);
        AuthUser user = users.lock(redeemed.userId()).orElseThrow(CredentialService::invalidToken);
        if (user.status() != UserStatus.ACTIVE) {
            throw invalidToken();
        }
        passwords.requireAcceptable(newPassword, user.email(), user.displayName(), "/newPassword");
        OffsetDateTime now = now();
        users.setPassword(user.id(), passwords.hash(newPassword), user.id(), now);
        tokens.markUsed(redeemed.id(), now);
        tokens.invalidateAll(user.id(), PASSWORD_RESET, now);
        sessions.deleteForUser(user.id());
        audit.record(AuditEvent.builder("PASSWORD_RESET", "auth")
                .entity("user", user.id(), user.email())
                .actingUser(user.id())
                .redactedChange("password")
                .build());
        AfterCommit.run(() -> notifier.sendPasswordChanged(user.email(), user.displayName()));
    }

    /** Sets the first password of an invited user, who becomes active (and may then log in). */
    @Transactional
    public void acceptInvitation(String token, String password, @Nullable String ip) {
        UserTokenRepository.UserToken redeemed = redeem(token, INVITE, ip);
        AuthUser user = users.lock(redeemed.userId()).orElseThrow(CredentialService::invalidToken);
        if (user.status() != UserStatus.INVITED) {
            throw invalidToken();
        }
        passwords.requireAcceptable(password, user.email(), user.displayName(), "/password");
        OffsetDateTime now = now();
        users.setPassword(user.id(), passwords.hash(password), user.id(), now);
        tokens.markUsed(redeemed.id(), now);
        tokens.invalidateAll(user.id(), INVITE, now);
        audit.record(AuditEvent.builder("INVITE_ACCEPT", "auth")
                .entity("user", user.id(), user.email())
                .actingUser(user.id())
                .transition(UserStatus.INVITED.name(), UserStatus.ACTIVE.name())
                .build());
    }

    /** Creates an invitation token and sends the email after commit (new users and resends). */
    @Transactional
    public void issueInvitation(AuthUser user) {
        OffsetDateTime now = now();
        tokens.invalidateAll(user.id(), INVITE, now);
        String token = SecureTokens.newSecret();
        OffsetDateTime expiresAt = now.plus(properties.tokens().invitationTtl());
        tokens.insert(user.id(), INVITE, SecureTokens.sha256(token), now, expiresAt);
        String link = link("/accept-invitation", token);
        AfterCommit.run(() -> notifier.sendInvitation(user.email(), user.displayName(), link, expiresAt));
    }

    private UserTokenRepository.UserToken redeem(String token, String purpose, @Nullable String ip) {
        if (!throttle.redemptionAllowed(ip)) {
            throw new ApiException(PlatformErrorCode.RATE_LIMITED, "Too many attempts. Try again later.");
        }
        if (token == null || token.length() != 43) {
            throw invalidToken();
        }
        return tokens.lock(SecureTokens.sha256(token))
                .filter(t -> t.purpose().equals(purpose)
                        && t.usedAt() == null
                        && t.expiresAt().isAfter(now()))
                .orElseThrow(CredentialService::invalidToken);
    }

    private String link(String path, String token) {
        String base = properties.publicBaseUrl() == null
                ? ""
                : properties.publicBaseUrl().replaceAll("/+$", "");
        // The token travels in the fragment: browsers never send it to servers or in Referer headers.
        return base + path + "#token=" + token;
    }

    private static ApiException invalidToken() {
        return new ApiException(AuthErrorCode.INVALID_TOKEN, "The link is invalid, expired or has already been used.");
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }
}
