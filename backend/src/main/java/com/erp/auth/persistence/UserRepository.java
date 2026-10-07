package com.erp.auth.persistence;

import static com.erp.db.auth.Tables.USERS;

import com.erp.auth.application.AuthUser;
import com.erp.auth.application.UserListings;
import com.erp.auth.domain.UserStatus;
import com.erp.auth.domain.UserType;
import com.erp.db.auth.tables.records.UsersRecord;
import com.erp.platform.jooq.KeysetPaginator;
import com.erp.platform.jooq.ListBinding;
import com.erp.platform.web.paging.ListQuery;
import com.erp.platform.web.paging.PageResponse;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;

/** Users. Emails are always stored and queried lower-case (citext uniqueness, see migration). */
@Repository
public class UserRepository {

    /** Advisory lock key for changes to the set of active system administrators (DATABASE.md §9). */
    public static final long SYSTEM_ADMIN_SET_LOCK = 0x4552505F41444D4EL; // "ERP_ADMN"

    private static final ListBinding BINDING = ListBinding.builder(UserListings.USERS)
            .field("email", USERS.EMAIL)
            .field("displayName", USERS.DISPLAY_NAME)
            .field("createdAt", USERS.CREATED_AT)
            .field("status", USERS.STATUS)
            .field("userType", USERS.USER_TYPE)
            .field("isSystemAdmin", USERS.IS_SYSTEM_ADMIN)
            .tiebreaker(USERS.ID)
            .search(List.of(USERS.EMAIL, USERS.DISPLAY_NAME))
            .build();

    private final DSLContext dsl;
    private final KeysetPaginator paginator;

    public UserRepository(DSLContext dsl, KeysetPaginator paginator) {
        this.dsl = dsl;
        this.paginator = paginator;
    }

    public UUID insert(
            String email,
            String displayName,
            UserType type,
            UserStatus status,
            @Nullable String passwordHash,
            boolean systemAdmin,
            @Nullable UUID actor,
            OffsetDateTime now) {
        return dsl.insertInto(USERS)
                .set(USERS.EMAIL, email)
                .set(USERS.DISPLAY_NAME, displayName)
                .set(USERS.USER_TYPE, type.name())
                .set(USERS.STATUS, status.name())
                .set(USERS.PASSWORD_HASH, passwordHash)
                .set(USERS.PASSWORD_CHANGED_AT, passwordHash == null ? null : now)
                .set(USERS.IS_SYSTEM_ADMIN, systemAdmin)
                .set(USERS.CREATED_AT, now)
                .set(USERS.UPDATED_AT, now)
                .set(USERS.CREATED_BY, actor)
                .set(USERS.UPDATED_BY, actor)
                .returning(USERS.ID)
                .fetchOne(USERS.ID);
    }

    public Optional<AuthUser> findById(UUID id) {
        return dsl.selectFrom(USERS).where(USERS.ID.eq(id)).fetchOptional().map(UserRepository::toUser);
    }

    /** Row-locked read, for state changes that must not race (login failures, status changes). */
    public Optional<AuthUser> lock(UUID id) {
        return dsl.selectFrom(USERS)
                .where(USERS.ID.eq(id))
                .forUpdate()
                .fetchOptional()
                .map(UserRepository::toUser);
    }

    public Optional<AuthUser> findByEmail(String lowerCaseEmail) {
        return dsl.selectFrom(USERS)
                .where(USERS.EMAIL.eq(lowerCaseEmail))
                .fetchOptional()
                .map(UserRepository::toUser);
    }

    public PageResponse<AuthUser> list(ListQuery query, @Nullable UserType type) {
        Condition scope = type == null ? org.jooq.impl.DSL.noCondition() : USERS.USER_TYPE.eq(type.name());
        return paginator.fetch(dsl, USERS, scope, query, BINDING, UserRepository::toUser);
    }

    public int countActiveSystemAdmins() {
        return dsl.fetchCount(USERS, USERS.IS_SYSTEM_ADMIN.isTrue().and(USERS.STATUS.eq(UserStatus.ACTIVE.name())));
    }

    /**
     * Counts active system administrators while holding a transaction-scoped advisory lock on the set
     * (DATABASE.md §9). Without it, two administrators disabling or demoting each other concurrently
     * would both see two and leave none. Row locks on all administrators would deadlock against the
     * target row each request has already locked; the advisory lock is the only cross-request wait.
     */
    public int countActiveSystemAdminsExclusively() {
        dsl.execute("SELECT pg_advisory_xact_lock(?)", SYSTEM_ADMIN_SET_LOCK);
        return countActiveSystemAdmins();
    }

