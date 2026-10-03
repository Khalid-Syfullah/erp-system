package com.erp.auth.application;

import com.erp.auth.persistence.UserRepository;
import com.erp.org.api.CompanySummary;
import com.erp.org.api.OrgFacade;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditPort;
import com.erp.platform.security.AuthenticatedActor;
import com.erp.platform.web.ApiException;
import com.erp.platform.web.EntityTags;
import com.erp.platform.web.MergePatch;
import com.erp.platform.web.PlatformErrorCode;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/** The signed-in user's own account ({@code /me}, API.md §17.1). */
@Service
public class AccountService {

    /** The caller's access to one company. */
    public record CompanyAccessView(
            CompanySummary company,
            Set<String> permissions,
            @Nullable Set<UUID> branchScope) {}

    /** Profile plus effective access, for the SPA's navigation (UX only; the backend still checks). */
    public record Profile(
            AuthUser user, boolean mfaRequired, boolean mfaEnrollmentRequired, List<CompanyAccessView> companies) {}

    static final Set<String> PATCHABLE = Set.of("displayName", "locale", "timezone");

    private final UserRepository users;
    private final PermissionResolver permissions;
    private final MfaService mfa;
    private final CredentialService credentials;
    private final SessionService sessions;
    private final OrgFacade org;
    private final AuditPort audit;
    private final Clock clock;

    public AccountService(
            UserRepository users,
            PermissionResolver permissions,
            MfaService mfa,
            CredentialService credentials,
            SessionService sessions,
            OrgFacade org,
            AuditPort audit,
            Clock clock) {
        this.users = users;
        this.permissions = permissions;
        this.mfa = mfa;
        this.credentials = credentials;
        this.sessions = sessions;
        this.org = org;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Profile profile(AuthenticatedActor actor) {
        AuthUser user = users.findById(actor.userId()).orElseThrow(ApiException::notFound);
        List<CompanyAccessView> companies = org.findCompanies(accessibleCompanyIds(actor)).stream()
                .map(company -> permissions
                        .grant(user.id(), company.id())
                        .map(grant -> new CompanyAccessView(
                                company,
                                actor.allowedPermissions() == null
                                        ? new TreeSet<>(grant.permissions())
                                        : grant.permissions().stream()
                                                .filter(actor.allowedPermissions()::contains)
                                                .collect(java.util.stream.Collectors.toCollection(TreeSet::new)),
                                grant.branchScope()))
                        .orElse(null))
                .filter(java.util.Objects::nonNull)
                .toList();
        return new Profile(user, mfa.isRequired(user), actor.mfaEnrollmentRequired(), companies);
    }

    /** Companies the caller can work in (an API token with a company restriction sees only that one). */
    @Transactional(readOnly = true)
    public List<CompanySummary> accessibleCompanies(AuthenticatedActor actor) {
        return org.findCompanies(accessibleCompanyIds(actor));
    }

    @Transactional
    public AuthUser patch(AuthenticatedActor actor, @Nullable String ifMatch, JsonNode document) {
        AuthUser current = users.lock(actor.userId()).orElseThrow(ApiException::notFound);
        EntityTags.requireMatch(ifMatch, current.version());
        MergePatch patch = MergePatch.of(document, PATCHABLE);
        MergePatch.Member<String> displayName = patch.text("displayName", true, 200);
        MergePatch.Member<String> locale = patch.text(
                "locale",
                true,
                5,
                l -> l.matches("^[a-z]{2}(-[A-Z]{2})?$") ? null : "must be a locale such as en or en-GB");
        MergePatch.Member<String> timezone = patch.text("timezone", false, 64, UserAdministrationService::zoneError);
        patch.throwIfInvalid();
        if (!users.updateProfile(
                current.id(),
                current.version(),
                displayName.orElse(current.displayName()),
                locale.orElse(current.locale()),
                timezone.orElse(current.timezone()),
                current.systemAdmin(),
                current.id(),
                OffsetDateTime.now(clock))) {
            throw new ApiException(PlatformErrorCode.VERSION_CONFLICT, "Your profile was modified concurrently.");
        }
        AuthUser after = users.findById(current.id()).orElseThrow();
        audit.record(AuditEvent.builder("UPDATE", "auth")
                .entity("user", current.id(), current.email())
                .change("displayName", current.displayName(), after.displayName())
                .change("locale", current.locale(), after.locale())
                .change("timezone", current.timezone(), after.timezone())
                .build());
        return after;
    }

    /** Step-up: re-enter the password; returns the rotated session secret. */
    @Transactional
    public String reauthenticate(AuthenticatedActor actor, String password) {
        AuthUser user = users.findById(actor.userId()).orElseThrow(ApiException::notFound);
        credentials.requireCurrentPassword(user, password, "/password");
        String rotated = sessions.rotate(actor.credentialId(), true);
        audit.record(AuditEvent.builder("REAUTHENTICATE", "auth")
                .entity("user", user.id(), user.email())
                .build());
        return rotated;
    }

    private Set<UUID> accessibleCompanyIds(AuthenticatedActor actor) {
        Set<UUID> ids = permissions.accessibleCompanies(actor.userId());
        if (actor.companyRestriction() != null) {
            ids = ids.contains(actor.companyRestriction()) ? Set.of(actor.companyRestriction()) : Set.of();
        }
        return ids;
    }
}
