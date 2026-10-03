package com.erp.auth.application;

import com.erp.auth.AuthPermissions;
import com.erp.auth.domain.UserStatus;
import com.erp.auth.persistence.AssignmentRepository;
import com.erp.auth.persistence.RoleRepository;
import com.erp.auth.persistence.SessionRepository;
import com.erp.auth.persistence.UserRepository;
import com.erp.org.api.OrgFacade;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditPort;
import com.erp.platform.context.CurrentContext;
import com.erp.platform.security.AuthenticatedActor;
import com.erp.platform.security.SegregationOfDuties;
import com.erp.platform.web.ApiException;
import com.erp.platform.web.FieldViolation;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Role assignments (SECURITY.md §4.1, §4.3). System administrators manage assignments in any
 * company. Company administrators manage them inside their company, with escalation guards: they can
 * only hand out roles whose permissions they hold themselves, cannot change their own assignments,
 * and cannot grant global-only permissions. Branch IDs must belong to the company (composite FK).
 */
@Service
public class AssignmentService {

    /** Requested assignment. */
    public record NewAssignment(
            UUID userId,
            UUID roleId,
            Set<UUID> branchIds,
            @Nullable LocalDate validFrom,
            @Nullable LocalDate validTo) {}

    private final AssignmentRepository assignments;
    private final RoleRepository roles;
    private final UserRepository users;
    private final SessionRepository sessions;
    private final PermissionResolver permissionResolver;
    private final OrgFacade org;
    private final AuditPort audit;
    private final Clock clock;

