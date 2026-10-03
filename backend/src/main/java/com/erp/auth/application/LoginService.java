package com.erp.auth.application;

import com.erp.auth.domain.SecureTokens;
import com.erp.auth.domain.UserStatus;
import com.erp.auth.domain.UserType;
import com.erp.auth.persistence.ChallengeRepository;
import com.erp.auth.persistence.LoginProtectionRepository;
import com.erp.auth.persistence.SessionRepository;
import com.erp.auth.persistence.UserRepository;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditPort;
import com.erp.platform.tx.AfterCommit;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Password login and the MFA second step (SECURITY.md §3.4–§3.5).
 *
 * <p>Failures are returned, not thrown, so that the transaction commits the attempt record, the
 * failure counter, a lockout and the audit entry; the web layer then answers 401. Unknown accounts,
 * wrong passwords, locked, disabled and invited accounts all produce the same generic answer, and an
 * Argon2 verification is spent in every case so that timing does not reveal which one applied.
 */
@Service
public class LoginService {

    /** Result of a login step. */
    public sealed interface Outcome {}

    /** Session established. */
    public record Authenticated(SessionService.IssuedSession session, AuthUser user, boolean mfaEnrollmentRequired)
            implements Outcome {}

    /** Password correct; a TOTP or recovery code is required. The token goes into a short-lived cookie. */
    public record MfaChallenge(String challengeToken, OffsetDateTime expiresAt) implements Outcome {}

    /** Rejected; {@code reason} is one of the {@link AuthErrorCode}s or {@code RATE_LIMITED}. */
    public record Rejected(String reason) implements Outcome {}

    static final String RATE_LIMITED = "RATE_LIMITED";

    private final UserRepository users;
    private final SessionService sessions;
    private final SessionRepository sessionRepository;
    private final ChallengeRepository challenges;
    private final LoginProtectionRepository attempts;
    private final LoginThrottle throttle;
    private final Passwords passwords;
    private final MfaService mfa;
    private final AuditPort audit;
    private final AccountNotifier notifier;
    private final AuthProperties properties;
    private final Clock clock;

