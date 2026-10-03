package com.erp.auth.persistence;

import static com.erp.db.auth.Tables.LOGIN_ATTEMPTS;
import static com.erp.db.auth.Tables.THROTTLE_EVENTS;

import com.erp.platform.jooq.Inets;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;

/**
 * Login attempts and throttle counters. Counting rows in time windows gives limits that hold across
 * all application instances (SECURITY.md §9) without another stateful component.
 */
@Repository
public class LoginProtectionRepository {

    private final DSLContext dsl;

    public LoginProtectionRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public void recordAttempt(
            @Nullable UUID userId,
            byte[] emailHash,
            @Nullable String ip,
            boolean succeeded,
            @Nullable String failureReason,
            OffsetDateTime now) {
        dsl.insertInto(LOGIN_ATTEMPTS)
                .set(LOGIN_ATTEMPTS.USER_ID, userId)
                .set(LOGIN_ATTEMPTS.EMAIL_HASH, emailHash)
                .set(LOGIN_ATTEMPTS.IP, Inets.of(ip))
                .set(LOGIN_ATTEMPTS.SUCCEEDED, succeeded)
                .set(LOGIN_ATTEMPTS.FAILURE_REASON, failureReason)
                .set(LOGIN_ATTEMPTS.ATTEMPTED_AT, now)
                .execute();
    }

    public int attemptsFromIpSince(@Nullable String ip, OffsetDateTime since) {
        var inet = Inets.of(ip);
        return inet == null
                ? 0
                : dsl.fetchCount(LOGIN_ATTEMPTS, LOGIN_ATTEMPTS.IP.eq(inet).and(LOGIN_ATTEMPTS.ATTEMPTED_AT.ge(since)));
    }

    public int attemptsForEmailSince(byte[] emailHash, OffsetDateTime since) {
        return dsl.fetchCount(
                LOGIN_ATTEMPTS, LOGIN_ATTEMPTS.EMAIL_HASH.eq(emailHash).and(LOGIN_ATTEMPTS.ATTEMPTED_AT.ge(since)));
    }

    public int lockoutsSince(UUID userId, OffsetDateTime since) {
        return dsl.fetchCount(
                LOGIN_ATTEMPTS,
                LOGIN_ATTEMPTS
                        .USER_ID
                        .eq(userId)
                        .and(LOGIN_ATTEMPTS.FAILURE_REASON.eq("LOCKOUT"))
                        .and(LOGIN_ATTEMPTS.ATTEMPTED_AT.ge(since)));
    }

    public void recordThrottleEvent(String action, byte[] subjectHash, OffsetDateTime now) {
        dsl.insertInto(THROTTLE_EVENTS)
                .set(THROTTLE_EVENTS.ACTION, action)
                .set(THROTTLE_EVENTS.SUBJECT_HASH, subjectHash)
                .set(THROTTLE_EVENTS.OCCURRED_AT, now)
                .execute();
    }

    public int throttleEventsSince(String action, byte[] subjectHash, OffsetDateTime since) {
        return dsl.fetchCount(
                THROTTLE_EVENTS,
                THROTTLE_EVENTS
                        .ACTION
                        .eq(action)
                        .and(THROTTLE_EVENTS.SUBJECT_HASH.eq(subjectHash))
                        .and(THROTTLE_EVENTS.OCCURRED_AT.ge(since)));
    }

    /** Deletes expired security records (auth.purge_security_records, SECURITY DEFINER). */
    public int purge(int loginAttemptRetentionDays) {
        return dsl.select(org.jooq.impl.DSL.field(
                        "auth.purge_security_records(make_interval(days => {0}))",
                        Integer.class, loginAttemptRetentionDays))
                .fetchOne(0, Integer.class);
    }
}
