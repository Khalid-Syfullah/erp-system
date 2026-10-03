package com.erp.platform.security;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The authenticated principal of a request, as established by a {@link RequestAuthenticator}
 * (SECURITY.md §3). Immutable and free of secrets.
 *
 * @param userId the user (human or service account) acting
 * @param type session user, API token or system
 * @param credentialId session ID or API token ID (never the secret)
 * @param systemAdmin whether the user holds the deployment-level system administrator flag
 * @param authenticatedAt when the credential was verified (password + second factor for sessions)
 * @param reauthenticatedAt last step-up re-authentication of the session, if any
 * @param companyRestriction API tokens only: the single company the token may act in
 * @param allowedPermissions API tokens only: permission down-scoping (null = the user's permissions)
 * @param mfaEnrollmentRequired the session may only enroll MFA until the user has a second factor
 * @param rateLimitPerMinute API tokens only: a token-specific request budget (null = default)
 */
public record AuthenticatedActor(
        UUID userId,
        ActorType type,
        UUID credentialId,
        boolean systemAdmin,
        Instant authenticatedAt,
        @Nullable Instant reauthenticatedAt,
        @Nullable UUID companyRestriction,
        @Nullable Set<String> allowedPermissions,
        boolean mfaEnrollmentRequired,
        @Nullable Integer rateLimitPerMinute) {

    public AuthenticatedActor {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(credentialId, "credentialId");
        Objects.requireNonNull(authenticatedAt, "authenticatedAt");
        allowedPermissions = allowedPermissions == null ? null : Set.copyOf(allowedPermissions);
    }

    /** The most recent proof of the user's credentials (login or step-up). */
    public Instant lastCredentialCheck() {
        return reauthenticatedAt != null && reauthenticatedAt.isAfter(authenticatedAt)
                ? reauthenticatedAt
                : authenticatedAt;
    }
}
