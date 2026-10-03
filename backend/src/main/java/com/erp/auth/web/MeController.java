package com.erp.auth.web;

import com.erp.auth.application.AccountService;
import com.erp.auth.application.ApiTokenService;
import com.erp.auth.application.AuthUser;
import com.erp.auth.application.CredentialService;
import com.erp.auth.application.MfaService;
import com.erp.auth.application.SessionService;
import com.erp.org.api.CompanySummary;
import com.erp.platform.context.CurrentContext;
import com.erp.platform.json.RawText;
import com.erp.platform.security.ActorType;
import com.erp.platform.security.AuthenticatedActor;
import com.erp.platform.security.AuthenticatedEndpoint;
import com.erp.platform.security.ReauthenticationGuard;
import com.erp.platform.web.ApiException;
import com.erp.platform.web.ApiPaths;
import com.erp.platform.web.EntityTags;
import com.erp.platform.web.PlatformErrorCode;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** The signed-in user's own account (API.md §17.1). Every endpoint acts on the caller only. */
@RestController
class MeController {

    private final AccountService accounts;
    private final CredentialService credentials;
    private final MfaService mfa;
    private final SessionService sessions;
    private final ApiTokenService apiTokens;
    private final SessionCookies cookies;
    private final Clock clock;

    MeController(
            AccountService accounts,
            CredentialService credentials,
            MfaService mfa,
            SessionService sessions,
            ApiTokenService apiTokens,
            SessionCookies cookies,
            Clock clock) {
        this.accounts = accounts;
        this.credentials = credentials;
        this.mfa = mfa;
        this.sessions = sessions;
        this.apiTokens = apiTokens;
        this.cookies = cookies;
        this.clock = clock;
    }

    record CompanyAccessResponse(
            UUID id,
            String code,
            String displayName,
            Set<String> permissions,
            @Nullable Set<UUID> branchScope) {}

    record ProfileResponse(
            AuthResponses.UserResponse user,
            boolean mfaRequired,
            boolean mfaEnrollmentRequired,
            List<CompanyAccessResponse> companies) {}

    record CompanyResponse(UUID id, String code, String displayName, String baseCurrency, boolean isActive) {
        static CompanyResponse from(CompanySummary c) {
            return new CompanyResponse(c.id(), c.code(), c.displayName(), c.baseCurrency(), c.active());
        }
    }

    record ChangePasswordRequest(
            @NotNull @RawText @Size(max = 1024) String currentPassword,
            @NotNull @RawText @Size(max = 1024) String newPassword) {}

    record ReauthenticateRequest(
            @NotNull @RawText @Size(max = 1024) String password) {}

    record ConfirmMfaRequest(
            @NotBlank @Pattern(regexp = "^\\d{6}$") String code) {}

    record EnrollmentResponse(String secret, String otpauthUri) {}

    record RecoveryCodesResponse(List<String> recoveryCodes) {}

    record CreateTokenRequest(
            @NotBlank @Size(max = 100) String name,
            @Min(1) @Max(365) @Nullable Integer expiresInDays,
            @Nullable UUID companyId,

            @Size(max = 200) @Nullable Set<@Pattern(regexp = "^[a-z_]+\\.[a-z_]+\\.[a-z_]+$") String> allowedPermissions,

            @Min(1) @Max(100_000) @Nullable Integer rateLimitPerMinute) {

        ApiTokenService.TokenRequest toRequest() {
            return new ApiTokenService.TokenRequest(
                    name, expiresInDays, companyId, allowedPermissions, rateLimitPerMinute);
        }
    }

    @AllowedDuringMfaEnrollment
    @AuthenticatedEndpoint
    @GetMapping(ApiPaths.V1 + "/me")
    ResponseEntity<ProfileResponse> me() {
        AccountService.Profile profile = accounts.profile(CurrentContext.requireActor());
        ProfileResponse body = new ProfileResponse(
                AuthResponses.UserResponse.from(profile.user()),
                profile.mfaRequired(),
                profile.mfaEnrollmentRequired(),
                profile.companies().stream()
                        .map(c -> new CompanyAccessResponse(
                                c.company().id(),
                                c.company().code(),
                                c.company().displayName(),
                                new TreeSet<>(c.permissions()),
                                c.branchScope() == null ? null : new TreeSet<>(c.branchScope())))
                        .toList());
        return ResponseEntity.ok()
                .eTag(EntityTags.forVersion(profile.user().version()))
                .body(body);
    }

    @AuthenticatedEndpoint
    @PatchMapping(path = ApiPaths.V1 + "/me", consumes = "application/merge-patch+json")
    ResponseEntity<AuthResponses.UserResponse> patch(
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch,
            @RequestBody JsonNode patch) {
        AuthUser user = accounts.patch(CurrentContext.requireActor(), ifMatch, patch);
        return ResponseEntity.ok()
                .eTag(EntityTags.forVersion(user.version()))
                .body(AuthResponses.UserResponse.from(user));
    }

    /** The companies the caller can work in (API.md §3). */
    @AuthenticatedEndpoint
    @GetMapping(ApiPaths.V1 + "/companies")
    AuthResponses.ListResponse<CompanyResponse> companies() {
        return new AuthResponses.ListResponse<>(accounts.accessibleCompanies(CurrentContext.requireActor()).stream()
                .map(CompanyResponse::from)
                .toList());
    }

