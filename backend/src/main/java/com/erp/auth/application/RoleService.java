package com.erp.auth.application;

import com.erp.auth.AuthPermissions;
import com.erp.auth.persistence.AssignmentRepository;
import com.erp.auth.persistence.RoleRepository;
import com.erp.auth.persistence.SessionRepository;
import com.erp.auth.persistence.UserRepository;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditPort;
import com.erp.platform.context.CurrentContext;
import com.erp.platform.web.ApiException;
import com.erp.platform.web.EntityTags;
import com.erp.platform.web.FieldViolation;
import com.erp.platform.web.MergePatch;
import com.erp.platform.web.PlatformErrorCode;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * Roles (SECURITY.md §4.1–§4.3). System roles are maintained by migration and are read-only here.
 * Custom roles contain catalogue permissions except the global-only ones. A change takes effect for
 * holders immediately on this instance (cache invalidation) and within 60 s elsewhere.
 */
@Service
public class RoleService {

    static final Set<String> PATCHABLE = Set.of("name", "description", "requiresMfa");

    private final RoleRepository roles;
    private final AssignmentRepository assignments;
    private final SessionRepository sessions;
    private final UserRepository users;
    private final PermissionResolver permissionResolver;
    private final AuditPort audit;
    private final Clock clock;

    public RoleService(
            RoleRepository roles,
            AssignmentRepository assignments,
            SessionRepository sessions,
            UserRepository users,
            PermissionResolver permissionResolver,
            AuditPort audit,
            Clock clock) {
        this.roles = roles;
        this.assignments = assignments;
        this.sessions = sessions;
        this.users = users;
        this.permissionResolver = permissionResolver;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<RoleInfo> list() {
        return roles.listRoles();
    }

    @Transactional(readOnly = true)
    public RoleInfo get(UUID roleId) {
        return roles.find(roleId).orElseThrow(ApiException::notFound);
    }

    @Transactional(readOnly = true)
    public List<PermissionInfo> permissions() {
        return roles.activePermissions();
    }

    @Transactional
    public RoleInfo create(
            String code, String name, @Nullable String description, boolean requiresMfa, Set<String> permissions) {
        validatePermissions(permissions, "/permissions");
        UUID actor = CurrentContext.requireActor().userId();
        OffsetDateTime now = OffsetDateTime.now(clock);
        UUID id = roles.insert(code, name, description, requiresMfa, actor, now);
        roles.replacePermissions(id, 0, permissions, actor, now);
        RoleInfo created = get(id);
        audit.record(AuditEvent.builder("ROLE_CHANGE", "auth")
                .entity("role", id, code)
                .transition(null, "CREATED")
                .detail("permissions", new TreeSet<>(permissions).toString())
                .detail("requiresMfa", requiresMfa)
                .build());
        return created;
    }

    @Transactional
    public RoleInfo patch(UUID roleId, @Nullable String ifMatch, JsonNode document) {
        RoleInfo current = mutable(roleId, ifMatch);
        MergePatch patch = MergePatch.of(document, PATCHABLE);
        MergePatch.Member<String> name = patch.text("name", true, 100);
        MergePatch.Member<String> description = patch.text("description", false, 500);
        MergePatch.Member<Boolean> requiresMfa = patch.bool("requiresMfa");
        patch.throwIfInvalid();
        UUID actor = CurrentContext.requireActor().userId();
        if (!roles.update(
                roleId,
                current.version(),
                name.orElse(current.name()),
                description.orElse(current.description()),
                Boolean.TRUE.equals(requiresMfa.orElse(current.requiresMfa())),
                actor,
                OffsetDateTime.now(clock))) {
            throw new ApiException(PlatformErrorCode.VERSION_CONFLICT, "The role was modified concurrently.");
        }
        RoleInfo after = get(roleId);
        audit.record(AuditEvent.builder("ROLE_CHANGE", "auth")
                .entity("role", roleId, after.code())
                .change("name", current.name(), after.name())
                .change("description", current.description(), after.description())
                .change("requiresMfa", current.requiresMfa(), after.requiresMfa())
                .build());
        if (after.requiresMfa() && !current.requiresMfa()) {
            signOutHoldersWithoutMfa(roleId);
        }
        return after;
    }

    @Transactional
    public RoleInfo replacePermissions(UUID roleId, @Nullable String ifMatch, Set<String> permissions) {
        RoleInfo current = mutable(roleId, ifMatch);
        validatePermissions(permissions, "/permissions");
        if (!roles.replacePermissions(
                roleId,
                current.version(),
                permissions,
                CurrentContext.requireActor().userId(),
                OffsetDateTime.now(clock))) {
            throw new ApiException(PlatformErrorCode.VERSION_CONFLICT, "The role was modified concurrently.");
        }
        Set<String> added = new TreeSet<>(permissions);
        added.removeAll(current.permissions());
        Set<String> removed = new TreeSet<>(current.permissions());
        removed.removeAll(permissions);
        audit.record(AuditEvent.builder("ROLE_CHANGE", "auth")
                .entity("role", roleId, current.code())
                .detail("permissionsAdded", added.toString())
                .detail("permissionsRemoved", removed.toString())
                .build());
        if (roles.anySensitive(added)) {
            signOutHoldersWithoutMfa(roleId);
        }
        permissionResolver.invalidateAll();
        return get(roleId);
    }

    @Transactional
    public void delete(UUID roleId, @Nullable String ifMatch) {
        RoleInfo current = mutable(roleId, ifMatch);
        if (roles.isAssigned(roleId)) {
            throw new ApiException(
                    PlatformErrorCode.RESOURCE_IN_USE, "The role is assigned; remove its assignments first.");
        }
        if (roles.deleteCustom(roleId, current.version()) != 1) {
            throw new ApiException(PlatformErrorCode.VERSION_CONFLICT, "The role was modified concurrently.");
        }
        audit.record(AuditEvent.builder("ROLE_CHANGE", "auth")
                .entity("role", roleId, current.code())
                .transition("CREATED", "DELETED")
                .build());
    }

    private RoleInfo mutable(UUID roleId, @Nullable String ifMatch) {
        RoleInfo role = get(roleId);
        EntityTags.requireMatch(ifMatch, role.version());
        if (role.system()) {
            throw new ApiException(
                    AuthErrorCode.SYSTEM_ROLE_IMMUTABLE,
                    "System roles are maintained by the platform; create a custom role.");
        }
        return role;
    }

    private void validatePermissions(Set<String> requested, String pointer) {
        Set<String> catalogue =
                roles.activePermissions().stream().map(PermissionInfo::code).collect(Collectors.toSet());
        List<FieldViolation> violations = new ArrayList<>();
        for (String permission : new TreeSet<>(requested)) {
            if (!catalogue.contains(permission)) {
                violations.add(FieldViolation.atPointer(
                        pointer, "UNKNOWN_PERMISSION", "'" + permission + "' is not a permission"));
            } else if (AuthPermissions.GLOBAL_ONLY.contains(permission)) {
                violations.add(FieldViolation.atPointer(
                        pointer, "GLOBAL_PERMISSION", "'" + permission + "' is reserved to system administrators"));
            }
        }
        if (!violations.isEmpty()) {
            throw ApiException.validationFailed("The role's permissions are invalid.", violations);
        }
    }

    /** New MFA obligation for the role's holders: those without MFA must sign in again and enroll. */
    private void signOutHoldersWithoutMfa(UUID roleId) {
        for (UUID userId : assignments.usersWithRole(roleId)) {
            if (!users.findById(userId).map(AuthUser::mfaEnabled).orElse(false)) {
                sessions.deleteForUser(userId);
            }
            permissionResolver.invalidateUser(userId);
        }
    }
}
