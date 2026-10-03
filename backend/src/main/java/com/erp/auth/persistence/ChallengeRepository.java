package com.erp.auth.persistence;

import static com.erp.db.auth.Tables.LOGIN_CHALLENGES;

import com.erp.platform.jooq.Inets;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;

/** Pending MFA challenges after a correct password (SECURITY.md §3.5). */
@Repository
public class ChallengeRepository {

    /** A challenge row, locked for the verification attempt. */
    public record Challenge(UUID id, UUID userId, int attempts, OffsetDateTime expiresAt) {}

    private final DSLContext dsl;

    public ChallengeRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public void insert(
            byte[] tokenHash, UUID userId, OffsetDateTime now, OffsetDateTime expiresAt, @Nullable String ip) {
        dsl.insertInto(LOGIN_CHALLENGES)
                .set(LOGIN_CHALLENGES.TOKEN_HASH, tokenHash)
                .set(LOGIN_CHALLENGES.USER_ID, userId)
                .set(LOGIN_CHALLENGES.CREATED_AT, now)
                .set(LOGIN_CHALLENGES.EXPIRES_AT, expiresAt)
                .set(LOGIN_CHALLENGES.IP, Inets.of(ip))
                .execute();
    }

    public Optional<Challenge> lock(byte[] tokenHash) {
        return dsl.selectFrom(LOGIN_CHALLENGES)
                .where(LOGIN_CHALLENGES.TOKEN_HASH.eq(tokenHash))
                .forUpdate()
                .fetchOptional()
                .map(r -> new Challenge(r.getId(), r.getUserId(), r.getAttempts(), r.getExpiresAt()));
    }

    public void incrementAttempts(UUID id) {
        dsl.update(LOGIN_CHALLENGES)
                .set(LOGIN_CHALLENGES.ATTEMPTS, LOGIN_CHALLENGES.ATTEMPTS.plus(1))
                .where(LOGIN_CHALLENGES.ID.eq(id))
                .execute();
    }

    public void delete(UUID id) {
        dsl.deleteFrom(LOGIN_CHALLENGES).where(LOGIN_CHALLENGES.ID.eq(id)).execute();
    }
}
