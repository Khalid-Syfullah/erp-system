package com.erp.auth.web;

import com.erp.auth.AuthPermissions;
import com.erp.auth.application.ApiTokenService;
import com.erp.auth.application.AssignmentService;
import com.erp.auth.application.AuthUser;
import com.erp.auth.application.UserAdministrationService;
import com.erp.auth.application.UserListings;
import com.erp.auth.domain.UserType;
import com.erp.platform.security.GlobalAccess;
import com.erp.platform.security.RequiresPermission;
import com.erp.platform.web.ApiPaths;
import com.erp.platform.web.EntityTags;
import com.erp.platform.web.paging.ListQueryParser;
import com.erp.platform.web.paging.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * User, service-account and role-assignment administration (API.md §17.2). Global system
 * administration: permissions come from the system administrator flag, and transactions may record
 * company-scoped audit entries ({@link GlobalAccess}).
 */
@GlobalAccess
@RestController
@RequestMapping(ApiPaths.V1 + "/admin")
class AdminUserController {

    private final UserAdministrationService users;
    private final AssignmentService assignments;
    private final ApiTokenService apiTokens;
    private final ListQueryParser parser;

    AdminUserController(
            UserAdministrationService users,
            AssignmentService assignments,
            ApiTokenService apiTokens,
            ListQueryParser parser) {
        this.users = users;
        this.assignments = assignments;
        this.apiTokens = apiTokens;
        this.parser = parser;
    }

