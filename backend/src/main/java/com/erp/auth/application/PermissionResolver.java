package com.erp.auth.application;

import com.erp.auth.AuthPermissions;
import com.erp.auth.persistence.AssignmentRepository;
import com.erp.platform.context.RequestContext;
import com.erp.platform.security.AuthenticatedActor;
import com.erp.platform.security.CompanyAccess;
import com.erp.platform.security.CompanyAccessResolver;
import com.erp.platform.security.PermissionCheck;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * Authorization decisions (SECURITY.md §4.1, §4.4): company membership and effective permissions from
 * valid role assignments, cached per (user, company) for {@code erp.auth.permission-cache-ttl}
 * (60 s). Changes made on this instance invalidate the cache at once; other instances converge within
 * the TTL. API tokens are down-scoped to their company restriction and allowed permissions.
 */
@Service
public class PermissionResolver implements PermissionCheck, CompanyAccessResolver {

    private record Key(UUID userId, UUID companyId) {}

    private record Entry(Optional<CompanyGrant> grant, Instant expiresAt) {}

    private final AssignmentRepository assignments;
    private final Clock clock;
    private final AuthProperties properties;
    private final ConcurrentHashMap<Key, Entry> cache = new ConcurrentHashMap<>();

    public PermissionResolver(AssignmentRepository assignments, Clock clock, AuthProperties properties) {
        this.assignments = assignments;
        this.clock = clock;
        this.properties = properties;
    }

    @Override
    public Optional<CompanyAccess> resolve(AuthenticatedActor actor, UUID companyId) {
        if (actor.companyRestriction() != null && !actor.companyRestriction().equals(companyId)) {
            return Optional.empty();
        }
        return grant(actor.userId(), companyId).map(g -> new CompanyAccess(companyId, g.branchScope()));
    }

    @Override
    public boolean isGranted(RequestContext context, String permission) {
        AuthenticatedActor actor = context.actor();
        if (actor == null) {
            return false;
        }
        Set<String> granted;
        if (context.companyId() == null) {
            // Global endpoints: only the system administrator's global permissions, never via a
            // company-restricted token.
            granted = actor.systemAdmin() && actor.companyRestriction() == null
                    ? AuthPermissions.SYSTEM_ADMIN_GLOBAL
                    : Set.of();
        } else {
            granted = grant(actor.userId(), context.companyId())
                    .map(CompanyGrant::permissions)
                    .orElse(Set.of());
        }
        if (actor.allowedPermissions() != null && !actor.allowedPermissions().contains(permission)) {
            return false;
        }
        return granted.contains(permission);
    }

    /** The user's effective access to the company today (UTC), cached. */
    public Optional<CompanyGrant> grant(UUID userId, UUID companyId) {
        Instant now = clock.instant();
        Key key = new Key(userId, companyId);
        Entry entry = cache.get(key);
        if (entry == null || entry.expiresAt().isBefore(now)) {
            entry = new Entry(assignments.grant(userId, companyId, today()), now.plus(properties.permissionCacheTtl()));
            cache.put(key, entry);
        }
        return entry.grant();
    }

    /** Companies in which the user currently has a valid assignment (not cached). */
    public Set<UUID> accessibleCompanies(UUID userId) {
        return assignments.companiesWithValidAssignments(userId, today());
    }

    /** Union of the user's permissions across all companies plus, for system admins, the global set. */
    public Set<String> permissionUnion(UUID userId, boolean systemAdmin) {
        Set<String> union = new HashSet<>(systemAdmin ? AuthPermissions.SYSTEM_ADMIN_GLOBAL : Set.of());
        for (UUID companyId : accessibleCompanies(userId)) {
            grant(userId, companyId).ifPresent(g -> union.addAll(g.permissions()));
        }
        return union;
    }

    public void invalidateUser(UUID userId) {
        cache.keySet().removeIf(key -> key.userId().equals(userId));
    }

    public void invalidateAll() {
        cache.clear();
    }

    LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
