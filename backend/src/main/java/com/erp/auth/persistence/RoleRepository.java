package com.erp.auth.persistence;

import static com.erp.db.auth.Tables.PERMISSIONS;
import static com.erp.db.auth.Tables.ROLES;
import static com.erp.db.auth.Tables.ROLE_ASSIGNMENTS;
import static com.erp.db.auth.Tables.ROLE_PERMISSIONS;

import com.erp.auth.application.PermissionInfo;
import com.erp.auth.application.RoleInfo;
import com.erp.db.auth.tables.records.RolesRecord;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;

/** Roles, their permissions and the permission catalogue. */
@Repository
public class RoleRepository {

    private final DSLContext dsl;

    public RoleRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public List<RoleInfo> listRoles() {
        Map<UUID, Set<String>> permissions = new HashMap<>();
        dsl.selectFrom(ROLE_PERMISSIONS)
                .fetch()
                .forEach(r -> permissions
                        .computeIfAbsent(r.getRoleId(), k -> new HashSet<>())
                        .add(r.getPermissionCode()));
        return dsl.selectFrom(ROLES)
                .orderBy(ROLES.IS_SYSTEM.desc(), ROLES.CODE)
                .fetch(r -> toInfo(r, permissions.getOrDefault(r.getId(), Set.of())));
    }

    public Optional<RoleInfo> find(UUID roleId) {
        return dsl.selectFrom(ROLES)
                .where(ROLES.ID.eq(roleId))
                .fetchOptional()
                .map(r -> toInfo(r, permissionsOf(roleId)));
    }

    public Optional<RoleInfo> findByCode(String code) {
        return dsl.selectFrom(ROLES)
                .where(ROLES.CODE.eq(code))
                .fetchOptional()
                .map(r -> toInfo(r, permissionsOf(r.getId())));
    }

    public Set<String> permissionsOf(UUID roleId) {
        return new HashSet<>(dsl.select(ROLE_PERMISSIONS.PERMISSION_CODE)
                .from(ROLE_PERMISSIONS)
                .where(ROLE_PERMISSIONS.ROLE_ID.eq(roleId))
                .fetch(ROLE_PERMISSIONS.PERMISSION_CODE));
    }

    public UUID insert(
            String code,
            String name,
            @Nullable String description,
            boolean requiresMfa,
            UUID actor,
            OffsetDateTime now) {
        return dsl.insertInto(ROLES)
                .set(ROLES.CODE, code)
                .set(ROLES.NAME, name)
                .set(ROLES.DESCRIPTION, description)
                .set(ROLES.REQUIRES_MFA, requiresMfa)
                .set(ROLES.IS_SYSTEM, false)
                .set(ROLES.CREATED_AT, now)
                .set(ROLES.UPDATED_AT, now)
                .set(ROLES.CREATED_BY, actor)
                .set(ROLES.UPDATED_BY, actor)
                .returning(ROLES.ID)
                .fetchOne(ROLES.ID);
    }

    public boolean update(
            UUID roleId,
            int expectedVersion,
            String name,
            @Nullable String description,
            boolean requiresMfa,
            UUID actor,
            OffsetDateTime now) {
        return dsl.update(ROLES)
                        .set(ROLES.NAME, name)
                        .set(ROLES.DESCRIPTION, description)
                        .set(ROLES.REQUIRES_MFA, requiresMfa)
                        .set(ROLES.UPDATED_AT, now)
                        .set(ROLES.UPDATED_BY, actor)
                        .set(ROLES.VERSION, ROLES.VERSION.plus(1))
                        .where(ROLES.ID.eq(roleId))
                        .and(ROLES.VERSION.eq(expectedVersion))
                        .and(ROLES.IS_SYSTEM.isFalse())
                        .execute()
                == 1;
    }

    /** Replaces the permission set of a custom role and bumps its version. */
    public boolean replacePermissions(
            UUID roleId, int expectedVersion, Collection<String> permissions, UUID actor, OffsetDateTime now) {
        int bumped = dsl.update(ROLES)
                .set(ROLES.VERSION, ROLES.VERSION.plus(1))
                .set(ROLES.UPDATED_AT, now)
                .set(ROLES.UPDATED_BY, actor)
                .where(ROLES.ID.eq(roleId))
                .and(ROLES.VERSION.eq(expectedVersion))
                .and(ROLES.IS_SYSTEM.isFalse())
                .execute();
        if (bumped != 1) {
            return false;
        }
        dsl.deleteFrom(ROLE_PERMISSIONS)
                .where(ROLE_PERMISSIONS.ROLE_ID.eq(roleId))
                .execute();
        if (!permissions.isEmpty()) {
            var insert = dsl.insertInto(ROLE_PERMISSIONS, ROLE_PERMISSIONS.ROLE_ID, ROLE_PERMISSIONS.PERMISSION_CODE);
            for (String permission : permissions) {
                insert = insert.values(roleId, permission);
            }
            insert.execute();
        }
        return true;
    }

    /** Whether any of the codes is a sensitive permission (SECURITY.md §3.5: holders must use MFA). */
    public boolean anySensitive(Collection<String> codes) {
        return !codes.isEmpty()
                && dsl.fetchExists(PERMISSIONS, PERMISSIONS.CODE.in(codes).and(PERMISSIONS.IS_SENSITIVE.isTrue()));
    }

    /** Whether holders of the role must use MFA: the role is flagged or grants a sensitive permission. */
    public boolean requiresMfa(RoleInfo role) {
        return role.requiresMfa() || anySensitive(role.permissions());
    }

    public boolean isAssigned(UUID roleId) {
        return dsl.fetchExists(ROLE_ASSIGNMENTS, ROLE_ASSIGNMENTS.ROLE_ID.eq(roleId));
    }

    public int deleteCustom(UUID roleId, int expectedVersion) {
        return dsl.deleteFrom(ROLES)
                .where(ROLES.ID.eq(roleId))
                .and(ROLES.VERSION.eq(expectedVersion))
                .and(ROLES.IS_SYSTEM.isFalse())
                .execute();
    }

    public List<PermissionInfo> activePermissions() {
        return dsl.selectFrom(PERMISSIONS)
                .where(PERMISSIONS.DEPRECATED_AT.isNull())
                .orderBy(PERMISSIONS.CODE)
                .fetch(r -> new PermissionInfo(r.getCode(), r.getModule(), r.getDescription(), r.getIsSensitive()));
    }

    static RoleInfo toInfo(RolesRecord r, Set<String> permissions) {
        return new RoleInfo(
                r.getId(),
                r.getCode(),
                r.getName(),
                r.getDescription(),
                r.getIsSystem(),
                r.getRequiresMfa(),
                Set.copyOf(permissions),
                r.getCreatedAt(),
                r.getUpdatedAt(),
                r.getVersion());
    }
}