    record CreateUserRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(max = 200) String displayName,
            @Nullable Boolean isSystemAdmin) {}

    record CreateServiceAccountRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(max = 200) String displayName) {}

    record AssignRequest(
            @NotNull UUID companyId,
            @NotNull UUID roleId,
            @Size(max = 500) @Nullable Set<UUID> branchIds,
            @Nullable LocalDate validFrom,
            @Nullable LocalDate validTo) {}

    record CreateTokenRequest(
            @NotBlank @Size(max = 100) String name,
            @Min(1) @Max(365) @Nullable Integer expiresInDays,
            @Nullable UUID companyId,

            @Size(max = 200) @Nullable Set<@Pattern(regexp = "^[a-z_]+\\.[a-z_]+\\.[a-z_]+$") String> allowedPermissions,

            @Min(1) @Max(100_000) @Nullable Integer rateLimitPerMinute) {}

    // ------------------------------------------------------------------------------- users

    @RequiresPermission(AuthPermissions.USER_READ)
    @GetMapping("/users")
    PageResponse<AuthResponses.UserResponse> list(@RequestParam MultiValueMap<String, String> parameters) {
        return users.list(parser.parse(parameters, UserListings.USERS), null).map(AuthResponses.UserResponse::from);
    }

    @RequiresPermission(AuthPermissions.USER_MANAGE)
    @PostMapping("/users")
    ResponseEntity<AuthResponses.UserResponse> create(@Valid @RequestBody CreateUserRequest body) {
        AuthUser user = users.createUser(body.email(), body.displayName(), Boolean.TRUE.equals(body.isSystemAdmin()));
        return ResponseEntity.created(URI.create(ApiPaths.V1 + "/admin/users/" + user.id()))
                .eTag(EntityTags.forVersion(user.version()))
                .body(AuthResponses.UserResponse.from(user));
    }

    @RequiresPermission(AuthPermissions.USER_READ)
    @GetMapping("/users/{userId}")
    ResponseEntity<AuthResponses.UserResponse> get(@PathVariable UUID userId) {
        return withETag(users.get(userId));
    }

    @RequiresPermission(AuthPermissions.USER_MANAGE)
    @PatchMapping(path = "/users/{userId}", consumes = "application/merge-patch+json")
    ResponseEntity<AuthResponses.UserResponse> patch(
            @PathVariable UUID userId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch,
            @RequestBody JsonNode patch) {
        return withETag(users.patch(userId, ifMatch, patch));
    }

    @RequiresPermission(AuthPermissions.USER_MANAGE)
    @PostMapping("/users/{userId}/disable")
    ResponseEntity<AuthResponses.UserResponse> disable(
            @PathVariable UUID userId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch) {
        return withETag(users.disable(userId, ifMatch));
    }

    @RequiresPermission(AuthPermissions.USER_MANAGE)
    @PostMapping("/users/{userId}/enable")
    ResponseEntity<AuthResponses.UserResponse> enable(
            @PathVariable UUID userId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch) {
        return withETag(users.enable(userId, ifMatch));
    }

    @RequiresPermission(AuthPermissions.USER_MANAGE)
    @PostMapping("/users/{userId}/unlock")
    ResponseEntity<AuthResponses.UserResponse> unlock(
            @PathVariable UUID userId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch) {
        return withETag(users.unlock(userId, ifMatch));
    }

    @RequiresPermission(AuthPermissions.USER_MANAGE)
    @PostMapping("/users/{userId}/reset-mfa")
    ResponseEntity<AuthResponses.UserResponse> resetMfa(
            @PathVariable UUID userId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch) {
        return withETag(users.resetMfa(userId, ifMatch));
    }

    @RequiresPermission(AuthPermissions.USER_MANAGE)
    @PostMapping("/users/{userId}/resend-invite")
    ResponseEntity<AuthResponses.UserResponse> resendInvite(
            @PathVariable UUID userId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch) {
        return withETag(users.resendInvitation(userId, ifMatch));
    }

    @RequiresPermission(AuthPermissions.USER_MANAGE)
    @PostMapping("/users/{userId}/revoke-sessions")
    ResponseEntity<Void> revokeSessions(@PathVariable UUID userId) {
        users.revokeSessions(userId);
        return ResponseEntity.noContent().build();
    }

    // --------------------------------------------------------------------- role assignments

    @RequiresPermission(AuthPermissions.USER_READ)
    @GetMapping("/users/{userId}/role-assignments")
    AuthResponses.ListResponse<AuthResponses.AssignmentResponse> assignmentsOf(@PathVariable UUID userId) {
        return new AuthResponses.ListResponse<>(assignments.listForUser(userId).stream()
                .map(AuthResponses.AssignmentResponse::from)
                .toList());
    }

    @RequiresPermission(AuthPermissions.ROLE_ASSIGNMENT_MANAGE)
    @PostMapping("/users/{userId}/role-assignments")
    ResponseEntity<AuthResponses.AssignmentResponse> assign(
            @PathVariable UUID userId, @Valid @RequestBody AssignRequest body) {
        var created = assignments.assignAsSystemAdmin(
                body.companyId(),
                new AssignmentService.NewAssignment(
                        userId,
                        body.roleId(),
                        body.branchIds() == null ? Set.of() : body.branchIds(),
                        body.validFrom(),
                        body.validTo()));
        return ResponseEntity.status(201).body(AuthResponses.AssignmentResponse.from(created));
    }

    @RequiresPermission(AuthPermissions.ROLE_ASSIGNMENT_MANAGE)
    @DeleteMapping("/users/{userId}/role-assignments/{assignmentId}")
    ResponseEntity<Void> unassign(@PathVariable UUID userId, @PathVariable UUID assignmentId) {
        assignments.removeAsSystemAdmin(userId, assignmentId);
        return ResponseEntity.noContent().build();
    }

    // --------------------------------------------------------------------- service accounts

    @RequiresPermission(AuthPermissions.SERVICE_ACCOUNT_MANAGE)
    @GetMapping("/service-accounts")
    PageResponse<AuthResponses.UserResponse> serviceAccounts(@RequestParam MultiValueMap<String, String> parameters) {
        return users.list(parser.parse(parameters, UserListings.USERS), UserType.SERVICE)
                .map(AuthResponses.UserResponse::from);
    }

    @RequiresPermission(AuthPermissions.SERVICE_ACCOUNT_MANAGE)
    @PostMapping("/service-accounts")
    ResponseEntity<AuthResponses.UserResponse> createServiceAccount(
            @Valid @RequestBody CreateServiceAccountRequest body) {
        AuthUser account = users.createServiceAccount(body.email(), body.displayName());
        return ResponseEntity.created(URI.create(ApiPaths.V1 + "/admin/users/" + account.id()))
                .body(AuthResponses.UserResponse.from(account));
    }

    @RequiresPermission(AuthPermissions.SERVICE_ACCOUNT_MANAGE)
    @GetMapping("/service-accounts/{userId}/api-tokens")
    AuthResponses.ListResponse<AuthResponses.ApiTokenResponse> serviceAccountTokens(@PathVariable UUID userId) {
        return new AuthResponses.ListResponse<>(apiTokens.listForServiceAccount(userId).stream()
                .map(AuthResponses.ApiTokenResponse::from)
                .toList());
    }

    @RequiresPermission(AuthPermissions.SERVICE_ACCOUNT_MANAGE)
    @PostMapping("/service-accounts/{userId}/api-tokens")
    ResponseEntity<AuthResponses.CreatedApiTokenResponse> createServiceAccountToken(
            @PathVariable UUID userId, @Valid @RequestBody CreateTokenRequest body) {
        ApiTokenService.IssuedToken issued = apiTokens.createForServiceAccount(
                userId,
                new ApiTokenService.TokenRequest(
                        body.name(),
                        body.expiresInDays(),
                        body.companyId(),
                        body.allowedPermissions(),
                        body.rateLimitPerMinute()));
        return ResponseEntity.status(201)
                .body(new AuthResponses.CreatedApiTokenResponse(
                        AuthResponses.ApiTokenResponse.from(issued.token()), issued.secret()));
    }

    @RequiresPermission(AuthPermissions.SERVICE_ACCOUNT_MANAGE)
    @DeleteMapping("/service-accounts/{userId}/api-tokens/{tokenId}")
    ResponseEntity<Void> revokeServiceAccountToken(@PathVariable UUID userId, @PathVariable UUID tokenId) {
        apiTokens.revokeForServiceAccount(userId, tokenId);
        return ResponseEntity.noContent().build();
    }

    private static ResponseEntity<AuthResponses.UserResponse> withETag(AuthUser user) {
        return ResponseEntity.ok()
                .eTag(EntityTags.forVersion(user.version()))
                .body(AuthResponses.UserResponse.from(user));
    }
}
