package com.erp.auth.application;

import com.erp.auth.domain.UserStatus;
import com.erp.auth.domain.UserType;
import com.erp.auth.persistence.ApiTokenRepository;
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
import com.erp.platform.web.paging.ListQuery;
import com.erp.platform.web.paging.PageResponse;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * User and service-account administration by system administrators (API.md §17.2, PRODUCT_SPEC.md
 * §3). There is no self-registration: accounts are created by an administrator and activated through
 * an emailed invitation. Administrators cannot disable or demote themselves, and the last active
 * system administrator cannot be disabled or demoted.
 */
@Service
public class UserAdministrationService {

    static final Set<String> PATCHABLE = Set.of("displayName", "locale", "timezone", "isSystemAdmin");

    private final UserRepository users;
    private final SessionRepository sessions;
    private final ApiTokenRepository apiTokens;
    private final CredentialService credentials;
    private final MfaService mfa;
    private final PermissionResolver permissions;
    private final AuditPort audit;
    private final Clock clock;

    public UserAdministrationService(
            UserRepository users,
            SessionRepository sessions,
            ApiTokenRepository apiTokens,
            CredentialService credentials,
            MfaService mfa,
            PermissionResolver permissions,
            AuditPort audit,
            Clock clock) {
        this.users = users;
        this.sessions = sessions;
        this.apiTokens = apiTokens;
        this.credentials = credentials;
        this.mfa = mfa;
        this.permissions = permissions;
        this.audit = audit;
        this.clock = clock;
    }

    /** Creates an invited human user and emails the invitation. */
    @Transactional
    public AuthUser createUser(String email, String displayName, boolean systemAdmin) {
        UUID actor = CurrentContext.requireActor().userId();
        String normalized = normalizeEmail(email);
        UUID id = users.insert(
                normalized, displayName, UserType.HUMAN, UserStatus.INVITED, null, systemAdmin, actor, now());
        AuthUser user = users.findById(id).orElseThrow();
        credentials.issueInvitation(user);
        audit.record(AuditEvent.builder("CREATE", "auth")
                .entity("user", id, normalized)
                .detail("userType", UserType.HUMAN.name())
                .detail("isSystemAdmin", systemAdmin)
                .transition(null, UserStatus.INVITED.name())
                .build());
        return user;
    }

    /** Creates an active service account (API tokens only, no password). */
    @Transactional
    public AuthUser createServiceAccount(String email, String displayName) {
        UUID actor = CurrentContext.requireActor().userId();
        String normalized = normalizeEmail(email);
        UUID id = users.insert(normalized, displayName, UserType.SERVICE, UserStatus.ACTIVE, null, false, actor, now());
        audit.record(AuditEvent.builder("CREATE", "auth")
                .entity("user", id, normalized)
                .detail("userType", UserType.SERVICE.name())
                .transition(null, UserStatus.ACTIVE.name())
                .build());
        return users.findById(id).orElseThrow();
    }

    @Transactional(readOnly = true)
    public PageResponse<AuthUser> list(ListQuery query, @Nullable UserType type) {
        return users.list(query, type);
    }

    @Transactional(readOnly = true)
    public AuthUser get(UUID userId) {
        return users.findById(userId).orElseThrow(ApiException::notFound);
    }

    @Transactional
    public AuthUser patch(UUID userId, @Nullable String ifMatch, JsonNode document) {
        AuthUser current = users.lock(userId).orElseThrow(ApiException::notFound);
        EntityTags.requireMatch(ifMatch, current.version());
        MergePatch patch = MergePatch.of(document, PATCHABLE);
        MergePatch.Member<String> displayName = patch.text("displayName", true, 200);
        MergePatch.Member<String> locale = patch.text(
                "locale",
                true,
                5,
                l -> l.matches("^[a-z]{2}(-[A-Z]{2})?$") ? null : "must be a locale such as en or en-GB");
        MergePatch.Member<String> timezone = patch.text("timezone", false, 64, UserAdministrationService::zoneError);
        MergePatch.Member<Boolean> systemAdmin = patch.bool("isSystemAdmin");
        patch.throwIfInvalid();

        boolean newAdmin = Boolean.TRUE.equals(systemAdmin.orElse(current.systemAdmin()));
        if (current.systemAdmin() && !newAdmin) {
            guardNotSelf(userId, "remove your own system administrator flag");
            guardNotLastAdmin(current);
        }
        if (newAdmin && current.userType() == UserType.SERVICE) {
            throw ApiException.validationFailed(
                    "Service accounts cannot be system administrators.",
                    List.of(FieldViolation.atPointer(
                            "/isSystemAdmin", "INVALID_VALUE", "must be false for service accounts")));
        }
        UUID actor = CurrentContext.requireActor().userId();
        boolean updated = users.updateProfile(
                userId,
                current.version(),
                displayName.orElse(current.displayName()),
                locale.orElse(current.locale()),
                timezone.orElse(current.timezone()),
                newAdmin,
                actor,
                now());
        if (!updated) {
            throw new ApiException(PlatformErrorCode.VERSION_CONFLICT, "The user was modified concurrently.");
        }
        if (newAdmin != current.systemAdmin()) {
            // Privilege change: existing sessions must re-authenticate with the new privileges (and MFA).
            sessions.deleteForUser(userId);
        }
        permissions.invalidateUser(userId);
        AuthUser after = get(userId);
        audit.record(AuditEvent.builder(newAdmin != current.systemAdmin() ? "PRIVILEGE_CHANGE" : "UPDATE", "auth")
                .entity("user", userId, after.email())
                .change("displayName", current.displayName(), after.displayName())
                .change("locale", current.locale(), after.locale())
                .change("timezone", current.timezone(), after.timezone())
                .change("isSystemAdmin", current.systemAdmin(), after.systemAdmin())
                .build());
        return after;
    }