    @AuthenticatedEndpoint
    @PostMapping(ApiPaths.V1 + "/me/password")
    ResponseEntity<Void> changePassword(@Valid @RequestBody ChangePasswordRequest body, HttpServletResponse response) {
        AuthenticatedActor actor = sessionActor();
        cookies.setSession(response, credentials.changePassword(actor, body.currentPassword(), body.newPassword()));
        return ResponseEntity.noContent().build();
    }

    /** Step-up re-authentication, valid five minutes (SECURITY.md §3.3). */
    @AuthenticatedEndpoint
    @PostMapping(ApiPaths.V1 + "/me/reauthenticate")
    ResponseEntity<Void> reauthenticate(@Valid @RequestBody ReauthenticateRequest body, HttpServletResponse response) {
        cookies.setSession(response, accounts.reauthenticate(sessionActor(), body.password()));
        return ResponseEntity.noContent().build();
    }

    @AllowedDuringMfaEnrollment
    @AuthenticatedEndpoint
    @PostMapping(ApiPaths.V1 + "/me/mfa/totp/setup")
    EnrollmentResponse startMfaEnrollment() {
        MfaService.Enrollment enrollment = mfa.startEnrollment(sessionActor().userId());
        return new EnrollmentResponse(enrollment.secret(), enrollment.provisioningUri());
    }

    @AllowedDuringMfaEnrollment
    @AuthenticatedEndpoint
    @PostMapping(ApiPaths.V1 + "/me/mfa/totp/confirm")
    RecoveryCodesResponse confirmMfaEnrollment(@Valid @RequestBody ConfirmMfaRequest body) {
        return new RecoveryCodesResponse(mfa.confirmEnrollment(sessionActor().userId(), body.code()));
    }

    @AuthenticatedEndpoint
    @DeleteMapping(ApiPaths.V1 + "/me/mfa/totp")
    ResponseEntity<Void> removeMfa() {
        AuthenticatedActor actor = sessionActor();
        ReauthenticationGuard.require(actor, clock.instant());
        mfa.disable(actor.userId());
        return ResponseEntity.noContent().build();
    }

    @AuthenticatedEndpoint
    @PostMapping(ApiPaths.V1 + "/me/mfa/recovery-codes")
    RecoveryCodesResponse regenerateRecoveryCodes() {
        AuthenticatedActor actor = sessionActor();
        ReauthenticationGuard.require(actor, clock.instant());
        return new RecoveryCodesResponse(mfa.regenerateRecoveryCodes(actor.userId()));
    }

    @AuthenticatedEndpoint
    @GetMapping(ApiPaths.V1 + "/me/sessions")
    AuthResponses.ListResponse<AuthResponses.SessionResponse> sessions() {
        AuthenticatedActor actor = sessionActor();
        return new AuthResponses.ListResponse<>(sessions.list(actor.userId()).stream()
                .map(s -> AuthResponses.SessionResponse.from(s, actor.credentialId()))
                .toList());
    }

    @AuthenticatedEndpoint
    @DeleteMapping(ApiPaths.V1 + "/me/sessions/{sessionId}")
    ResponseEntity<Void> revokeSession(@PathVariable UUID sessionId) {
        if (!sessions.revoke(sessionActor().userId(), sessionId)) {
            throw ApiException.notFound();
        }
        return ResponseEntity.noContent().build();
    }

    @AuthenticatedEndpoint
    @GetMapping(ApiPaths.V1 + "/me/api-tokens")
    AuthResponses.ListResponse<AuthResponses.ApiTokenResponse> apiTokens() {
        return new AuthResponses.ListResponse<>(
                apiTokens.list(CurrentContext.requireActor().userId()).stream()
                        .map(AuthResponses.ApiTokenResponse::from)
                        .toList());
    }

    /** Requires {@code auth.api_token.manage_own} and a recent password confirmation (checked in the service). */
    @AuthenticatedEndpoint
    @PostMapping(ApiPaths.V1 + "/me/api-tokens")
    ResponseEntity<AuthResponses.CreatedApiTokenResponse> createApiToken(@Valid @RequestBody CreateTokenRequest body) {
        ApiTokenService.IssuedToken issued = apiTokens.createPersonal(body.toRequest());
        return ResponseEntity.status(201)
                .body(new AuthResponses.CreatedApiTokenResponse(
                        AuthResponses.ApiTokenResponse.from(issued.token()), issued.secret()));
    }

    @AuthenticatedEndpoint
    @DeleteMapping(ApiPaths.V1 + "/me/api-tokens/{tokenId}")
    ResponseEntity<Void> revokeApiToken(@PathVariable UUID tokenId) {
        apiTokens.revoke(CurrentContext.requireActor().userId(), tokenId);
        return ResponseEntity.noContent().build();
    }

    /** Password, step-up, MFA and session management need a browser session, not an API token. */
    private static AuthenticatedActor sessionActor() {
        AuthenticatedActor actor = CurrentContext.requireActor();
        if (actor.type() != ActorType.USER) {
            throw new ApiException(PlatformErrorCode.FORBIDDEN, "This operation requires a signed-in browser session.");
        }
        return actor;
    }
}