    public AssignmentService(
            AssignmentRepository assignments,
            RoleRepository roles,
            UserRepository users,
            SessionRepository sessions,
            PermissionResolver permissionResolver,
            OrgFacade org,
            AuditPort audit,
            Clock clock) {
        this.assignments = assignments;
        this.roles = roles;
        this.users = users;
        this.sessions = sessions;
        this.permissionResolver = permissionResolver;
        this.org = org;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<AssignmentInfo> listForUser(UUID userId) {
        users.findById(userId).orElseThrow(ApiException::notFound);
        return assignments.listForUser(userId);
    }

    /** Resolves a user for a company administrator's assignment request. */
    @Transactional(readOnly = true)
    public java.util.Optional<UUID> findUserIdByEmail(String email) {
        return users.findByEmail(UserAdministrationService.normalizeEmail(email))
                .map(AuthUser::id);
    }

    @Transactional(readOnly = true)
    public List<AssignmentInfo> listForCompany(UUID companyId) {
        return assignments.listForCompany(companyId);
    }

    /** System administrator, any company (global endpoint). */
    @Transactional
    public AssignmentInfo assignAsSystemAdmin(UUID companyId, NewAssignment request) {
        if (org.findCompany(companyId).isEmpty()) {
            throw ApiException.validationFailed(
                    "The company does not exist.",
                    List.of(FieldViolation.atPointer("/companyId", "UNKNOWN_COMPANY", "is not a company")));
        }
        return assign(companyId, request);
    }

    /** Company administrator inside the active company; escalation-guarded. */
    @Transactional
    public AssignmentInfo assignInCompany(NewAssignment request) {
        UUID companyId = CurrentContext.requireCompany();
        AuthenticatedActor actor = CurrentContext.requireActor();
        SegregationOfDuties.requireDifferentUsers(actor.userId(), request.userId(), "change your own role assignments");
        RoleInfo role = roles.find(request.roleId()).orElseThrow(() -> unknownRole());
        requireHeldByActor(actor, companyId, role);
        return assign(companyId, request);
    }

    @Transactional
    public void removeAsSystemAdmin(UUID userId, UUID assignmentId) {
        AssignmentInfo assignment = assignments
                .find(assignmentId)
                .filter(a -> a.userId().equals(userId))
                .orElseThrow(ApiException::notFound);
        remove(assignment);
    }

    @Transactional
    public void removeInCompany(UUID assignmentId) {
        UUID companyId = CurrentContext.requireCompany();
        AuthenticatedActor actor = CurrentContext.requireActor();
        AssignmentInfo assignment = assignments
                .find(assignmentId)
                .filter(a -> a.companyId().equals(companyId))
                .orElseThrow(ApiException::notFound);
        SegregationOfDuties.requireDifferentUsers(
                actor.userId(), assignment.userId(), "change your own role assignments");
        RoleInfo role = roles.find(assignment.roleId()).orElseThrow();
        requireHeldByActor(actor, companyId, role);
        remove(assignment);
    }

    private AssignmentInfo assign(UUID companyId, NewAssignment request) {
        AuthUser user = users.findById(request.userId())
                .orElseThrow(() -> ApiException.validationFailed(
                        "The user does not exist.",
                        List.of(FieldViolation.atPointer("/userId", "UNKNOWN_USER", "is not a user"))));
        if (user.status() == UserStatus.DISABLED) {
            throw ApiException.validationFailed(
                    "Disabled users cannot receive roles.",
                    List.of(FieldViolation.atPointer("/userId", "USER_DISABLED", "is disabled")));
        }
        RoleInfo role = roles.find(request.roleId()).orElseThrow(AssignmentService::unknownRole);
        UUID actor = CurrentContext.requireActor().userId();
        UUID id = assignments.insert(
                user.id(),
                role.id(),
                companyId,
                request.branchIds(),
                request.validFrom(),
                request.validTo(),
                actor,
                OffsetDateTime.now(clock));
        permissionResolver.invalidateUser(user.id());
        if (!user.mfaEnabled() && roles.requiresMfa(role)) {
            // New MFA obligation (flagged role or sensitive permission): current sessions must sign
            // in again and enroll.
            sessions.deleteForUser(user.id());
        }
        audit.record(AuditEvent.builder("ROLE_ASSIGNMENT", "auth")
                .entity("user", user.id(), user.email())
                .company(companyId)
                .transition(null, "ASSIGNED")
                .detail("role", role.code())
                .detail(
                        "branches",
                        request.branchIds().isEmpty() ? "ALL" : new TreeSet<>(request.branchIds()).toString())
                .detail(
                        "validFrom",
                        request.validFrom() == null ? null : request.validFrom().toString())
                .detail(
                        "validTo",
                        request.validTo() == null ? null : request.validTo().toString())
                .build());
        return assignments.find(id).orElseThrow();
    }

    private void remove(AssignmentInfo assignment) {
        assignments.delete(assignment.id());
        permissionResolver.invalidateUser(assignment.userId());
        AuthUser user = users.findById(assignment.userId()).orElseThrow();
        if (!user.systemAdmin() && !assignments.hasAnyAssignment(user.id())) {
            // SECURITY.md §3.3: removing all assignments ends the user's sessions.
            sessions.deleteForUser(user.id());
        }
        audit.record(AuditEvent.builder("ROLE_ASSIGNMENT", "auth")
                .entity("user", assignment.userId(), assignment.userEmail())
                .company(assignment.companyId())
                .transition("ASSIGNED", "REMOVED")
                .detail("role", assignment.roleCode())
                .build());
    }

    /** No escalation: the company administrator must hold every permission of the role. */
    private void requireHeldByActor(AuthenticatedActor actor, UUID companyId, RoleInfo role) {
        Set<String> held = new HashSet<>(permissionResolver
                .grant(actor.userId(), companyId)
                .map(CompanyGrant::permissions)
                .orElse(Set.of()));
        if (actor.allowedPermissions() != null) {
            held.retainAll(actor.allowedPermissions());
        }
        Set<String> missing = new TreeSet<>(role.permissions());
        missing.removeAll(held);
        boolean global = role.permissions().stream().anyMatch(AuthPermissions.GLOBAL_ONLY::contains);
        if (!missing.isEmpty() || global) {
            throw new ApiException(
                    AuthErrorCode.PRIVILEGE_ESCALATION,
                    "You can only assign or remove roles whose permissions you hold yourself.");
        }
    }

    private static ApiException unknownRole() {
        return ApiException.validationFailed(
                "The role does not exist.",
                List.of(FieldViolation.atPointer("/roleId", "UNKNOWN_ROLE", "is not a role")));
    }
}
