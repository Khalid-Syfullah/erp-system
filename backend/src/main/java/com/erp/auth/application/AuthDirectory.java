package com.erp.auth.application;

import com.erp.auth.api.AuthFacade;
import com.erp.auth.domain.UserStatus;
import com.erp.auth.domain.UserType;
import com.erp.auth.persistence.ApiTokenRepository;
import com.erp.auth.persistence.AssignmentRepository;
import com.erp.auth.persistence.SessionRepository;
import com.erp.auth.persistence.UserRepository;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditPort;
import com.erp.platform.context.CurrentContext;
import com.erp.platform.context.RequestContext;
import com.erp.platform.web.ApiException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** {@link AuthFacade} for HR. */
@Service
class AuthDirectory implements AuthFacade {

    private final UserRepository users;
    private final AssignmentRepository assignments;
    private final SessionRepository sessions;
    private final ApiTokenRepository apiTokens;
    private final PermissionResolver permissions;
    private final AuditPort audit;
    private final Clock clock;

    AuthDirectory(
            UserRepository users,
            AssignmentRepository assignments,
            SessionRepository sessions,
            ApiTokenRepository apiTokens,
            PermissionResolver permissions,
            AuditPort audit,
            Clock clock) {
        this.users = users;
        this.assignments = assignments;
        this.sessions = sessions;
        this.apiTokens = apiTokens;
        this.permissions = permissions;
        this.audit = audit;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<LinkableUser> linkableUser(UUID userId, UUID companyId) {
        return users.findById(userId)
                .filter(u -> u.userType() == UserType.HUMAN && u.status() != UserStatus.DISABLED)
                .filter(u -> assignments
                        .grant(u.id(), companyId, LocalDate.now(clock))
                        .isPresent())
                .map(u -> new LinkableUser(u.id(), u.email(), u.displayName()));
    }

    @Override
    @Transactional
    public void deactivateForTermination(UUID userId) {
        AuthUser user = users.lock(userId).orElseThrow(ApiException::notFound);
        if (user.status() == UserStatus.DISABLED) {
            return;
        }
        if (user.systemAdmin()
                && user.status() == UserStatus.ACTIVE
                && users.countActiveSystemAdminsExclusively() <= 1) {
            throw new ApiException(
                    AuthErrorCode.LAST_SYSTEM_ADMIN,
                    "The employee's user is the last active system administrator and cannot be disabled.");
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        UUID actor = CurrentContext.get()
                .map(RequestContext::actor)
                .map(a -> a.userId())
                .orElse(null);
        users.setStatus(userId, UserStatus.DISABLED, actor, now);
        sessions.deleteForUser(userId);
        apiTokens.revokeAll(userId, now);
        permissions.invalidateUser(userId);
        audit.record(AuditEvent.builder("STATE_CHANGE", "auth")
                .entity("user", userId, user.email())
                .transition(user.status().name(), UserStatus.DISABLED.name())
                .detail("reason", "EMPLOYEE_TERMINATED")
                .build());
    }
}
