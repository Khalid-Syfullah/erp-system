package com.erp.hr.application;

import com.erp.hr.domain.EmployeeStatus;
import com.erp.hr.persistence.DepartmentHeadRepository;
import com.erp.hr.persistence.EmployeeRepository;
import com.erp.hr.persistence.EmploymentAssignmentRepository;
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
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * Core employee records and their lifecycle (PRODUCT_SPEC.md §10.1). A terminated employee is
 * read-only. Termination ends the employee's current assignment and department headships at the
 * termination date; it is refused while assignments or headships start after that date or other
 * employees still report to them afterwards. (User deactivation and the payroll event follow with
 * the user link in Phase 9.)
 *
 * <p>Branch-restricted users see employees whose current assignment is in their branches, and must
 * create new employees together with an initial assignment in one of them.
 */
@Service
public class EmployeeService {

    static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    static final Set<String> PATCHABLE = Set.of("firstName", "lastName", "preferredName", "workEmail", "hireDate");

    /** An employee with the assignment effective on the company's business date. */
    public record Detail(EmployeeView employee, @Nullable AssignmentView currentAssignment) {}

    private final EmployeeRepository employees;
    private final EmploymentAssignmentRepository assignmentRecords;
    private final EmploymentAssignmentService assignments;
    private final DepartmentHeadRepository heads;
    private final HrCalendar calendar;
    private final AuditPort audit;

