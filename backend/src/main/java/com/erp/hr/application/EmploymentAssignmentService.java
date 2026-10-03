package com.erp.hr.application;

import com.erp.hr.domain.EffectivePeriod;
import com.erp.hr.domain.EmployeeStatus;
import com.erp.hr.domain.ReportingLines;
import com.erp.hr.persistence.EmployeeRepository;
import com.erp.hr.persistence.EmploymentAssignmentRepository;
import com.erp.hr.persistence.PositionRepository;
import com.erp.org.api.BranchSummary;
import com.erp.org.api.DepartmentSummary;
import com.erp.org.api.OrgFacade;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditPort;
import com.erp.platform.context.CurrentContext;
import com.erp.platform.context.RequestContext;
import com.erp.platform.web.ApiException;
import com.erp.platform.web.EntityTags;
import com.erp.platform.web.FieldViolation;
import com.erp.platform.web.MergePatch;
import com.erp.platform.web.PlatformErrorCode;
import com.erp.platform.web.paging.ListQuery;
import com.erp.platform.web.paging.PageResponse;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * Employment assignments: where an employee works (branch, department), as what (position) and to
 * whom they report, for a period (PRODUCT_SPEC.md §10). Rules enforced here, after locking every row
 * involved (DATABASE.md §9):
 *
 * <ul>
 *   <li>periods of one employee never overlap (HR-1; exclusion constraint as backstop);
 *   <li>a period starts on or after the hire date and the employee is not terminated;
 *   <li>branch, department and position exist in the company and are active; a department tied to a
 *       branch is only used in that branch, a position tied to a department only in that department;
 *   <li>the manager is another employee of the company, employed from the start of the period and not
 *       terminated within it, and the reporting line creates no cycle at any date of the period.
 * </ul>
 *
 * Users with a restricted branch scope only see and change assignments in their branches.
 */
@Service
public class EmploymentAssignmentService {

    public static final BigDecimal MIN_FTE = new BigDecimal("0.0001");
    public static final int FTE_SCALE = 4;
    static final Set<String> EMPLOYMENT_TYPES = Set.of("FULL_TIME", "PART_TIME", "CONTRACT", "INTERN", "TEMPORARY");
    static final Set<String> PATCHABLE =
            Set.of("positionId", "managerEmployeeId", "employmentType", "fte", "effectiveTo");

    private final EmploymentAssignmentRepository assignments;
    private final EmployeeRepository employees;
    private final PositionRepository positions;
    private final OrgFacade org;
    private final HrCalendar calendar;
    private final AuditPort audit;