    public boolean updateProfile(
            UUID id,
            int expectedVersion,
            String displayName,
            @Nullable String locale,
            @Nullable String timezone,
            boolean systemAdmin,
            UUID actor,
            OffsetDateTime now) {
        return dsl.update(USERS)
                        .set(USERS.DISPLAY_NAME, displayName)
                        .set(USERS.LOCALE, locale)
                        .set(USERS.TIMEZONE, timezone)
                        .set(USERS.IS_SYSTEM_ADMIN, systemAdmin)
                        .set(USERS.UPDATED_AT, now)
                        .set(USERS.UPDATED_BY, actor)
                        .set(USERS.VERSION, USERS.VERSION.plus(1))
                        .where(USERS.ID.eq(id))
                        .and(USERS.VERSION.eq(expectedVersion))
                        .execute()
                == 1;
    }

    public void recordFailure(UUID id, int failedCount, @Nullable OffsetDateTime lockedUntil, OffsetDateTime now) {
        dsl.update(USERS)
                .set(USERS.FAILED_LOGIN_COUNT, failedCount)
                .set(USERS.LOCKED_UNTIL, lockedUntil)
                .set(USERS.UPDATED_AT, now)
                .where(USERS.ID.eq(id))
                .execute();
    }

    public void recordSuccessfulLogin(UUID id, OffsetDateTime now) {
        dsl.update(USERS)
                .set(USERS.FAILED_LOGIN_COUNT, 0)
                .setNull(USERS.LOCKED_UNTIL)
                .set(USERS.LAST_LOGIN_AT, now)
                .where(USERS.ID.eq(id))
                .execute();
    }

    public void setStatus(UUID id, UserStatus status, @Nullable UUID actor, OffsetDateTime now) {
        dsl.update(USERS)
                .set(USERS.STATUS, status.name())
                .set(USERS.FAILED_LOGIN_COUNT, 0)
                .setNull(USERS.LOCKED_UNTIL)
                .set(USERS.UPDATED_AT, now)
                .set(USERS.UPDATED_BY, actor)
                .set(USERS.VERSION, USERS.VERSION.plus(1))
                .where(USERS.ID.eq(id))
                .execute();
    }

    /** Sets a new password hash; an invited user becomes active. */
    public void setPassword(UUID id, String passwordHash, @Nullable UUID actor, OffsetDateTime now) {
        dsl.update(USERS)
                .set(USERS.PASSWORD_HASH, passwordHash)
                .set(USERS.PASSWORD_CHANGED_AT, now)
                .set(
                        USERS.STATUS,
                        org.jooq
                                .impl
                                .DSL
                                .when(USERS.STATUS.eq(UserStatus.INVITED.name()), UserStatus.ACTIVE.name())
                                .otherwise(USERS.STATUS))
                .set(USERS.FAILED_LOGIN_COUNT, 0)
                .setNull(USERS.LOCKED_UNTIL)
                .set(USERS.UPDATED_AT, now)
                .set(USERS.UPDATED_BY, actor)
                .set(USERS.VERSION, USERS.VERSION.plus(1))
                .where(USERS.ID.eq(id))
                .execute();
    }

    /** Transparent re-hash after an Argon2 parameter change; not a password change. */
    public void rehash(UUID id, String passwordHash) {
        dsl.update(USERS)
                .set(USERS.PASSWORD_HASH, passwordHash)
                .where(USERS.ID.eq(id))
                .execute();
    }

    public void setMfaEnabled(UUID id, boolean enabled, @Nullable UUID actor, OffsetDateTime now) {
        dsl.update(USERS)
                .set(USERS.MFA_ENABLED, enabled)
                .set(USERS.UPDATED_AT, now)
                .set(USERS.UPDATED_BY, actor)
                .set(USERS.VERSION, USERS.VERSION.plus(1))
                .where(USERS.ID.eq(id))
                .execute();
    }

    static AuthUser toUser(UsersRecord r) {
        return new AuthUser(
                r.getId(),
                r.getEmail(),
                r.getDisplayName(),
                UserType.valueOf(r.getUserType()),
                UserStatus.valueOf(r.getStatus()),
                r.getPasswordHash(),
                r.getMfaEnabled(),
                r.getFailedLoginCount(),
                r.getLockedUntil(),
                r.getLastLoginAt(),
                r.getIsSystemAdmin(),
                r.getLocale(),
                r.getTimezone(),
                r.getCreatedAt(),
                r.getUpdatedAt(),
                r.getVersion());
    }
}
