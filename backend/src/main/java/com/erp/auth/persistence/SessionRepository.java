package com.erp.auth.persistence;

import static com.erp.db.auth.Tables.SESSIONS;

import com.erp.auth.application.SessionInfo;
import com.erp.db.auth.tables.records.SessionsRecord;
import com.erp.platform.jooq.Inets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;

/** Server-side browser sessions, looked up by the SHA-256 hash of the cookie secret (ADR-028). */
@Repository
public class SessionRepository {

    private final DSLContext dsl;

    public SessionRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public UUID insert(
            byte[] tokenHash,
            UUID userId,
            OffsetDateTime now,
            OffsetDateTime absoluteExpiresAt,
            boolean mfaVerified,
            boolean mfaEnrollmentRequired,
            @Nullable String ip,
            @Nullable String userAgent) {
        return dsl.insertInto(SESSIONS)
                .set(SESSIONS.TOKEN_HASH, tokenHash)
                .set(SESSIONS.USER_ID, userId)
                .set(SESSIONS.CREATED_AT, now)
                .set(SESSIONS.LAST_SEEN_AT, now)
                .set(SESSIONS.AUTHENTICATED_AT, now)
                .set(SESSIONS.ABSOLUTE_EXPIRES_AT, absoluteExpiresAt)
                .set(SESSIONS.MFA_VERIFIED, mfaVerified)
                .set(SESSIONS.MFA_ENROLLMENT_REQUIRED, mfaEnrollmentRequired)
                .set(SESSIONS.IP, Inets.of(ip))
                .set(SESSIONS.USER_AGENT, userAgent)
                .returning(SESSIONS.ID)
                .fetchOne(SESSIONS.ID);
    }

    public Optional<SessionInfo> findByTokenHash(byte[] tokenHash) {
        return dsl.selectFrom(SESSIONS)
                .where(SESSIONS.TOKEN_HASH.eq(tokenHash))
                .fetchOptional()
                .map(SessionRepository::toInfo);
    }

    public Optional<SessionInfo> find(UUID userId, UUID sessionId) {
        return dsl.selectFrom(SESSIONS)
                .where(SESSIONS.USER_ID.eq(userId))
                .and(SESSIONS.ID.eq(sessionId))
                .fetchOptional()
                .map(SessionRepository::toInfo);
    }

    public List<SessionInfo> listForUser(UUID userId) {
        return dsl.selectFrom(SESSIONS)
                .where(SESSIONS.USER_ID.eq(userId))
                .orderBy(SESSIONS.CREATED_AT.desc())
                .fetch(SessionRepository::toInfo);
    }

    /** Updates the idle-timeout clock (called at most once a minute per session). */
    public void touch(UUID sessionId, OffsetDateTime now) {
        dsl.update(SESSIONS)
                .set(SESSIONS.LAST_SEEN_AT, now)
                .where(SESSIONS.ID.eq(sessionId))
                .execute();
    }

    /** Replaces the secret (rotation) and optionally records a step-up re-authentication. */
    public void rotate(
            UUID sessionId, byte[] newTokenHash, @Nullable OffsetDateTime reauthenticatedAt, OffsetDateTime now) {
        var update = dsl.update(SESSIONS).set(SESSIONS.TOKEN_HASH, newTokenHash).set(SESSIONS.LAST_SEEN_AT, now);
        if (reauthenticatedAt != null) {
            update = update.set(SESSIONS.REAUTHENTICATED_AT, reauthenticatedAt);
        }
        update.where(SESSIONS.ID.eq(sessionId)).execute();
    }

    public void clearMfaEnrollmentRequirement(UUID userId) {
        dsl.update(SESSIONS)
                .set(SESSIONS.MFA_ENROLLMENT_REQUIRED, false)
                .set(SESSIONS.MFA_VERIFIED, true)
                .where(SESSIONS.USER_ID.eq(userId))
                .execute();
    }

    public int delete(UUID sessionId) {
        return dsl.deleteFrom(SESSIONS).where(SESSIONS.ID.eq(sessionId)).execute();
    }

    public int deleteForUser(UUID userId) {
        return dsl.deleteFrom(SESSIONS).where(SESSIONS.USER_ID.eq(userId)).execute();
    }

    public int deleteForUserExcept(UUID userId, UUID keepSessionId) {
        return dsl.deleteFrom(SESSIONS)
                .where(SESSIONS.USER_ID.eq(userId))
                .and(SESSIONS.ID.ne(keepSessionId))
                .execute();
    }

    /** Keeps the newest {@code keep} sessions of the user and deletes the rest (concurrency cap). */
    public int trimToNewest(UUID userId, int keep) {
        List<UUID> excess = dsl.select(SESSIONS.ID)
                .from(SESSIONS)
                .where(SESSIONS.USER_ID.eq(userId))
                .orderBy(SESSIONS.CREATED_AT.desc(), SESSIONS.ID.desc())
                .offset(keep)
                .fetch(SESSIONS.ID);
        return excess.isEmpty()
                ? 0
                : dsl.deleteFrom(SESSIONS).where(SESSIONS.ID.in(excess)).execute();
    }

    static SessionInfo toInfo(SessionsRecord r) {
        return new SessionInfo(
                r.getId(),
                r.getUserId(),
                r.getCreatedAt(),
                r.getLastSeenAt(),
                r.getAuthenticatedAt(),
                r.getReauthenticatedAt(),
                r.getAbsoluteExpiresAt(),
                r.getMfaVerified(),
                r.getMfaEnrollmentRequired(),
                r.getIp() == null ? null : r.getIp().address().getHostAddress(),
                r.getUserAgent());
    }
}