    EmploymentAssignmentService(
            EmploymentAssignmentRepository assignments,
            EmployeeRepository employees,
            PositionRepository positions,
            OrgFacade org,
            HrCalendar calendar,
            AuditPort audit) {
        this.assignments = assignments;
        this.employees = employees;
        this.positions = positions;
        this.org = org;
        this.calendar = calendar;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public PageResponse<AssignmentView> list(@Nullable LocalDate asOf, ListQuery query) {
        return assignments.list(
                CurrentContext.requireCompany(), CurrentContext.require().branchScope(), asOf, query);
    }

    /** The employee's assignments in the caller's branch scope, oldest first. */
    @Transactional(readOnly = true)
    public List<AssignmentView> forEmployee(UUID employeeId) {
        UUID companyId = CurrentContext.requireCompany();
        Set<UUID> scope = CurrentContext.require().branchScope();
        employees.find(companyId, employeeId, scope, calendar.today(companyId)).orElseThrow(ApiException::notFound);
        return assignments.forEmployee(companyId, employeeId).stream()
                .filter(a -> scope == null || scope.contains(a.branchId()))
                .toList();
    }

    @Transactional
    public AssignmentView create(UUID employeeId, HrCommands.Assignment command) {
        UUID companyId = CurrentContext.requireCompany();
        EmployeeView employee = employees
                .lockForChange(companyId, employeeId, CurrentContext.require().branchScope(), calendar.today(companyId))
                .orElseThrow(ApiException::notFound);
        return createFor(employee, command);
    }

    /** Creates an assignment for an employee the caller has already locked (also the initial assignment). */
    AssignmentView createFor(EmployeeView employee, HrCommands.Assignment command) {
        UUID companyId = employee.companyId();
        if (employee.status() == EmployeeStatus.TERMINATED) {
            throw new ApiException(PlatformErrorCode.INVALID_STATE, "The employee is terminated.");
        }
        LocalDate from = command.effectiveFrom() != null ? command.effectiveFrom() : employee.hireDate();
        List<FieldViolation> violations = new ArrayList<>();
        if (from.isBefore(employee.hireDate())) {
            violations.add(
                    FieldViolation.atPointer("/effectiveFrom", "BEFORE_HIRE", "must not be before the hire date"));
        }
        if (command.effectiveTo() != null && command.effectiveTo().isBefore(from)) {
            violations.add(
                    FieldViolation.atPointer("/effectiveTo", "INVALID_VALUE", "must not be before effectiveFrom"));
        }
        if (!violations.isEmpty()) {
            throw ApiException.validationFailed("The assignment is invalid.", violations);
        }
        EffectivePeriod period = new EffectivePeriod(from, command.effectiveTo());
        validateUnits(companyId, command.branchId(), command.departmentId(), command.positionId(), violations);
        if (command.managerEmployeeId() != null) {
            validateManager(companyId, employee.id(), command.managerEmployeeId(), period, violations);
        }
        if (!violations.isEmpty()) {
            throw ApiException.validationFailed("The assignment is invalid.", violations);
        }
        requireNoOverlap(companyId, employee.id(), period, null);

        HrCommands.Assignment resolved = new HrCommands.Assignment(
                command.branchId(),
                command.departmentId(),
                command.positionId(),
                command.managerEmployeeId(),
                command.employmentType(),
                command.fte(),
                from,
                command.effectiveTo());
        UUID id = assignments.insert(
                companyId,
                employee.id(),
                resolved,
                CurrentContext.requireActor().userId());
        audit.record(AuditEvent.builder("CREATE", "hr")
                .entity("employment_assignment", id, employee.employeeNumber())
                .detail("employeeId", employee.id())
                .detail("branchId", command.branchId())
                .detail("departmentId", command.departmentId())
                .detail("positionId", command.positionId())
                .detail("managerEmployeeId", command.managerEmployeeId())
                .detail("employmentType", command.employmentType())
                .detail("fte", command.fte().toPlainString())
                .detail("effectiveFrom", from.toString())
                .detail("effectiveTo", Objects.toString(command.effectiveTo(), null))
                .build());
        return assignments.find(companyId, employee.id(), id).orElseThrow();
    }

    @Transactional
    public AssignmentView patch(UUID employeeId, UUID assignmentId, @Nullable String ifMatch, JsonNode document) {
        UUID companyId = CurrentContext.requireCompany();
        EmployeeView employee = lockEmployee(companyId, employeeId);
        AssignmentView current = visibleAssignment(companyId, employeeId, assignmentId);
        EntityTags.requireMatch(ifMatch, current.version());
        if (employee.status() == EmployeeStatus.TERMINATED) {
            throw new ApiException(PlatformErrorCode.INVALID_STATE, "The employee is terminated.");
        }
        MergePatch patch = MergePatch.of(document, PATCHABLE);
        var position = patch.uuid("positionId", false);
        var manager = patch.uuid("managerEmployeeId", false);
        var type = patch.text(
                "employmentType",
                true,
                20,
                t -> EMPLOYMENT_TYPES.contains(t) ? null : "must be one of " + String.join(", ", EMPLOYMENT_TYPES));
        var fte = patch.decimal("fte", MIN_FTE, BigDecimal.ONE, FTE_SCALE);
        var effectiveTo = patch.date("effectiveTo", false);
        LocalDate newTo = effectiveTo.orElse(current.effectiveTo());
        if (newTo != null && newTo.isBefore(current.effectiveFrom())) {
            patch.reject("effectiveTo", "INVALID_VALUE", "must not be before effectiveFrom");
        }
        patch.throwIfInvalid();

        EffectivePeriod period = new EffectivePeriod(current.effectiveFrom(), newTo);
        UUID newPosition = position.orElse(current.positionId());
        UUID newManager = manager.orElse(current.managerEmployeeId());
        List<FieldViolation> violations = new ArrayList<>();
        boolean extendsIntoPresent = !Objects.equals(newTo, current.effectiveTo())
                && (newTo == null || !newTo.isBefore(calendar.today(companyId)));
        if (extendsIntoPresent) {
            // A past assignment brought back to the present needs units that are still active.
            validateUnits(companyId, current.branchId(), current.departmentId(), newPosition, violations);
        } else if (newPosition != null && !newPosition.equals(current.positionId())) {
            validatePosition(companyId, newPosition, current.departmentId(), violations);
        }
        boolean lineChanged = !Objects.equals(newManager, current.managerEmployeeId())
                || !Objects.equals(newTo, current.effectiveTo());
        if (newManager != null && lineChanged) {
            validateManager(companyId, employeeId, newManager, period, violations);
        }
        if (!violations.isEmpty()) {
            throw ApiException.validationFailed("The assignment is invalid.", violations);
        }
        if (!Objects.equals(newTo, current.effectiveTo())) {
            requireNoOverlap(companyId, employeeId, period, assignmentId);
        }
        if (!assignments.update(
                companyId,
                assignmentId,
                current.version(),
                CurrentContext.requireActor().userId(),
                newPosition,
                newManager,
                type.orElse(current.employmentType()),
                fte.orElse(current.fte()),
                newTo)) {
            throw new ApiException(PlatformErrorCode.VERSION_CONFLICT, "The assignment was modified concurrently.");
        }
        AssignmentView after =
                assignments.find(companyId, employeeId, assignmentId).orElseThrow();
        audit.record(AuditEvent.builder("UPDATE", "hr")
                .entity("employment_assignment", assignmentId, employee.employeeNumber())
                .change("positionId", current.positionId(), after.positionId())
                .change("managerEmployeeId", current.managerEmployeeId(), after.managerEmployeeId())
                .change("employmentType", current.employmentType(), after.employmentType())
                .change("fte", current.fte().toPlainString(), after.fte().toPlainString())
                .change(
                        "effectiveTo",
                        Objects.toString(current.effectiveTo(), null),
                        Objects.toString(after.effectiveTo(), null))
                .build());
        return after;
    }

    /** Only assignments that have not started yet may be deleted; started ones are ended instead. */
    @Transactional
    public void delete(UUID employeeId, UUID assignmentId, @Nullable String ifMatch) {
        UUID companyId = CurrentContext.requireCompany();
        EmployeeView employee = lockEmployee(companyId, employeeId);
        AssignmentView current = visibleAssignment(companyId, employeeId, assignmentId);
        EntityTags.requireMatch(ifMatch, current.version());
        if (!current.effectiveFrom().isAfter(calendar.today(companyId))) {
            throw new ApiException(
                    PlatformErrorCode.INVALID_STATE,
                    "The assignment has started; end it by setting effectiveTo instead of deleting it.");
        }
        if (!assignments.delete(companyId, assignmentId, current.version())) {
            throw new ApiException(PlatformErrorCode.VERSION_CONFLICT, "The assignment was modified concurrently.");
        }
        audit.record(AuditEvent.builder("DELETE", "hr")
                .entity("employment_assignment", assignmentId, employee.employeeNumber())
                .detail("effectiveFrom", current.effectiveFrom().toString())
                .build());
    }

    /** Ends an assignment at the termination date (called by the termination flow). */
    void endAt(EmployeeView employee, AssignmentView assignment, LocalDate end) {
        if (!assignments.update(
                employee.companyId(),
                assignment.id(),
                assignment.version(),
                CurrentContext.requireActor().userId(),
                assignment.positionId(),
                assignment.managerEmployeeId(),
                assignment.employmentType(),
                assignment.fte(),
                end)) {
            throw new ApiException(PlatformErrorCode.VERSION_CONFLICT, "The assignment was modified concurrently.");
        }
        audit.record(AuditEvent.builder("UPDATE", "hr")
                .entity("employment_assignment", assignment.id(), employee.employeeNumber())
                .change("effectiveTo", Objects.toString(assignment.effectiveTo(), null), end.toString())
                .detail("reason", "TERMINATION")
                .build());
    }

    private EmployeeView lockEmployee(UUID companyId, UUID employeeId) {
        return employees
                .lockForChange(companyId, employeeId, CurrentContext.require().branchScope(), calendar.today(companyId))
                .orElseThrow(ApiException::notFound);
    }

    private AssignmentView visibleAssignment(UUID companyId, UUID employeeId, UUID assignmentId) {
        RequestContext context = CurrentContext.require();
        return assignments
                .find(companyId, employeeId, assignmentId)
                .filter(a -> context.canSeeBranch(a.branchId()))
                .orElseThrow(ApiException::notFound);
    }

    private void validateUnits(
            UUID companyId,
            UUID branchId,
            UUID departmentId,
            @Nullable UUID positionId,
            List<FieldViolation> violations) {
        RequestContext context = CurrentContext.require();
        Optional<BranchSummary> branch =
                context.canSeeBranch(branchId) ? org.branchForUse(companyId, branchId) : Optional.empty();
        if (branch.isEmpty()) {
            violations.add(FieldViolation.atPointer("/branchId", "UNKNOWN_BRANCH", "is not a branch of the company"));
        } else if (!branch.get().active()) {
            violations.add(FieldViolation.atPointer("/branchId", "INACTIVE", "must be an active branch"));
        }
        Optional<DepartmentSummary> department = org.departmentForUse(companyId, departmentId);
        if (department.isEmpty()) {
            violations.add(FieldViolation.atPointer(
                    "/departmentId", "UNKNOWN_DEPARTMENT", "is not a department of the company"));
        } else if (!department.get().active()) {
            violations.add(FieldViolation.atPointer("/departmentId", "INACTIVE", "must be an active department"));
        } else if (department.get().branchId() != null
                && !department.get().branchId().equals(branchId)) {
            violations.add(FieldViolation.atPointer(
                    "/departmentId", "DEPARTMENT_BRANCH_MISMATCH", "belongs to another branch"));
        }
        if (positionId != null) {
            validatePosition(companyId, positionId, departmentId, violations);
        }
    }

    private void validatePosition(UUID companyId, UUID positionId, UUID departmentId, List<FieldViolation> violations) {
        Optional<PositionView> position = positions.lockForUse(companyId, positionId);
        if (position.isEmpty()) {
            violations.add(
                    FieldViolation.atPointer("/positionId", "UNKNOWN_POSITION", "is not a position of the company"));
        } else if (!position.get().active()) {
            violations.add(FieldViolation.atPointer("/positionId", "INACTIVE", "must be an active position"));
        } else if (position.get().departmentId() != null
                && !position.get().departmentId().equals(departmentId)) {
            violations.add(FieldViolation.atPointer(
                    "/positionId", "POSITION_DEPARTMENT_MISMATCH", "belongs to another department"));
        }
    }

    private void validateManager(
            UUID companyId, UUID employeeId, UUID managerId, EffectivePeriod period, List<FieldViolation> violations) {
        String pointer = "/managerEmployeeId";
        if (managerId.equals(employeeId)) {
            violations.add(FieldViolation.atPointer(pointer, "SELF", "must not be the employee"));
            return;
        }
        Optional<EmployeeView> manager = employees.lockForReference(companyId, managerId);
        if (manager.isEmpty()) {
            violations.add(FieldViolation.atPointer(pointer, "UNKNOWN_EMPLOYEE", "is not an employee of the company"));
            return;
        }
        if (manager.get().hireDate().isAfter(period.from())) {
            violations.add(FieldViolation.atPointer(
                    pointer, "MANAGER_NOT_EMPLOYED", "must be employed from the start of the assignment"));
            return;
        }
        LocalDate leaves = manager.get().terminationDate();
        if (leaves != null && (period.to() == null || period.to().isAfter(leaves))) {
            violations.add(FieldViolation.atPointer(
                    pointer, "MANAGER_TERMINATED", "leaves the company before the assignment ends"));
            return;
        }
        assignments.lockReportingLines(companyId);
        ReportingLines.Result result = ReportingLines.check(
                employeeId, managerId, period, (e, p) -> assignments.managerSpans(companyId, e, p));
        if (result == ReportingLines.Result.CYCLE) {
            violations.add(FieldViolation.atPointer(
                    pointer, "REPORTING_CYCLE", "would make the employee report to themselves through the chain"));
        } else if (result == ReportingLines.Result.TOO_DEEP) {
            violations.add(FieldViolation.atPointer(
                    pointer,
                    "REPORTING_CHAIN_TOO_DEEP",
                    "has a reporting chain longer than " + ReportingLines.MAX_DEPTH + " levels"));
        }
    }

    private void requireNoOverlap(UUID companyId, UUID employeeId, EffectivePeriod period, @Nullable UUID excludeId) {
        if (!assignments.overlapping(companyId, employeeId, period, excludeId).isEmpty()) {
            throw new ApiException(
                    HrErrorCode.ASSIGNMENT_OVERLAP,
                    "The employee already has an assignment in this period; end it first.");
        }
    }
}
