package com.erp.auth.application;

import com.erp.auth.domain.UserStatus;
import com.erp.auth.domain.UserType;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** A user account as loaded from {@code auth.users}. Never serialize it: it holds the password hash. */
public record AuthUser(
        UUID id,
        String email,
        String displayName,
        UserType userType,
        UserStatus status,
        @Nullable String passwordHash,
        boolean mfaEnabled,
        int failedLoginCount,
        @Nullable OffsetDateTime lockedUntil,
        @Nullable OffsetDateTime lastLoginAt,
        boolean systemAdmin,
        @Nullable String locale,
        @Nullable String timezone,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        int version) {

    @Override
    public String toString() {
        return "AuthUser[id=" + id + ", status=" + status + ", type=" + userType + "]";
    }
}