    EmployeeService(
            EmployeeRepository employees,
            EmploymentAssignmentRepository assignmentRecords,
            EmploymentAssignmentService assignments,
            DepartmentHeadRepository heads,
            HrCalendar calendar,
            AuditPort audit) {
        this.employees = employees;
        this.assignmentRecords = assignmentRecords;
        this.assignments = assignments;
        this.heads = heads;
        this.calendar = calendar;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public PageResponse<Detail> list(ListQuery query) {
        UUID companyId = CurrentContext.requireCompany();
        LocalDate today = calendar.today(companyId);
        PageResponse<EmployeeView> page =
                employees.list(companyId, CurrentContext.require().branchScope(), today, query);
        Map<UUID, AssignmentView> current = assignmentRecords.effectiveOn(
                companyId, page.data().stream().map(EmployeeView::id).toList(), today);
        return page.map(e -> new Detail(e, current.get(e.id())));
    }

    @Transactional(readOnly = true)
    public Detail get(UUID employeeId) {
        UUID companyId = CurrentContext.requireCompany();
        LocalDate today = calendar.today(companyId);
        EmployeeView employee = employees
                .find(companyId, employeeId, CurrentContext.require().branchScope(), today)
                .orElseThrow(ApiException::notFound);
        return new Detail(
                employee,
                assignmentRecords
                        .effectiveOn(companyId, List.of(employeeId), today)
                        .get(employeeId));
    }

    @Transactional
    public Detail create(HrCommands.Employee command, HrCommands.@Nullable Assignment initialAssignment) {
        UUID companyId = CurrentContext.requireCompany();
        if (CurrentContext.require().branchScope() != null && initialAssignment == null) {
            throw new ApiException(
                    PlatformErrorCode.FORBIDDEN,
                    "Users restricted to some branches must create employees with an initial assignment in one of"
                            + " their branches.");
        }
        HrCommands.Employee normalized = normalize(command);
        if (normalized.workEmail() != null
                && !EMAIL.matcher(normalized.workEmail()).matches()) {
            throw ApiException.validationFailed(
                    "The employee is invalid.",
                    List.of(FieldViolation.atPointer("/workEmail", "INVALID_VALUE", "must be an email address")));
        }
        UUID id = employees.insert(
                companyId, normalized, CurrentContext.requireActor().userId());
        audit.record(AuditEvent.builder("CREATE", "hr")
                .entity("employee", id, normalized.employeeNumber())
                .detail("hireDate", normalized.hireDate().toString())
                .build());
        EmployeeView created = employees
                .lockForChange(companyId, id, null, calendar.today(companyId))
                .orElseThrow();
        if (initialAssignment != null) {
            assignments.createFor(created, initialAssignment);
        }
        return new Detail(
                created,
                assignmentRecords
                        .effectiveOn(companyId, List.of(id), calendar.today(companyId))
                        .get(id));
    }

    @Transactional
    public Detail patch(UUID employeeId, @Nullable String ifMatch, JsonNode document) {
        UUID companyId = CurrentContext.requireCompany();
        EmployeeView current = lock(companyId, employeeId);
        EntityTags.requireMatch(ifMatch, current.version());
        requireNotTerminated(current);
        MergePatch patch = MergePatch.of(document, PATCHABLE);
        var firstName = patch.text("firstName", true, 100);
        var lastName = patch.text("lastName", true, 100);
        var preferredName = patch.text("preferredName", false, 100);
        var workEmail = patch.text(
                "workEmail", false, 254, e -> EMAIL.matcher(e).matches() ? null : "must be an email address");
        var hireDate = patch.date("hireDate", true);
        patch.throwIfInvalid();

        LocalDate newHire = hireDate.orElse(current.hireDate());
        if (!newHire.equals(current.hireDate())) {
            Optional<LocalDate> firstAssignment = assignmentRecords.forEmployee(companyId, employeeId).stream()
                    .map(AssignmentView::effectiveFrom)
                    .min(LocalDate::compareTo);
            Optional<LocalDate> firstHeadship = heads.earliestStart(companyId, employeeId);
            if (firstAssignment.filter(newHire::isAfter).isPresent()
                    || firstHeadship.filter(newHire::isAfter).isPresent()) {
                throw ApiException.validationFailed(
                        "The employee is invalid.",
                        List.of(FieldViolation.atPointer(
                                "/hireDate",
                                "AFTER_FIRST_ASSIGNMENT",
                                "must not be after the start of the employee's first assignment or headship")));
            }
        }
        HrCommands.Employee next = normalize(new HrCommands.Employee(
                current.employeeNumber(),
                firstName.orElse(current.firstName()),
                lastName.orElse(current.lastName()),
                preferredName.orElse(current.preferredName()),
                workEmail.orElse(current.workEmail()),
                newHire));
        if (!employees.updateProfile(
                companyId,
                employeeId,
                current.version(),
                CurrentContext.requireActor().userId(),
                next)) {
            throw new ApiException(PlatformErrorCode.VERSION_CONFLICT, "The employee was modified concurrently.");
        }
        Detail after = get(employeeId);
        EmployeeView e = after.employee();
        audit.record(AuditEvent.builder("UPDATE", "hr")
                .entity("employee", employeeId, e.employeeNumber())
                .change("firstName", current.firstName(), e.firstName())
                .change("lastName", current.lastName(), e.lastName())
                .change("preferredName", current.preferredName(), e.preferredName())
                .change("workEmail", current.workEmail(), e.workEmail())
                .change("hireDate", current.hireDate().toString(), e.hireDate().toString())
                .build());
        return after;
    }

    /** {@code ONBOARDING → ACTIVE} and {@code ON_LEAVE → ACTIVE}. */
    @Transactional
    public Detail activate(UUID employeeId, @Nullable String ifMatch) {
        UUID companyId = CurrentContext.requireCompany();
        EmployeeView current = lock(companyId, employeeId);
        EntityTags.requireMatch(ifMatch, current.version());
        transition(current, EmployeeStatus.ACTIVE, null, null);
        return get(employeeId);
    }

    @Transactional
    public Detail terminate(
            UUID employeeId, @Nullable String ifMatch, LocalDate terminationDate, @Nullable String reason) {
        UUID companyId = CurrentContext.requireCompany();
        EmployeeView current = lock(companyId, employeeId);
        EntityTags.requireMatch(ifMatch, current.version());
        if (!current.status().canTransitionTo(EmployeeStatus.TERMINATED)) {
            throw new ApiException(PlatformErrorCode.INVALID_STATE, "The employee is already terminated.");
        }
        if (terminationDate.isBefore(current.hireDate())) {
            throw ApiException.validationFailed(
                    "The termination is invalid.",
                    List.of(FieldViolation.atPointer(
                            "/terminationDate", "BEFORE_HIRE", "must not be before the hire date")));
        }
        List<AssignmentView> all = assignmentRecords.forEmployee(companyId, employeeId);
        List<DepartmentHeadView> headships = heads.forEmployeeFrom(companyId, employeeId, terminationDate);
        if (all.stream().anyMatch(a -> a.effectiveFrom().isAfter(terminationDate))
                || headships.stream().anyMatch(h -> h.effectiveFrom().isAfter(terminationDate))) {
            throw new ApiException(
                    PlatformErrorCode.INVALID_STATE,
                    "Assignments or department headships start after the termination date; delete or end them first.");
        }
        int reports = assignmentRecords.countReportsAfter(companyId, employeeId, terminationDate);
        if (reports > 0) {
            throw new ApiException(
                    PlatformErrorCode.INVALID_STATE,
                    reports + " assignment(s) still report to this employee after the termination date;"
                            + " change their manager first.");
        }
        for (AssignmentView a : all) {
            if (a.effectiveTo() == null || a.effectiveTo().isAfter(terminationDate)) {
                assignments.endAt(current, a, terminationDate);
            }
        }
        UUID actor = CurrentContext.requireActor().userId();
        for (DepartmentHeadView h : headships) {
            if (h.effectiveTo() == null || h.effectiveTo().isAfter(terminationDate)) {
                if (!heads.updateEnd(companyId, h.id(), h.version(), actor, terminationDate)) {
                    throw new ApiException(
                            PlatformErrorCode.VERSION_CONFLICT, "A department headship was modified concurrently.");
                }
                audit.record(AuditEvent.builder("UPDATE", "hr")
                        .entity("department_head", h.id(), current.employeeNumber())
                        .change("effectiveTo", Objects.toString(h.effectiveTo(), null), terminationDate.toString())
                        .detail("reason", "TERMINATION")
                        .build());
            }
        }
        transition(current, EmployeeStatus.TERMINATED, terminationDate, reason);
        return get(employeeId);
    }

    private void transition(
            EmployeeView current, EmployeeStatus target, @Nullable LocalDate terminationDate, @Nullable String reason) {
        if (!current.status().canTransitionTo(target)) {
            throw new ApiException(
                    PlatformErrorCode.INVALID_STATE,
                    "An employee in status " + current.status() + " cannot become " + target + ".");
        }
        if (!employees.updateStatus(
                current.companyId(),
                current.id(),
                current.version(),
                CurrentContext.requireActor().userId(),
                target,
                terminationDate,
                reason)) {
            throw new ApiException(PlatformErrorCode.VERSION_CONFLICT, "The employee was modified concurrently.");
        }
        AuditEvent.Builder event = AuditEvent.builder("STATE_CHANGE", "hr")
                .entity("employee", current.id(), current.employeeNumber())
                .transition(current.status().name(), target.name());
        if (terminationDate != null) {
            event.detail("terminationDate", terminationDate.toString());
        }
        audit.record(event.build());
    }

    private EmployeeView lock(UUID companyId, UUID employeeId) {
        return employees
                .lockForChange(companyId, employeeId, CurrentContext.require().branchScope(), calendar.today(companyId))
                .orElseThrow(ApiException::notFound);
    }

    private static void requireNotTerminated(EmployeeView employee) {
        if (employee.status().isTerminal()) {
            throw new ApiException(PlatformErrorCode.INVALID_STATE, "The employee is terminated and read-only.");
        }
    }

    private static HrCommands.Employee normalize(HrCommands.Employee c) {
        return new HrCommands.Employee(
                c.employeeNumber(),
                c.firstName().strip(),
                c.lastName().strip(),
                c.preferredName() == null || c.preferredName().isBlank()
                        ? null
                        : c.preferredName().strip(),
                c.workEmail() == null || c.workEmail().isBlank()
                        ? null
                        : c.workEmail().strip().toLowerCase(java.util.Locale.ROOT),
                c.hireDate());
    }
}
