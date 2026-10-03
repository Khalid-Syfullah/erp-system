package com.erp.auth.persistence;

import static com.erp.db.auth.Tables.USER_TOKENS;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

/** Single-use invitation and password-reset tokens (hash only). */
@Repository
public class UserTokenRepository {

    /** A token row, locked for redemption. */
    public record UserToken(UUID id, UUID userId, String purpose, OffsetDateTime expiresAt, OffsetDateTime usedAt) {}

    private final DSLContext dsl;

    public UserTokenRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public void insert(UUID userId, String purpose, byte[] tokenHash, OffsetDateTime now, OffsetDateTime expiresAt) {
        dsl.insertInto(USER_TOKENS)
                .set(USER_TOKENS.USER_ID, userId)
                .set(USER_TOKENS.PURPOSE, purpose)
                .set(USER_TOKENS.TOKEN_HASH, tokenHash)
                .set(USER_TOKENS.CREATED_AT, now)
                .set(USER_TOKENS.EXPIRES_AT, expiresAt)
                .execute();
    }

    public Optional<UserToken> lock(byte[] tokenHash) {
        return dsl.selectFrom(USER_TOKENS)
                .where(USER_TOKENS.TOKEN_HASH.eq(tokenHash))
                .forUpdate()
                .fetchOptional()
                .map(r -> new UserToken(r.getId(), r.getUserId(), r.getPurpose(), r.getExpiresAt(), r.getUsedAt()));
    }

    public void markUsed(UUID id, OffsetDateTime now) {
        dsl.update(USER_TOKENS)
                .set(USER_TOKENS.USED_AT, now)
                .where(USER_TOKENS.ID.eq(id))
                .execute();
    }

    /** Invalidates every unused token of the purpose (e.g. after a password change). */
    public int invalidateAll(UUID userId, String purpose, OffsetDateTime now) {
        return dsl.update(USER_TOKENS)
                .set(USER_TOKENS.USED_AT, now)
                .where(USER_TOKENS.USER_ID.eq(userId))
                .and(USER_TOKENS.PURPOSE.eq(purpose))
                .and(USER_TOKENS.USED_AT.isNull())
                .execute();
    }
}