    /** Disables the account: all sessions end and all API tokens are revoked at once. */
    @Transactional
    public AuthUser disable(UUID userId, @Nullable String ifMatch) {
        AuthUser user = lockVersioned(userId, ifMatch);
        guardNotSelf(userId, "disable your own account");
        if (user.status() == UserStatus.DISABLED) {
            throw new ApiException(PlatformErrorCode.INVALID_STATE, "The user is already disabled.");
        }
        if (user.systemAdmin()) {
            guardNotLastAdmin(user);
        }
        OffsetDateTime now = now();
        users.setStatus(userId, UserStatus.DISABLED, actor(), now);
        sessions.deleteForUser(userId);
        apiTokens.revokeAll(userId, now);
        permissions.invalidateUser(userId);
        return transitioned(user, UserStatus.DISABLED);
    }

    @Transactional
    public AuthUser enable(UUID userId, @Nullable String ifMatch) {
        AuthUser user = lockVersioned(userId, ifMatch);
        if (user.status() != UserStatus.DISABLED) {
            throw new ApiException(PlatformErrorCode.INVALID_STATE, "Only disabled users can be enabled.");
        }
        UserStatus target = user.userType() == UserType.HUMAN && user.passwordHash() == null
                ? UserStatus.INVITED
                : UserStatus.ACTIVE;
        users.setStatus(userId, target, actor(), now());
        return transitioned(user, target);
    }

    /** Lifts a lock (administrative or temporary) and clears the failure counter. */
    @Transactional
    public AuthUser unlock(UUID userId, @Nullable String ifMatch) {
        AuthUser user = lockVersioned(userId, ifMatch);
        boolean temporarilyLocked =
                user.lockedUntil() != null && user.lockedUntil().isAfter(now());
        if (user.status() != UserStatus.LOCKED && !temporarilyLocked) {
            throw new ApiException(PlatformErrorCode.INVALID_STATE, "The user is not locked.");
        }
        UserStatus target = user.status() == UserStatus.LOCKED ? UserStatus.ACTIVE : user.status();
        users.setStatus(userId, target, actor(), now());
        return transitioned(user, target);
    }

    @Transactional
    public AuthUser resetMfa(UUID userId, @Nullable String ifMatch) {
        AuthUser user = lockVersioned(userId, ifMatch);
        if (!user.mfaEnabled()) {
            throw new ApiException(AuthErrorCode.MFA_NOT_ENROLLED, "The user has no authenticator enrolled.");
        }
        mfa.reset(user);
        return get(userId);
    }

    @Transactional
    public AuthUser resendInvitation(UUID userId, @Nullable String ifMatch) {
        AuthUser user = lockVersioned(userId, ifMatch);
        if (user.status() != UserStatus.INVITED) {
            throw new ApiException(PlatformErrorCode.INVALID_STATE, "Only invited users can receive an invitation.");
        }
        credentials.issueInvitation(user);
        audit.record(AuditEvent.builder("INVITE", "auth")
                .entity("user", userId, user.email())
                .build());
        return user;
    }

    @Transactional
    public int revokeSessions(UUID userId) {
        AuthUser user = get(userId);
        int revoked = sessions.deleteForUser(userId);
        audit.record(AuditEvent.builder("SESSION_REVOKE", "auth")
                .entity("user", userId, user.email())
                .detail("sessions", revoked)
                .build());
        return revoked;
    }

    private AuthUser lockVersioned(UUID userId, @Nullable String ifMatch) {
        AuthUser user = users.lock(userId).orElseThrow(ApiException::notFound);
        EntityTags.requireMatch(ifMatch, user.version());
        return user;
    }

    private AuthUser transitioned(AuthUser before, UserStatus target) {
        audit.record(AuditEvent.builder("STATE_CHANGE", "auth")
                .entity("user", before.id(), before.email())
                .transition(before.status().name(), target.name())
                .build());
        return get(before.id());
    }

    private void guardNotLastAdmin(AuthUser user) {
        if (user.status() == UserStatus.ACTIVE && users.countActiveSystemAdminsExclusively() <= 1) {
            throw new ApiException(
                    AuthErrorCode.LAST_SYSTEM_ADMIN,
                    "The last active system administrator cannot be removed or disabled.");
        }
    }

    private static void guardNotSelf(UUID userId, String what) {
        if (userId.equals(CurrentContext.requireActor().userId())) {
            throw new ApiException(PlatformErrorCode.FORBIDDEN, "You cannot " + what + ".");
        }
    }

    static String normalizeEmail(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }

    static @Nullable String zoneError(String zone) {
        try {
            ZoneId.of(zone);
            return ZoneId.getAvailableZoneIds().contains(zone) ? null : "must be an IANA time zone";
        } catch (DateTimeException e) {
            return "must be an IANA time zone";
        }
    }

    private UUID actor() {
        return CurrentContext.requireActor().userId();
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }
}
