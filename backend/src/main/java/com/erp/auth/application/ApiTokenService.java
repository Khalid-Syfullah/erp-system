package com.erp.auth.application;

import com.erp.auth.AuthPermissions;
import com.erp.auth.domain.ApiTokenFormat;
import com.erp.auth.domain.SecureTokens;
import com.erp.auth.domain.UserStatus;
import com.erp.auth.domain.UserType;
import com.erp.auth.persistence.ApiTokenRepository;
import com.erp.auth.persistence.RoleRepository;
import com.erp.auth.persistence.UserRepository;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditPort;
import com.erp.platform.context.CurrentContext;
import com.erp.platform.security.ActorType;
import com.erp.platform.security.AuthenticatedActor;
import com.erp.platform.security.ReauthenticationGuard;
import com.erp.platform.web.ApiException;
import com.erp.platform.web.FieldViolation;
import com.erp.platform.web.PlatformErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * API tokens for integrations (SECURITY.md §3.6): mandatory expiry (≤ 365 days), optional company
 * restriction and permission down-scoping, shown once, stored as SHA-256 hash. Personal tokens need a
 * recent password confirmation and {@code auth.api_token.manage_own}; service-account tokens are
 * issued by system administrators.
 */
@Service
public class ApiTokenService {

    /** Requested token. */
    public record TokenRequest(
            String name,
            @Nullable Integer expiresInDays,
            @Nullable UUID companyId,
            @Nullable Set<String> allowedPermissions,
            @Nullable Integer rateLimitPerMinute) {}

    /** The only moment the secret exists outside the client. */
    public record IssuedToken(ApiTokenInfo token, String secret) {}

    static final Duration TOUCH_INTERVAL = Duration.ofMinutes(1);

    private final ApiTokenRepository tokens;
    private final UserRepository users;
    private final RoleRepository roles;
    private final PermissionResolver permissions;
    private final AuditPort audit;
    private final AuthProperties properties;
    private final Clock clock;

    public ApiTokenService(
            ApiTokenRepository tokens,
            UserRepository users,
            RoleRepository roles,
            PermissionResolver permissions,
            AuditPort audit,
            AuthProperties properties,
            Clock clock) {
        this.tokens = tokens;
        this.users = users;
        this.roles = roles;
        this.permissions = permissions;
        this.audit = audit;
        this.properties = properties;
        this.clock = clock;
    }

    /** Validates a bearer token; runs without a transaction. */
    public Optional<AuthenticatedActor> authenticate(String token) {
        if (ApiTokenFormat.publicPrefix(token).isEmpty()) {
            return Optional.empty();
        }
        Optional<ApiTokenInfo> found = tokens.findByHash(SecureTokens.sha256(token));
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (found.isEmpty()
                || found.get().revokedAt() != null
                || !found.get().expiresAt().isAfter(now)) {
            return Optional.empty();
        }
        ApiTokenInfo info = found.get();
        Optional<AuthUser> user = users.findById(info.userId()).filter(u -> u.status() == UserStatus.ACTIVE);
        if (user.isEmpty()) {
            return Optional.empty();
        }
        if (info.lastUsedAt() == null || info.lastUsedAt().plus(TOUCH_INTERVAL).isBefore(now)) {
            tokens.touch(info.id(), now);
        }
        return Optional.of(new AuthenticatedActor(
                info.userId(),
                ActorType.API_TOKEN,
                info.id(),
                user.get().systemAdmin(),
                info.createdAt().toInstant(),
                null,
                info.companyId(),
                info.allowedPermissions() == null ? null : Set.copyOf(info.allowedPermissions()),
                false,
                info.rateLimitPerMinute()));
    }

    @Transactional
    public IssuedToken createPersonal(TokenRequest request) {
        AuthenticatedActor actor = CurrentContext.requireActor();
        ReauthenticationGuard.require(actor, clock.instant());
        AuthUser user = users.findById(actor.userId()).orElseThrow(ApiException::notFound);
        Set<String> held = permissions.permissionUnion(user.id(), user.systemAdmin());
        if (!held.contains(AuthPermissions.API_TOKEN_MANAGE_OWN)) {
            throw new ApiException(PlatformErrorCode.FORBIDDEN, "You are not allowed to create personal API tokens.");
        }
        return issue(user, request, actor.userId());
    }