    public LoginService(
            UserRepository users,
            SessionService sessions,
            SessionRepository sessionRepository,
            ChallengeRepository challenges,
            LoginProtectionRepository attempts,
            LoginThrottle throttle,
            Passwords passwords,
            MfaService mfa,
            AuditPort audit,
            AccountNotifier notifier,
            AuthProperties properties,
            Clock clock) {
        this.users = users;
        this.sessions = sessions;
        this.sessionRepository = sessionRepository;
        this.challenges = challenges;
        this.attempts = attempts;
        this.throttle = throttle;
        this.passwords = passwords;
        this.mfa = mfa;
        this.audit = audit;
        this.notifier = notifier;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public Outcome login(String email, String password, @Nullable String ip, @Nullable String userAgent) {
        String normalizedEmail = email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
        byte[] emailHash = LoginThrottle.emailHash(normalizedEmail);
        if (!throttle.loginAllowed(emailHash, ip)) {
            return new Rejected(RATE_LIMITED);
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        Optional<AuthUser> found = users.findByEmail(normalizedEmail).filter(u -> u.userType() == UserType.HUMAN);
        if (found.isEmpty()) {
            passwords.equalizeTiming(password);
            attempts.recordAttempt(null, emailHash, ip, false, "UNKNOWN_USER", now);
            return new Rejected(AuthErrorCode.INVALID_CREDENTIALS.name());
        }
        AuthUser user = users.lock(found.get().id()).orElseThrow();
        if (user.lockedUntil() != null && user.lockedUntil().isAfter(now)) {
            passwords.equalizeTiming(password);
            return fail(user, emailHash, ip, "ACCOUNT_LOCKED", now);
        }
        if (user.status() != UserStatus.ACTIVE) {
            passwords.equalizeTiming(password);
            return fail(user, emailHash, ip, "ACCOUNT_NOT_ACTIVE", now);
        }
        if (!passwords.matches(password, user.passwordHash())) {
            return wrongPassword(user, emailHash, ip, now);
        }
        if (passwords.needsRehash(user.passwordHash())) {
            users.rehash(user.id(), passwords.hash(password));
        }
        users.recordSuccessfulLogin(user.id(), now);
        attempts.recordAttempt(user.id(), emailHash, ip, true, null, now);
        if (user.mfaEnabled()) {
            String token = SecureTokens.newSecret();
            OffsetDateTime expiresAt = now.plus(properties.mfa().challengeTtl());
            challenges.insert(SecureTokens.sha256(token), user.id(), now, expiresAt, ip);
            return new MfaChallenge(token, expiresAt);
        }
        boolean enrollmentRequired = mfa.isRequired(user);
        return establish(user, false, enrollmentRequired, ip, userAgent, "PASSWORD");
    }

    /** Second step: TOTP code or recovery code against the challenge from {@link #login}. */
    @Transactional
    public Outcome verifySecondFactor(
            String challengeToken,
            @Nullable String code,
            @Nullable String recoveryCode,
            @Nullable String ip,
            @Nullable String userAgent) {
        if (challengeToken == null) {
            return new Rejected(AuthErrorCode.INVALID_CREDENTIALS.name());
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        Optional<ChallengeRepository.Challenge> found = challenges.lock(SecureTokens.sha256(challengeToken));
        if (found.isEmpty()) {
            return new Rejected(AuthErrorCode.INVALID_CREDENTIALS.name());
        }
        ChallengeRepository.Challenge challenge = found.get();
        if (!challenge.expiresAt().isAfter(now) || challenge.attempts() >= 5) {
            challenges.delete(challenge.id());
            return new Rejected(AuthErrorCode.INVALID_CREDENTIALS.name());
        }
        AuthUser user = users.lock(challenge.userId()).orElseThrow();
        byte[] emailHash = LoginThrottle.emailHash(user.email());
        if (user.status() != UserStatus.ACTIVE) {
            challenges.delete(challenge.id());
            return fail(user, emailHash, ip, "ACCOUNT_NOT_ACTIVE", now);
        }
        boolean verified = code != null
                ? mfa.verifyCode(user.id(), code)
                : recoveryCode != null && mfa.useRecoveryCode(user.id(), recoveryCode);
        if (!verified) {
            if (challenge.attempts() + 1 >= 5) {
                challenges.delete(challenge.id());
            } else {
                challenges.incrementAttempts(challenge.id());
            }
            attempts.recordAttempt(user.id(), emailHash, ip, false, "INVALID_MFA_CODE", now);
            audit.record(AuditEvent.builder("LOGIN_FAILED", "auth")
                    .entity("user", user.id(), user.email())
                    .detail("reason", "INVALID_MFA_CODE")
                    .build());
            return new Rejected(AuthErrorCode.INVALID_MFA_CODE.name());
        }
        challenges.delete(challenge.id());
        return establish(user, true, false, ip, userAgent, code != null ? "TOTP" : "RECOVERY_CODE");
    }

    @Transactional
    public void logout(UUID userId, UUID sessionId) {
        sessionRepository.delete(sessionId);
        audit.record(AuditEvent.builder("LOGOUT", "auth")
                .entity("session", sessionId, null)
                .build());
    }

    private Authenticated establish(
            AuthUser user,
            boolean mfaVerified,
            boolean enrollmentRequired,
            @Nullable String ip,
            @Nullable String userAgent,
            String method) {
        SessionService.IssuedSession session =
                sessions.create(user.id(), mfaVerified, enrollmentRequired, ip, userAgent);
        audit.record(AuditEvent.builder("LOGIN", "auth")
                .entity("user", user.id(), user.email())
                .actingUser(user.id())
                .detail("method", method)
                .detail("sessionId", session.sessionId().toString())
                .build());
        return new Authenticated(session, user, enrollmentRequired);
    }

    private Outcome wrongPassword(AuthUser user, byte[] emailHash, @Nullable String ip, OffsetDateTime now) {
        int failures = user.failedLoginCount() + 1;
        if (failures < properties.login().maxConsecutiveFailures()) {
            users.recordFailure(user.id(), failures, null, now);
            return fail(user, emailHash, ip, "INVALID_CREDENTIALS", now);
        }
        // Temporary lock; repeated locks within 24 hours lock the account until an admin unlocks it.
        users.recordFailure(user.id(), 0, now.plus(properties.login().lockoutDuration()), now);
        attempts.recordAttempt(user.id(), emailHash, ip, false, "LOCKOUT", now);
        boolean permanent = attempts.lockoutsSince(user.id(), now.minusHours(24))
                >= properties.login().lockoutsBeforeAdminUnlock();
        if (permanent) {
            users.setStatus(user.id(), UserStatus.LOCKED, null, now);
            sessionRepository.deleteForUser(user.id());
        }
        audit.record(AuditEvent.builder("LOCKOUT", "auth")
                .entity("user", user.id(), user.email())
                .transition(UserStatus.ACTIVE.name(), permanent ? UserStatus.LOCKED.name() : "TEMPORARILY_LOCKED")
                .build());
        AfterCommit.run(() -> notifier.sendAccountLocked(user.email(), user.displayName(), permanent));
        return fail(user, emailHash, ip, "INVALID_CREDENTIALS", now);
    }

    private Rejected fail(AuthUser user, byte[] emailHash, @Nullable String ip, String reason, OffsetDateTime now) {
        attempts.recordAttempt(user.id(), emailHash, ip, false, reason, now);
        audit.record(AuditEvent.builder("LOGIN_FAILED", "auth")
                .entity("user", user.id(), user.email())
                .detail("reason", reason)
                .build());
        return new Rejected(AuthErrorCode.INVALID_CREDENTIALS.name());
    }
}
