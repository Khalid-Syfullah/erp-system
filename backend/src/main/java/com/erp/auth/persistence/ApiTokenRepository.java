package com.erp.auth.persistence;

import static com.erp.db.auth.Tables.API_TOKENS;

import com.erp.auth.application.ApiTokenInfo;
import com.erp.db.auth.tables.records.ApiTokensRecord;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;

/** API tokens, looked up by the SHA-256 hash of the full token (SECURITY.md §3.6). */
@Repository
public class ApiTokenRepository {

    private final DSLContext dsl;

    public ApiTokenRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public UUID insert(
            UUID userId,
            String name,
            String prefix,
            byte[] tokenHash,
            @Nullable UUID companyId,
            @Nullable List<String> allowedPermissions,
            @Nullable Integer rateLimitPerMinute,
            OffsetDateTime now,
            OffsetDateTime expiresAt,
            UUID actor) {
        return dsl.insertInto(API_TOKENS)
                .set(API_TOKENS.USER_ID, userId)
                .set(API_TOKENS.NAME, name)
                .set(API_TOKENS.TOKEN_PREFIX, prefix)
                .set(API_TOKENS.TOKEN_HASH, tokenHash)
                .set(API_TOKENS.COMPANY_ID, companyId)
                .set(
                        API_TOKENS.ALLOWED_PERMISSIONS,
                        allowedPermissions == null ? null : allowedPermissions.toArray(String[]::new))
                .set(API_TOKENS.RATE_LIMIT_PER_MINUTE, rateLimitPerMinute)
                .set(API_TOKENS.CREATED_AT, now)
                .set(API_TOKENS.EXPIRES_AT, expiresAt)
                .set(API_TOKENS.CREATED_BY, actor)
                .returning(API_TOKENS.ID)
                .fetchOne(API_TOKENS.ID);
    }

    public Optional<ApiTokenInfo> findByHash(byte[] tokenHash) {
        return dsl.selectFrom(API_TOKENS)
                .where(API_TOKENS.TOKEN_HASH.eq(tokenHash))
                .fetchOptional()
                .map(ApiTokenRepository::toInfo);
    }

    public List<ApiTokenInfo> listForUser(UUID userId) {
        return dsl.selectFrom(API_TOKENS)
                .where(API_TOKENS.USER_ID.eq(userId))
                .orderBy(API_TOKENS.CREATED_AT.desc())
                .fetch(ApiTokenRepository::toInfo);
    }

    public void touch(UUID tokenId, OffsetDateTime now) {
        dsl.update(API_TOKENS)
                .set(API_TOKENS.LAST_USED_AT, now)
                .where(API_TOKENS.ID.eq(tokenId))
                .execute();
    }

    /** Revokes one token of the user; false if it does not exist for this user or is already revoked. */
    public boolean revoke(UUID userId, UUID tokenId, OffsetDateTime now) {
        return dsl.update(API_TOKENS)
                        .set(API_TOKENS.REVOKED_AT, now)
                        .where(API_TOKENS.USER_ID.eq(userId))
                        .and(API_TOKENS.ID.eq(tokenId))
                        .and(API_TOKENS.REVOKED_AT.isNull())
                        .execute()
                == 1;
    }

    public int revokeAll(UUID userId, OffsetDateTime now) {
        return dsl.update(API_TOKENS)
                .set(API_TOKENS.REVOKED_AT, now)
                .where(API_TOKENS.USER_ID.eq(userId))
                .and(API_TOKENS.REVOKED_AT.isNull())
                .execute();
    }

    public boolean prefixExists(String prefix) {
        return dsl.fetchExists(API_TOKENS, API_TOKENS.TOKEN_PREFIX.eq(prefix));
    }

    static ApiTokenInfo toInfo(ApiTokensRecord r) {
        return new ApiTokenInfo(
                r.getId(),
                r.getUserId(),
                r.getName(),
                r.getTokenPrefix(),
                r.getCompanyId(),
                r.getAllowedPermissions() == null ? null : Arrays.asList(r.getAllowedPermissions()),
                r.getRateLimitPerMinute(),
                r.getExpiresAt(),
                r.getLastUsedAt(),
                r.getRevokedAt(),
                r.getCreatedAt());
    }
}
