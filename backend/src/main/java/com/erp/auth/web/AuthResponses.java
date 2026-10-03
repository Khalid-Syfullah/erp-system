package com.erp.auth.web;

import com.erp.auth.application.ApiTokenInfo;
import com.erp.auth.application.AssignmentInfo;
import com.erp.auth.application.AuthUser;
import com.erp.auth.application.PermissionInfo;
import com.erp.auth.application.RoleInfo;
import com.erp.auth.application.SessionInfo;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Response bodies of the Auth endpoints. None of them ever carries a secret or hash. */
final class AuthResponses {

    private AuthResponses() {}

    record UserResponse(
            UUID id,
            String email,
            String displayName,
            String userType,
            String status,
            boolean isSystemAdmin,
            boolean mfaEnabled,
            String locale,
            @Nullable String timezone,
            @Nullable OffsetDateTime lastLoginAt,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            int version) {

        static UserResponse from(AuthUser u) {
            return new UserResponse(
                    u.id(),
                    u.email(),
                    u.displayName(),
                    u.userType().name(),
                    u.status().name(),
                    u.systemAdmin(),
                    u.mfaEnabled(),
                    u.locale(),
                    u.timezone(),
                    u.lastLoginAt(),
                    u.createdAt(),
                    u.updatedAt(),
                    u.version());
        }
    }

    record SessionResponse(
            UUID id,
            boolean current,
            OffsetDateTime createdAt,
            OffsetDateTime lastSeenAt,
            OffsetDateTime absoluteExpiresAt,
            @Nullable String ip,
            @Nullable String userAgent) {

        static SessionResponse from(SessionInfo s, @Nullable UUID currentSessionId) {
            return new SessionResponse(
                    s.id(),
                    s.id().equals(currentSessionId),
                    s.createdAt(),
                    s.lastSeenAt(),
                    s.absoluteExpiresAt(),
                    s.ip(),
                    s.userAgent());
        }
    }

    record ApiTokenResponse(
            UUID id,
            String name,
            String prefix,
            @Nullable UUID companyId,
            @Nullable List<String> allowedPermissions,
            @Nullable Integer rateLimitPerMinute,
            OffsetDateTime expiresAt,
            @Nullable OffsetDateTime lastUsedAt,
            @Nullable OffsetDateTime revokedAt,
            OffsetDateTime createdAt) {

        static ApiTokenResponse from(ApiTokenInfo t) {
            return new ApiTokenResponse(
                    t.id(),
                    t.name(),
                    t.prefix(),
                    t.companyId(),
                    t.allowedPermissions(),
                    t.rateLimitPerMinute(),
                    t.expiresAt(),
                    t.lastUsedAt(),
                    t.revokedAt(),
                    t.createdAt());
        }
    }

    /** Returned once at creation; {@code token} is never shown again. */
    record CreatedApiTokenResponse(ApiTokenResponse metadata, String token) {}

    record RoleResponse(
            UUID id,
            String code,
            String name,
            @Nullable String description,
            boolean isSystem,
            boolean requiresMfa,
            Set<String> permissions,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            int version) {

        static RoleResponse from(RoleInfo r) {
            return new RoleResponse(
                    r.id(),
                    r.code(),
                    r.name(),
                    r.description(),
                    r.system(),
                    r.requiresMfa(),
                    new TreeSet<>(r.permissions()),
                    r.createdAt(),
                    r.updatedAt(),
                    r.version());
        }
    }

    record PermissionResponse(String code, String module, String description, boolean isSensitive) {

        static PermissionResponse from(PermissionInfo p) {
            return new PermissionResponse(p.code(), p.module(), p.description(), p.sensitive());
        }
    }

    record AssignmentResponse(
            UUID id,
            UUID userId,
            String userEmail,
            String userDisplayName,
            UUID roleId,
            String roleCode,
            UUID companyId,
            Set<UUID> branchIds,
            @Nullable LocalDate validFrom,
            @Nullable LocalDate validTo,
            OffsetDateTime createdAt) {

        static AssignmentResponse from(AssignmentInfo a) {
            return new AssignmentResponse(
                    a.id(),
                    a.userId(),
                    a.userEmail(),
                    a.userDisplayName(),
                    a.roleId(),
                    a.roleCode(),
                    a.companyId(),
                    new TreeSet<>(a.branchIds()),
                    a.validFrom(),
                    a.validTo(),
                    a.createdAt());
        }
    }

    record ListResponse<T>(List<T> data) {}
}
