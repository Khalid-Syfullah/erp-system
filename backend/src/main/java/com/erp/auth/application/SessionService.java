package com.erp.auth.application;

import com.erp.auth.domain.SecureTokens;
import com.erp.auth.domain.UserStatus;
import com.erp.auth.persistence.SessionRepository;
import com.erp.auth.persistence.UserRepository;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditPort;
import com.erp.platform.security.ActorType;
import com.erp.platform.security.AuthenticatedActor;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Server-side browser sessions (SECURITY.md §3.3, ADR-028): 256-bit random secrets, stored hashed;
 * idle timeout (30 min), absolute timeout (12 h), at most five concurrent sessions per user, rotation
 * on privilege changes, and immediate invalidation when the user is no longer active.
 */
@Service
public class SessionService {

    /** A freshly issued session: the secret goes into the cookie and is never stored. */
    public record IssuedSession(UUID sessionId, String token, OffsetDateTime absoluteExpiresAt) {}

    static final Duration TOUCH_INTERVAL = Duration.ofMinutes(1);

    private final SessionRepository sessions;
    private final UserRepository users;
    private final AuthProperties properties;
    private final AuditPort audit;
    private final Clock clock;

    public SessionService(
            SessionRepository sessions, UserRepository users, AuthProperties properties, AuditPort audit, Clock clock) {
        this.sessions = sessions;
        this.users = users;
        this.properties = properties;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public IssuedSession create(
            UUID userId,
            boolean mfaVerified,
            boolean mfaEnrollmentRequired,
            @Nullable String ip,
            @Nullable String userAgent) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        OffsetDateTime absoluteExpiry = now.plus(properties.session().absoluteTimeout());
        String token = SecureTokens.newSecret();
        UUID sessionId = sessions.insert(
                SecureTokens.sha256(token),
                userId,
                now,
                absoluteExpiry,
                mfaVerified,
                mfaEnrollmentRequired,
                ip,
                userAgent);
        sessions.trimToNewest(userId, properties.session().maxConcurrent());
        return new IssuedSession(sessionId, token, absoluteExpiry);
    }

    /**
     * Validates a session secret. Expired sessions and sessions of users who are no longer active are
     * deleted. Runs without a transaction (auth tables have no RLS).
     */
    public Optional<AuthenticatedActor> authenticate(String token) {
        if (token == null || token.length() != 43) {
            return Optional.empty();
        }
        Optional<SessionInfo> found = sessions.findByTokenHash(SecureTokens.sha256(token));
        if (found.isEmpty()) {
            return Optional.empty();
        }
        SessionInfo session = found.get();
        OffsetDateTime now = OffsetDateTime.now(clock);
        boolean expired = !now.isBefore(session.absoluteExpiresAt())
                || !now.isBefore(session.lastSeenAt().plus(properties.session().idleTimeout()));
        Optional<AuthUser> user = expired ? Optional.empty() : users.findById(session.userId());
        if (user.isEmpty() || user.get().status() != UserStatus.ACTIVE) {
            sessions.delete(session.id());
            return Optional.empty();
        }
        if (session.lastSeenAt().plus(TOUCH_INTERVAL).isBefore(now)) {
            sessions.touch(session.id(), now);
        }
        return Optional.of(new AuthenticatedActor(
                session.userId(),
                ActorType.USER,
                session.id(),
                user.get().systemAdmin(),
                session.authenticatedAt().toInstant(),
                session.reauthenticatedAt() == null
                        ? null
                        : session.reauthenticatedAt().toInstant(),
                null,
                null,
                session.mfaEnrollmentRequired(),
                null));
    }

    /** Issues a new secret for the session (fixation defence), optionally recording a step-up. */
    @Transactional
    public String rotate(UUID sessionId, boolean reauthenticated) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        String token = SecureTokens.newSecret();
        sessions.rotate(sessionId, SecureTokens.sha256(token), reauthenticated ? now : null, now);
        return token;
    }

    @Transactional(readOnly = true)
    public List<SessionInfo> list(UUID userId) {
        return sessions.listForUser(userId);
    }

    /** Signs one of the user's own sessions out (AUD-1: audited like a logout). */
    @Transactional
    public boolean revoke(UUID userId, UUID sessionId) {
        boolean revoked = sessions.find(userId, sessionId)
                .map(s -> sessions.delete(s.id()) == 1)
                .orElse(false);
        if (revoked) {
            audit.record(AuditEvent.builder("SESSION_REVOKE", "auth")
                    .entity("session", sessionId, null)
                    .detail("userId", userId)
                    .build());
        }
        return revoked;
    }
}
