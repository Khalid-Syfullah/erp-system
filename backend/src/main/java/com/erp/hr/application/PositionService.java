package com.erp.hr.application;

import com.erp.hr.persistence.EmploymentAssignmentRepository;
import com.erp.hr.persistence.PositionRepository;
import com.erp.org.api.DepartmentSummary;
import com.erp.org.api.OrgFacade;
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
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * Positions (designations / job titles) of the active company. A position tied to a department may
 * only be held in that department; a position still held (now or in future) cannot be deactivated or
 * moved to a department its holders are not in.
 */
@Service
public class PositionService {

    static final Set<String> PATCHABLE = Set.of("title", "departmentId", "grade");

    private final PositionRepository positions;
    private final EmploymentAssignmentRepository assignments;
    private final OrgFacade org;
    private final HrCalendar calendar;
    private final AuditPort audit;

    PositionService(
            PositionRepository positions,
            EmploymentAssignmentRepository assignments,
            OrgFacade org,
            HrCalendar calendar,
            AuditPort audit) {
        this.positions = positions;
        this.assignments = assignments;
        this.org = org;
        this.calendar = calendar;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public PageResponse<PositionView> list(ListQuery query) {
        return positions.list(CurrentContext.requireCompany(), query);
    }

    @Transactional(readOnly = true)
    public PositionView get(UUID id) {
        return positions.find(CurrentContext.requireCompany(), id).orElseThrow(ApiException::notFound);
    }

    @Transactional
    public PositionView create(HrCommands.Position command) {
        UUID companyId = CurrentContext.requireCompany();
        if (command.departmentId() != null) {
            departmentViolation(companyId, command.departmentId()).ifPresent(v -> {
                throw ApiException.validationFailed("The position is invalid.", List.of(v));
            });
        }
        UUID id = positions.insert(
                companyId, command, CurrentContext.requireActor().userId());
        audit.record(AuditEvent.builder("CREATE", "hr")
                .entity("position", id, command.code())
                .detail("title", command.title())
                .detail("departmentId", command.departmentId())
                .detail("grade", command.grade())
                .build());
        return get(id);
    }

    @Transactional
    public PositionView patch(UUID id, @Nullable String ifMatch, JsonNode document) {
        UUID companyId = CurrentContext.requireCompany();
        PositionView current = positions.lockForChange(companyId, id).orElseThrow(ApiException::notFound);
        EntityTags.requireMatch(ifMatch, current.version());
        MergePatch patch = MergePatch.of(document, PATCHABLE);
        var title = patch.text("title", true, 100);
        var department = patch.uuid("departmentId", false);
        var grade = patch.text("grade", false, 20);
        patch.throwIfInvalid();

        UUID newDepartment = department.orElse(current.departmentId());
        if (newDepartment != null && !newDepartment.equals(current.departmentId())) {
            departmentViolation(companyId, newDepartment).ifPresent(v -> {
                throw ApiException.validationFailed("The position is invalid.", List.of(v));
            });
            int elsewhere = assignments.countCurrentOrFutureWithPosition(
                    companyId, id, newDepartment, calendar.today(companyId));
            if (elsewhere > 0) {
                throw new ApiException(
                        PlatformErrorCode.RESOURCE_IN_USE,
                        "The position is held in other departments by " + elsewhere
                                + " current or future assignment(s); it cannot be tied to this department.");
            }
        }
        update(
                current,
                new HrCommands.Position(
                        current.code(),
                        title.orElse(current.title()),
                        newDepartment,
                        grade.orElse(current.grade()),
                        current.active()));
        PositionView after = get(id);
        audit.record(AuditEvent.builder("UPDATE", "hr")
                .entity("position", id, after.code())
                .change("title", current.title(), after.title())
                .change("departmentId", current.departmentId(), after.departmentId())
                .change("grade", current.grade(), after.grade())
                .build());
        return after;
    }

    @Transactional
    public PositionView setActive(UUID id, @Nullable String ifMatch, boolean active) {
        UUID companyId = CurrentContext.requireCompany();
        PositionView current = positions.lockForChange(companyId, id).orElseThrow(ApiException::notFound);
        EntityTags.requireMatch(ifMatch, current.version());
        if (current.active() == active) {
            throw new ApiException(
                    PlatformErrorCode.INVALID_STATE, "The position is already " + (active ? "active." : "inactive."));
        }
        if (active) {
            if (current.departmentId() != null
                    && !org.departmentForUse(companyId, current.departmentId())
                            .map(DepartmentSummary::active)
                            .orElse(false)) {
                throw new ApiException(PlatformErrorCode.INVALID_STATE, "Activate the position's department first.");
            }
        } else {
            int held = assignments.countCurrentOrFutureWithPosition(companyId, id, null, calendar.today(companyId));
            if (held > 0) {
                throw new ApiException(
                        PlatformErrorCode.RESOURCE_IN_USE,
                        "The position is held by " + held + " current or future assignment(s).");
            }
        }
        update(
                current,
                new HrCommands.Position(
                        current.code(), current.title(), current.departmentId(), current.grade(), active));
        audit.record(AuditEvent.builder("STATE_CHANGE", "hr")
                .entity("position", id, current.code())
                .transition(current.active() ? "ACTIVE" : "INACTIVE", active ? "ACTIVE" : "INACTIVE")
                .build());
        return get(id);
    }

    private Optional<FieldViolation> departmentViolation(UUID companyId, UUID departmentId) {
        Optional<DepartmentSummary> department = org.departmentForUse(companyId, departmentId);
        if (department.isEmpty()) {
            return Optional.of(FieldViolation.atPointer(
                    "/departmentId", "UNKNOWN_DEPARTMENT", "is not a department of the company"));
        }
        if (!department.get().active()) {
            return Optional.of(FieldViolation.atPointer("/departmentId", "INACTIVE", "must be an active department"));
        }
        return Optional.empty();
    }

    private void update(PositionView current, HrCommands.Position next) {
        if (!positions.update(
                current.companyId(),
                current.id(),
                current.version(),
                CurrentContext.requireActor().userId(),
                next)) {
            throw new ApiException(PlatformErrorCode.VERSION_CONFLICT, "The position was modified concurrently.");
        }
    }
}
