package com.erp.auth.web;

import com.erp.auth.AuthPermissions;
import com.erp.auth.application.RoleInfo;
import com.erp.auth.application.RoleService;
import com.erp.platform.security.GlobalAccess;
import com.erp.platform.security.RequiresPermission;
import com.erp.platform.web.ApiPaths;
import com.erp.platform.web.EntityTags;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Roles and the permission catalogue (API.md §17.2). */
@GlobalAccess
@RestController
@RequestMapping(ApiPaths.V1 + "/admin")
class AdminRoleController {

    private final RoleService roles;

    AdminRoleController(RoleService roles) {
        this.roles = roles;
    }

    record CreateRoleRequest(
            @NotBlank @Pattern(regexp = "^[A-Z][A-Z0-9_]{1,49}$") String code,

            @NotBlank @Size(max = 100) String name,
            @Size(max = 500) @Nullable String description,
            @Nullable Boolean requiresMfa,
            @NotNull @Size(max = 200) Set<@NotBlank String> permissions) {}

    record PermissionsRequest(@NotNull @Size(max = 200) Set<@NotBlank String> permissions) {}

    @RequiresPermission(AuthPermissions.ROLE_READ)
    @GetMapping("/roles")
    AuthResponses.ListResponse<AuthResponses.RoleResponse> list() {
        return new AuthResponses.ListResponse<>(
                roles.list().stream().map(AuthResponses.RoleResponse::from).toList());
    }

    @RequiresPermission(AuthPermissions.ROLE_READ)
    @GetMapping("/permissions")
    AuthResponses.ListResponse<AuthResponses.PermissionResponse> permissions() {
        return new AuthResponses.ListResponse<>(roles.permissions().stream()
                .map(AuthResponses.PermissionResponse::from)
                .toList());
    }

    @RequiresPermission(AuthPermissions.ROLE_MANAGE)
    @PostMapping("/roles")
    ResponseEntity<AuthResponses.RoleResponse> create(@Valid @RequestBody CreateRoleRequest body) {
        RoleInfo role = roles.create(
                body.code(),
                body.name(),
                body.description(),
                Boolean.TRUE.equals(body.requiresMfa()),
                body.permissions());
        return ResponseEntity.created(URI.create(ApiPaths.V1 + "/admin/roles/" + role.id()))
                .eTag(EntityTags.forVersion(role.version()))
                .body(AuthResponses.RoleResponse.from(role));
    }

    @RequiresPermission(AuthPermissions.ROLE_READ)
    @GetMapping("/roles/{roleId}")
    ResponseEntity<AuthResponses.RoleResponse> get(@PathVariable UUID roleId) {
        return withETag(roles.get(roleId));
    }

    @RequiresPermission(AuthPermissions.ROLE_MANAGE)
    @PatchMapping(path = "/roles/{roleId}", consumes = "application/merge-patch+json")
    ResponseEntity<AuthResponses.RoleResponse> patch(
            @PathVariable UUID roleId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch,
            @RequestBody JsonNode patch) {
        return withETag(roles.patch(roleId, ifMatch, patch));
    }

    @RequiresPermission(AuthPermissions.ROLE_MANAGE)
    @PutMapping("/roles/{roleId}/permissions")
    ResponseEntity<AuthResponses.RoleResponse> replacePermissions(
            @PathVariable UUID roleId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch,
            @Valid @RequestBody PermissionsRequest body) {
        return withETag(roles.replacePermissions(roleId, ifMatch, body.permissions()));
    }

    @RequiresPermission(AuthPermissions.ROLE_MANAGE)
    @DeleteMapping("/roles/{roleId}")
    ResponseEntity<Void> delete(
            @PathVariable UUID roleId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch) {
        roles.delete(roleId, ifMatch);
        return ResponseEntity.noContent().build();
    }

    private static ResponseEntity<AuthResponses.RoleResponse> withETag(RoleInfo role) {
        return ResponseEntity.ok()
                .eTag(EntityTags.forVersion(role.version()))
                .body(AuthResponses.RoleResponse.from(role));
    }
}
