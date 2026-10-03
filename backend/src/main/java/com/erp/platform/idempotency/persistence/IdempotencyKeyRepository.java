package com.erp.platform.idempotency.persistence;

import static com.erp.db.platform.Tables.IDEMPOTENCY_KEYS;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSON;
import org.jooq.JSONB;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;

/** Storage of {@code Idempotency-Key} outcomes (API.md §10). */
@Repository
public class IdempotencyKeyRepository {

    /** A stored outcome. */
    public record StoredResponse(
            byte[] requestHash,
            int status,
            @Nullable String headers,
            @Nullable String body) {}

    private final DSLContext dsl;

    public IdempotencyKeyRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /**
     * Claims the key for this request. Blocks while another transaction holds the same (uncommitted)
     * key; returns {@code false} if a committed row exists.
     */
    public boolean claim(UUID userId, String key, byte[] requestHash, OffsetDateTime expiresAt) {
        return dsl.insertInto(IDEMPOTENCY_KEYS)
                        .set(IDEMPOTENCY_KEYS.USER_ID, userId)
                        .set(IDEMPOTENCY_KEYS.IDEM_KEY, key)
                        .set(IDEMPOTENCY_KEYS.REQUEST_HASH, requestHash)
                        .set(IDEMPOTENCY_KEYS.STATUS, "IN_PROGRESS")
                        .set(IDEMPOTENCY_KEYS.EXPIRES_AT, expiresAt)
                        .onConflictDoNothing()
                        .execute()
                == 1;
    }

    public Optional<StoredResponse> find(UUID userId, String key, OffsetDateTime now) {
        return dsl.selectFrom(IDEMPOTENCY_KEYS)
                .where(IDEMPOTENCY_KEYS.USER_ID.eq(userId))
                .and(IDEMPOTENCY_KEYS.IDEM_KEY.eq(key))
                .and(IDEMPOTENCY_KEYS.STATUS.eq("COMPLETED"))
                .and(IDEMPOTENCY_KEYS.EXPIRES_AT.gt(now))
                .fetchOptional()
                .map(r -> new StoredResponse(
                        r.getRequestHash(),
                        r.getResponseStatus(),
                        r.getResponseHeaders() == null
                                ? null
                                : r.getResponseHeaders().data(),
                        r.getResponseBody() == null ? null : r.getResponseBody().data()));
    }

    /** Removes an expired row so the key can be used again. */
    public void deleteExpired(UUID userId, String key, OffsetDateTime now) {
        dsl.deleteFrom(IDEMPOTENCY_KEYS)
                .where(IDEMPOTENCY_KEYS.USER_ID.eq(userId))
                .and(IDEMPOTENCY_KEYS.IDEM_KEY.eq(key))
                .and(IDEMPOTENCY_KEYS.EXPIRES_AT.le(now))
                .execute();
    }

    public void complete(UUID userId, String key, int status, String headers, @Nullable String body) {
        dsl.update(IDEMPOTENCY_KEYS)
                .set(IDEMPOTENCY_KEYS.STATUS, "COMPLETED")
                .set(IDEMPOTENCY_KEYS.RESPONSE_STATUS, (short) status)
                .set(IDEMPOTENCY_KEYS.RESPONSE_HEADERS, JSONB.jsonb(headers))
                .set(IDEMPOTENCY_KEYS.RESPONSE_BODY, body == null ? null : JSON.json(body))
                .where(IDEMPOTENCY_KEYS.USER_ID.eq(userId))
                .and(IDEMPOTENCY_KEYS.IDEM_KEY.eq(key))
                .execute();
    }

    /** Stores a client-error outcome after the business transaction rolled back (no-op if stored meanwhile). */
    public void storeFailure(
            UUID userId, String key, byte[] requestHash, int status, String body, OffsetDateTime expiresAt) {
        dsl.insertInto(IDEMPOTENCY_KEYS)
                .set(IDEMPOTENCY_KEYS.USER_ID, userId)
                .set(IDEMPOTENCY_KEYS.IDEM_KEY, key)
                .set(IDEMPOTENCY_KEYS.REQUEST_HASH, requestHash)
                .set(IDEMPOTENCY_KEYS.STATUS, "COMPLETED")
                .set(IDEMPOTENCY_KEYS.RESPONSE_STATUS, (short) status)
                .set(IDEMPOTENCY_KEYS.RESPONSE_HEADERS, JSONB.jsonb("{}"))
                .set(IDEMPOTENCY_KEYS.RESPONSE_BODY, JSON.json(body))
                .set(IDEMPOTENCY_KEYS.EXPIRES_AT, expiresAt)
                .onConflictDoNothing()
                .execute();
    }

    public int purgeExpired(OffsetDateTime now) {
        return dsl.deleteFrom(IDEMPOTENCY_KEYS)
                .where(IDEMPOTENCY_KEYS.EXPIRES_AT.le(now))
                .execute();
    }
}
