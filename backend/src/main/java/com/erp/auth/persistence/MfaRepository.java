package com.erp.auth.persistence;

import static com.erp.db.auth.Tables.MFA_TOTP;
import static com.erp.db.auth.Tables.RECOVERY_CODES;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;

/** TOTP secrets (encrypted) and recovery codes (SHA-256). */
@Repository
public class MfaRepository {

    /** Stored TOTP state; {@code confirmedAt == null} means enrollment is pending. */
    public record TotpState(
            byte[] secretEncrypted,
            int keyVersion,
            @Nullable OffsetDateTime confirmedAt,
            @Nullable Long lastUsedStep) {}

    private final DSLContext dsl;

    public MfaRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /** Starts (or restarts) enrollment with a new, unconfirmed secret. */
    public void savePending(UUID userId, byte[] secretEncrypted, int keyVersion, OffsetDateTime now) {
        dsl.insertInto(MFA_TOTP)
                .set(MFA_TOTP.USER_ID, userId)
                .set(MFA_TOTP.SECRET_ENCRYPTED, secretEncrypted)
                .set(MFA_TOTP.SECRET_KEY_VERSION, (short) keyVersion)
                .set(MFA_TOTP.CREATED_AT, now)
                .onConflict(MFA_TOTP.USER_ID)
                .doUpdate()
                .set(MFA_TOTP.SECRET_ENCRYPTED, secretEncrypted)
                .set(MFA_TOTP.SECRET_KEY_VERSION, (short) keyVersion)
                .setNull(MFA_TOTP.CONFIRMED_AT)
                .setNull(MFA_TOTP.LAST_USED_STEP)
                .set(MFA_TOTP.CREATED_AT, now)
                .execute();
    }

    public Optional<TotpState> lockTotp(UUID userId) {
        return dsl.selectFrom(MFA_TOTP)
                .where(MFA_TOTP.USER_ID.eq(userId))
                .forUpdate()
                .fetchOptional()
                .map(r -> new TotpState(
                        r.getSecretEncrypted(), r.getSecretKeyVersion(), r.getConfirmedAt(), r.getLastUsedStep()));
    }

    public void confirm(UUID userId, long usedStep, OffsetDateTime now) {
        dsl.update(MFA_TOTP)
                .set(MFA_TOTP.CONFIRMED_AT, now)
                .set(MFA_TOTP.LAST_USED_STEP, usedStep)
                .where(MFA_TOTP.USER_ID.eq(userId))
                .execute();
    }

    public void recordUsedStep(UUID userId, long usedStep) {
        dsl.update(MFA_TOTP)
                .set(MFA_TOTP.LAST_USED_STEP, usedStep)
                .where(MFA_TOTP.USER_ID.eq(userId))
                .execute();
    }

    public void deleteAll(UUID userId) {
        dsl.deleteFrom(RECOVERY_CODES).where(RECOVERY_CODES.USER_ID.eq(userId)).execute();
        dsl.deleteFrom(MFA_TOTP).where(MFA_TOTP.USER_ID.eq(userId)).execute();
    }

    public void replaceRecoveryCodes(UUID userId, List<byte[]> codeHashes, OffsetDateTime now) {
        dsl.deleteFrom(RECOVERY_CODES).where(RECOVERY_CODES.USER_ID.eq(userId)).execute();
        var insert = dsl.insertInto(
                RECOVERY_CODES, RECOVERY_CODES.USER_ID, RECOVERY_CODES.CODE_HASH, RECOVERY_CODES.CREATED_AT);
        for (byte[] hash : codeHashes) {
            insert = insert.values(userId, hash, now);
        }
        insert.execute();
    }

    /** Uses a recovery code once; false if unknown or already used. */
    public boolean consumeRecoveryCode(UUID userId, byte[] codeHash, OffsetDateTime now) {
        return dsl.update(RECOVERY_CODES)
                        .set(RECOVERY_CODES.USED_AT, now)
                        .where(RECOVERY_CODES.USER_ID.eq(userId))
                        .and(RECOVERY_CODES.CODE_HASH.eq(codeHash))
                        .and(RECOVERY_CODES.USED_AT.isNull())
                        .execute()
                == 1;
    }
}