    @Transactional
    public IssuedToken createForServiceAccount(UUID serviceAccountId, TokenRequest request) {
        AuthUser account = serviceAccount(serviceAccountId);
        return issue(account, request, CurrentContext.requireActor().userId());
    }

    @Transactional(readOnly = true)
    public List<ApiTokenInfo> list(UUID userId) {
        return tokens.listForUser(userId);
    }

    @Transactional(readOnly = true)
    public List<ApiTokenInfo> listForServiceAccount(UUID serviceAccountId) {
        return tokens.listForUser(serviceAccount(serviceAccountId).id());
    }

    @Transactional
    public void revoke(UUID ownerId, UUID tokenId) {
        if (!tokens.revoke(ownerId, tokenId, OffsetDateTime.now(clock))) {
            throw ApiException.notFound();
        }
        audit.record(AuditEvent.builder("API_TOKEN_REVOKE", "auth")
                .entity("api_token", tokenId, null)
                .detail("ownerId", ownerId.toString())
                .build());
    }

    @Transactional
    public void revokeForServiceAccount(UUID serviceAccountId, UUID tokenId) {
        revoke(serviceAccount(serviceAccountId).id(), tokenId);
    }

    private IssuedToken issue(AuthUser owner, TokenRequest request, UUID issuer) {
        List<FieldViolation> violations = new ArrayList<>();
        int days =
                request.expiresInDays() == null ? properties.tokens().apiTokenDefaultDays() : request.expiresInDays();
        if (days < 1 || days > properties.tokens().apiTokenMaxDays()) {
            violations.add(FieldViolation.atPointer(
                    "/expiresInDays",
                    "OUT_OF_RANGE",
                    "must be between 1 and " + properties.tokens().apiTokenMaxDays()));
        }
        if (request.companyId() != null
                && permissions.grant(owner.id(), request.companyId()).isEmpty()) {
            violations.add(
                    FieldViolation.atPointer("/companyId", "NO_ACCESS", "the token owner has no role in this company"));
        }
        List<String> allowed = null;
        if (request.allowedPermissions() != null) {
            Set<String> catalogue =
                    roles.activePermissions().stream().map(PermissionInfo::code).collect(Collectors.toSet());
            Set<String> held = request.companyId() != null
                    ? permissions
                            .grant(owner.id(), request.companyId())
                            .map(CompanyGrant::permissions)
                            .orElse(Set.of())
                    : permissions.permissionUnion(owner.id(), owner.systemAdmin());
            for (String permission : new TreeSet<>(request.allowedPermissions())) {
                if (!catalogue.contains(permission)) {
                    violations.add(FieldViolation.atPointer(
                            "/allowedPermissions", "UNKNOWN_PERMISSION", "'" + permission + "' is not a permission"));
                } else if (!held.contains(permission)) {
                    violations.add(FieldViolation.atPointer(
                            "/allowedPermissions", "NOT_HELD", "'" + permission + "' is not held by the token owner"));
                }
            }
            allowed = new ArrayList<>(new TreeSet<>(request.allowedPermissions()));
        }
        if (!violations.isEmpty()) {
            throw ApiException.validationFailed("The API token request is invalid.", violations);
        }
        ApiTokenFormat.Generated generated = ApiTokenFormat.generate();
        while (tokens.prefixExists(generated.publicPrefix())) {
            generated = ApiTokenFormat.generate();
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        UUID id = tokens.insert(
                owner.id(),
                request.name(),
                generated.publicPrefix(),
                SecureTokens.sha256(generated.token()),
                request.companyId(),
                allowed,
                request.rateLimitPerMinute(),
                now,
                now.plusDays(days),
                issuer);
        audit.record(AuditEvent.builder("API_TOKEN_CREATE", "auth")
                .entity("api_token", id, request.name())
                .detail("ownerId", owner.id().toString())
                .detail("prefix", generated.publicPrefix())
                .detail("expiresInDays", days)
                .detail(
                        "companyId",
                        request.companyId() == null ? null : request.companyId().toString())
                .detail("allowedPermissions", allowed == null ? null : allowed.toString())
                .build());
        ApiTokenInfo info = tokens.listForUser(owner.id()).stream()
                .filter(t -> t.id().equals(id))
                .findFirst()
                .orElseThrow();
        return new IssuedToken(info, generated.token());
    }

    private AuthUser serviceAccount(UUID userId) {
        return users.findById(userId)
                .filter(u -> u.userType() == UserType.SERVICE)
                .orElseThrow(ApiException::notFound);
    }
}
