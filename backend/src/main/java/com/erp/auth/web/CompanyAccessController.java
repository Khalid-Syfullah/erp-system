package com.erp.auth.web;

import com.erp.auth.AuthPermissions;
import com.erp.auth.application.AssignmentService;
import com.erp.auth.application.RoleService;
import com.erp.platform.security.RequiresPermission;
import com.erp.platform.web.ApiException;
import com.erp.platform.web.ApiPaths;
import com.erp.platform.web.FieldViolation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Role assignments inside one company, for company administrators ({@code auth.role_assignment.manage},
 * SECURITY.md §4.3). Escalation-guarded in {@link AssignmentService#assignInCompany}.
 */
@RestController
@RequestMapping(ApiPaths.V1 + "/companies/{companyId}")
class CompanyAccessController {

    private final AssignmentService assignments;
    private final RoleService roles;

    CompanyAccessController(AssignmentService assignments, RoleService roles) {
        this.assignments = assignments;
        this.roles = roles;
    }

    /** Identifies the user by ID or email (company administrators usually know the email). */
    record AssignRequest(
            @Nullable UUID userId,
            @Email @Size(max = 254) @Nullable String userEmail,
            @NotNull UUID roleId,
            @Size(max = 500) @Nullable Set<UUID> branchIds,
            @Nullable LocalDate validFrom,
            @Nullable LocalDate validTo) {}

    @RequiresPermission(AuthPermissions.ROLE_ASSIGNMENT_MANAGE)
    @GetMapping("/role-assignments")
    AuthResponses.ListResponse<AuthResponses.AssignmentResponse> list(@PathVariable UUID companyId) {
        return new AuthResponses.ListResponse<>(assignments.listForCompany(companyId).stream()
                .map(AuthResponses.AssignmentResponse::from)
                .toList());
    }

    @RequiresPermission(AuthPermissions.ROLE_ASSIGNMENT_MANAGE)
    @GetMapping("/roles")
    AuthResponses.ListResponse<AuthResponses.RoleResponse> roles(@PathVariable UUID companyId) {
        return new AuthResponses.ListResponse<>(
                roles.list().stream().map(AuthResponses.RoleResponse::from).toList());
    }

    @RequiresPermission(AuthPermissions.ROLE_ASSIGNMENT_MANAGE)
    @PostMapping("/role-assignments")
    ResponseEntity<AuthResponses.AssignmentResponse> assign(
            @PathVariable UUID companyId, @Valid @RequestBody AssignRequest body) {
        UUID userId = resolveUser(body);
        var created = assignments.assignInCompany(new AssignmentService.NewAssignment(
                userId,
                body.roleId(),
                body.branchIds() == null ? Set.of() : body.branchIds(),
                body.validFrom(),
                body.validTo()));
        return ResponseEntity.status(201).body(AuthResponses.AssignmentResponse.from(created));
    }

    @RequiresPermission(AuthPermissions.ROLE_ASSIGNMENT_MANAGE)
    @DeleteMapping("/role-assignments/{assignmentId}")
    ResponseEntity<Void> unassign(@PathVariable UUID companyId, @PathVariable UUID assignmentId) {
        assignments.removeInCompany(assignmentId);
        return ResponseEntity.noContent().build();
    }

    private UUID resolveUser(AssignRequest body) {
        if ((body.userId() == null) == (body.userEmail() == null)) {
            throw ApiException.validationFailed(
                    "Identify the user by userId or userEmail.",
                    List.of(FieldViolation.atPointer(
                            "", "EXACTLY_ONE", "exactly one of userId or userEmail is required")));
        }
        if (body.userId() != null) {
            return body.userId();
        }
        return assignments
                .findUserIdByEmail(body.userEmail())
                .orElseThrow(() -> ApiException.validationFailed(
                        "No user with this email exists.",
                        List.of(FieldViolation.atPointer("/userEmail", "UNKNOWN_USER", "is not a user"))));
    }
}
