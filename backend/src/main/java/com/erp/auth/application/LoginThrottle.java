package com.erp.auth.application;

import com.erp.auth.domain.SecureTokens;
import com.erp.auth.persistence.LoginProtectionRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Database-backed limits for unauthenticated endpoints (SECURITY.md §9). Counting rows in the
 * attempt and throttle tables makes the limits hold across all application instances.
 */
@Component
public class LoginThrottle {

    static final String PASSWORD_RESET_EMAIL = "RESET_EMAIL";
    static final String PASSWORD_RESET_IP = "RESET_IP";
    static final String TOKEN_REDEMPTION_IP = "REDEEM_IP";
    static final String PASSWORD_CONFIRMATION = "PASSWORD_CONFIRM";
    static final int PASSWORD_CONFIRMATIONS_PER_15_MINUTES = 5;

    private final LoginProtectionRepository repository;
    private final AuthProperties properties;
    private final Clock clock;

    public LoginThrottle(LoginProtectionRepository repository, AuthProperties properties, Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    /** SHA-256 of the normalized email; attempts are tracked without storing addresses. */
    public static byte[] emailHash(String email) {
        return SecureTokens.sha256(
                "email:" + (email == null ? "" : email.strip().toLowerCase(Locale.ROOT)));
    }

    static byte[] subjectHash(String kind, @Nullable String value) {
        return SecureTokens.sha256(kind + ":" + (value == null ? "" : value));
    }

    public boolean loginAllowed(byte[] emailHash, @Nullable String ip) {
        OffsetDateTime since = OffsetDateTime.now(clock).minusMinutes(1);
        return repository.attemptsFromIpSince(ip, since) < properties.login().perIpPerMinute()
                && repository.attemptsForEmailSince(emailHash, since)
                        < properties.login().perAccountPerMinute();
    }

    /** Records the request and reports whether it is within the hourly reset-request limits. */
    public boolean passwordResetAllowed(String email, @Nullable String ip) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        byte[] emailSubject = emailHash(email);
        byte[] ipSubject = subjectHash("ip", ip);
        boolean allowed = repository.throttleEventsSince(PASSWORD_RESET_EMAIL, emailSubject, now.minusHours(1))
                        < properties.tokens().resetRequestsPerEmailPerHour()
                && repository.throttleEventsSince(PASSWORD_RESET_IP, ipSubject, now.minusHours(1))
                        < properties.tokens().resetRequestsPerIpPerHour();
        repository.recordThrottleEvent(PASSWORD_RESET_EMAIL, emailSubject, now);
        repository.recordThrottleEvent(PASSWORD_RESET_IP, ipSubject, now);
        return allowed;
    }

    /**
     * Records an invitation/reset token redemption attempt and reports whether it is allowed. Commits
     * independently: a failed redemption rolls back the caller but must still count.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean redemptionAllowed(@Nullable String ip) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        byte[] subject = subjectHash("ip", ip);
        boolean allowed = repository.throttleEventsSince(TOKEN_REDEMPTION_IP, subject, now.minusHours(1))
                < properties.tokens().redemptionsPerIpPerHour();
        repository.recordThrottleEvent(TOKEN_REDEMPTION_IP, subject, now);
        return allowed;
    }

    /** Wrong-password budget for authenticated confirmations (password change, step-up, MFA removal). */
    public boolean passwordConfirmationAllowed(java.util.UUID userId) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        return repository.throttleEventsSince(
                        PASSWORD_CONFIRMATION,
                        subjectHash("user", userId.toString()),
                        now.minus(Duration.ofMinutes(15)))
                < PASSWORD_CONFIRMATIONS_PER_15_MINUTES;
    }

    /** Commits independently of the (failing) caller so that wrong passwords keep counting. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailedPasswordConfirmation(java.util.UUID userId) {
        repository.recordThrottleEvent(
                PASSWORD_CONFIRMATION, subjectHash("user", userId.toString()), OffsetDateTime.now(clock));
    }
}
