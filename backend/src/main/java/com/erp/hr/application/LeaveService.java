package com.erp.hr.application;

import com.erp.hr.domain.EmployeeStatus;
import com.erp.hr.domain.LeaveRequestStatus;
import com.erp.hr.domain.WorkCalendar;
import com.erp.hr.persistence.EmployeeRepository;
import com.erp.hr.persistence.EmploymentAssignmentRepository;
import com.erp.hr.persistence.LeaveLedgerRepository;
import com.erp.hr.persistence.LeaveRequestRepository;
import com.erp.hr.persistence.LeaveTypeRepository;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditPort;
import com.erp.platform.context.CurrentContext;
import com.erp.platform.web.ApiException;
import com.erp.platform.web.EntityTags;
import com.erp.platform.web.FieldViolation;
import com.erp.platform.web.PlatformErrorCode;
import com.erp.platform.web.paging.ListQuery;
import com.erp.platform.web.paging.PageResponse;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Leave requests and balances (PRODUCT_SPEC.md §10.1, HR-2). Days are counted in working days of the
 * employee's branch (weekends and public holidays excluded); a half day books 0.5 of a single working
 * day. A request is booked in the leave year (calendar year) it falls in.
 *
 * <ul>
 *   <li>Submitting needs the available balance (ledger balance less other submitted days), approving
 *       the ledger balance, unless the type allows a negative balance. Submitted and approved leave of
 *       one employee never overlaps (exclusion constraint).
 *   <li>HR approves with {@code hr.leave.approve}; a manager approves the requests of their direct and
 *       indirect reports. Nobody decides their own request ({@code 403 SOD_VIOLATION}).
 *   <li>Approval appends a {@code TAKEN} entry; cancelling approved leave gives the days back with an
 *       {@code ADJUSTMENT}. Approved leave covering today moves an active employee to {@code ON_LEAVE}
 *       and back ({@link LeaveStatusSync} does the same daily).
 * </ul>
 *
 * Operations on one employee's leave serialize on the employee row.
 */
@Service
public class LeaveService {

    /** Who acts: HR in the branch scope, the employee themself, or their manager. */
    public enum Access {
        HR,
        OWN,
        TEAM
    }

    private static final BigDecimal HALF = new BigDecimal("0.5");

    private final LeaveRequestRepository requests;
    private final LeaveLedgerRepository ledger;
    private final LeaveTypeRepository types;
    private final EmployeeRepository employees;
    private final EmploymentAssignmentRepository assignments;
    private final WorkCalendarService calendars;
    private final TeamService team;
    private final LeaveStatusSync status;
    private final HrContext context;
    private final AuditPort audit;

    LeaveService(
            LeaveRequestRepository requests,
            LeaveLedgerRepository ledger,
            LeaveTypeRepository types,
            EmployeeRepository employees,
            EmploymentAssignmentRepository assignments,
            WorkCalendarService calendars,
            TeamService team,
            LeaveStatusSync status,
            HrContext context,
            AuditPort audit) {
        this.requests = requests;
        this.ledger = ledger;
        this.types = types;
        this.employees = employees;
        this.assignments = assignments;
        this.calendars = calendars;
        this.team = team;
        this.status = status;
        this.context = context;
        this.audit = audit;
    }

    // ------------------------------------------------------------------------------ queries

    @Transactional(readOnly = true)
    public PageResponse<HrViews.LeaveRequest> list(Access access, ListQuery query) {
        UUID companyId = context.companyId();
        LocalDate today = context.today();
        return switch (access) {
            case HR -> requests.list(companyId, CurrentContext.require().branchScope(), today, null, query);
            case OWN -> requests.list(companyId, null, today, List.of(team.me().id()), query);
            case TEAM ->
                requests.list(
                        companyId,
                        null,
                        today,
                        team.reportsOf(companyId, team.me().id(), today),
                        query);
        };
    }

    @Transactional(readOnly = true)
    public HrViews.LeaveRequest get(Access access, UUID id) {
        HrViews.LeaveRequest request = requests.find(context.companyId(), id).orElseThrow(ApiException::notFound);
        requireAccess(access, request.employeeId());
        return request;
    }

    /** Balances of every leave type the employee has entries for or that is active, in the year. */
    @Transactional(readOnly = true)
    public List<HrViews.Balance> balances(Access access, @Nullable UUID employeeId, int year) {
        UUID companyId = context.companyId();
        UUID employee = access == Access.OWN ? team.me().id() : employeeId;
        if (employee == null) {
            throw ApiException.validationFailed(
                    "The employee is required.",
                    List.of(FieldViolation.atParameter("employeeId", "REQUIRED", "is required")));
        }
        requireAccess(access, employee);
        Map<UUID, Map<String, BigDecimal>> totals = ledger.totals(companyId, employee, year);
        Map<UUID, BigDecimal> pending = requests.pendingByType(companyId, employee, year);
        List<HrViews.Balance> result = new ArrayList<>();
        for (HrViews.LeaveType type : types.all(companyId)) {
            Map<String, BigDecimal> t = totals.getOrDefault(type.id(), Map.of());
            if (!type.active() && t.isEmpty()) {
                continue;
            }
            BigDecimal accrued = t.getOrDefault("ACCRUAL", BigDecimal.ZERO);
            BigDecimal carried = t.getOrDefault("CARRY_FORWARD", BigDecimal.ZERO);
            BigDecimal taken = t.getOrDefault("TAKEN", BigDecimal.ZERO).negate();
            BigDecimal adjusted = t.getOrDefault("ADJUSTMENT", BigDecimal.ZERO);
            BigDecimal expired = t.getOrDefault("EXPIRY", BigDecimal.ZERO).negate();
            BigDecimal balance =
                    accrued.add(carried).subtract(taken).add(adjusted).subtract(expired);
            BigDecimal waiting = pending.getOrDefault(type.id(), BigDecimal.ZERO);
            result.add(new HrViews.Balance(
                    type.id(),
                    type.code(),
                    type.name(),
                    year,
                    accrued,
                    carried,
                    taken,
                    adjusted,
                    expired,
                    balance,
                    waiting,
                    balance.subtract(waiting)));
        }
        return result;
    }

    @Transactional(readOnly = true)
    public PageResponse<HrViews.LedgerEntry> ledger(Access access, @Nullable UUID employeeId, ListQuery query) {
        UUID employee = access == Access.OWN ? team.me().id() : employeeId;
        if (employee != null) {
            requireAccess(access, employee);
        } else if (CurrentContext.require().branchScope() != null) {
            throw ApiException.validationFailed(
                    "The employee is required.",
                    List.of(FieldViolation.atParameter(
                            "employeeId", "REQUIRED", "is required for users restricted to some branches")));
        }
        return ledger.list(context.companyId(), employee, query);
    }

    // ----------------------------------------------------------------------------- commands

    @Transactional
    public HrViews.LeaveRequest create(Access access, HrCommands.LeaveRequest command) {
        UUID companyId = context.companyId();
        UUID employeeId = access == Access.OWN ? team.me().id() : command.employeeId();
        EmployeeView employee = lockEmployee(access, employeeId);
        Booking booking = booking(employee, command);
        UUID id = requests.insert(
                companyId,
                employeeId,
                command.leaveTypeId(),
                command.startDate(),
                command.endDate(),
                booking.days(),
                reason(command.reason()),
                context.actor());
        audit.record(AuditEvent.builder("CREATE", "hr")
                .entity("leave_request", id, employee.employeeNumber())
                .detail("leaveTypeId", command.leaveTypeId())
                .detail("startDate", command.startDate().toString())
                .detail("endDate", command.endDate().toString())
                .detail("days", booking.days().toPlainString())
                .build());
        return requests.find(companyId, id).orElseThrow();
    }

    @Transactional
    public HrViews.LeaveRequest update(
            Access access, UUID id, @Nullable String ifMatch, HrCommands.LeaveRequest command) {
        UUID companyId = context.companyId();
        HrViews.LeaveRequest current = lockRequest(access, id, ifMatch, LeaveRequestStatus.Action.EDIT);
        EmployeeView employee = lockEmployee(access, current.employeeId());
        Booking booking = booking(employee, command);
        if (!requests.updateDraft(
                companyId,
                id,
                current.version(),
                context.actor(),
                command.leaveTypeId(),
                command.startDate(),
                command.endDate(),
                booking.days(),
                reason(command.reason()))) {
            throw new ApiException(PlatformErrorCode.VERSION_CONFLICT, "The leave request was modified concurrently.");
        }
        audit.record(AuditEvent.builder("UPDATE", "hr")
                .entity("leave_request", id, employee.employeeNumber())
                .change(
                        "startDate",
                        current.startDate().toString(),
                        command.startDate().toString())
                .change(
                        "endDate",
                        current.endDate().toString(),
                        command.endDate().toString())
                .change("days", current.days().toPlainString(), booking.days().toPlainString())
                .build());
        return requests.find(companyId, id).orElseThrow();
    }

    @Transactional
    public void delete(Access access, UUID id, @Nullable String ifMatch) {
        HrViews.LeaveRequest current = lockRequest(access, id, ifMatch, LeaveRequestStatus.Action.EDIT);
        requests.delete(context.companyId(), id);
        audit.record(AuditEvent.builder("DELETE", "hr")
                .entity("leave_request", id, null)
                .detail("employeeId", current.employeeId())
                .build());
    }

    @Transactional
    public HrViews.LeaveRequest submit(Access access, UUID id, @Nullable String ifMatch) {
        UUID companyId = context.companyId();
        HrViews.LeaveRequest current = lockRequest(access, id, ifMatch, LeaveRequestStatus.Action.SUBMIT);
        lockEmployee(access, current.employeeId());
        HrViews.LeaveType type =
                types.lockForUse(companyId, current.leaveTypeId()).orElseThrow();
        if (!type.active()) {
            throw invalid("/leaveTypeId", "INACTIVE", "is no longer active");
        }
        int year = current.startDate().getYear();
        if (!type.allowNegativeBalance()) {
            BigDecimal available = ledger.balance(companyId, current.employeeId(), type.id(), year)
                    .subtract(requests.pendingDays(companyId, current.employeeId(), type.id(), year, id));
            if (available.compareTo(current.days()) < 0) {
                throw insufficient(available, current.days());
            }
        }
        return transition(current, LeaveRequestStatus.Action.SUBMIT, null);
    }

    @Transactional
    public HrViews.LeaveRequest approve(Access access, UUID id, @Nullable String ifMatch, @Nullable String note) {
        UUID companyId = context.companyId();
        HrViews.LeaveRequest current = lockRequest(access, id, ifMatch, LeaveRequestStatus.Action.APPROVE);
        requireNotOwnDecision(current);
        EmployeeView employee = lockEmployee(access, current.employeeId());
        HrViews.LeaveType type =
                types.lockForUse(companyId, current.leaveTypeId()).orElseThrow();
        int year = current.startDate().getYear();
        if (!type.allowNegativeBalance()) {
            BigDecimal balance = ledger.balance(companyId, current.employeeId(), type.id(), year);
            if (balance.compareTo(current.days()) < 0) {
                throw insufficient(balance, current.days());
            }
        }
        HrViews.LeaveRequest approved = transition(current, LeaveRequestStatus.Action.APPROVE, note);
        ledger.append(
                companyId,
                current.employeeId(),
                type.id(),
                year,
                null,
                "TAKEN",
                current.days().negate(),
                id,
                null,
                context.actor());
        status.sync(employee, context.today());
        return approved;
    }

    @Transactional
    public HrViews.LeaveRequest reject(Access access, UUID id, @Nullable String ifMatch, @Nullable String note) {
        HrViews.LeaveRequest current = lockRequest(access, id, ifMatch, LeaveRequestStatus.Action.REJECT);
        requireNotOwnDecision(current);
        return transition(current, LeaveRequestStatus.Action.REJECT, note);
    }

    /**
     * Cancels a request. An employee cancels their own approved leave only before it starts; HR cancels
     * any. Approved days are given back.
     */
    @Transactional
    public HrViews.LeaveRequest cancel(Access access, UUID id, @Nullable String ifMatch, @Nullable String note) {
        HrViews.LeaveRequest current = lockRequest(access, id, ifMatch, LeaveRequestStatus.Action.CANCEL);
        if (access == Access.OWN
                && current.status() == LeaveRequestStatus.APPROVED
                && !current.startDate().isAfter(context.today())) {
            throw new ApiException(
                    PlatformErrorCode.INVALID_STATE, "Approved leave that has started is cancelled by HR.");
        }
        EmployeeView employee = lockEmployee(access, current.employeeId());
        return cancel(employee, current, note);
    }

    /** Leave after the termination date is cancelled and its approved days given back (termination). */
    @Transactional
    public void cancelAfterTermination(EmployeeView employee, LocalDate terminationDate) {
        UUID companyId = employee.companyId();
        for (HrViews.LeaveRequest r : requests.openAfter(companyId, employee.id(), terminationDate)) {
            if (r.startDate().isAfter(terminationDate)) {
                cancel(employee, r, "Employment ended " + terminationDate);
            }
        }
    }

    /** A manual correction of the balance (hr.leave.adjust). */
    @Transactional
    public HrViews.Balance adjust(UUID employeeId, UUID leaveTypeId, int year, BigDecimal days, String note) {
        UUID companyId = context.companyId();
        EmployeeView employee = lockEmployee(Access.HR, employeeId);
        HrViews.LeaveType type = types.lockForUse(companyId, leaveTypeId)
                .orElseThrow(() -> invalid("/leaveTypeId", "UNKNOWN_LEAVE_TYPE", "is not a leave type"));
        if (days.signum() == 0 || days.abs().compareTo(BigDecimal.valueOf(366)) > 0 || days.scale() > 2) {
            throw invalid("/days", "INVALID_VALUE", "must be non-zero, at most 366 days, with two decimals");
        }
        ledger.append(
                companyId, employeeId, type.id(), year, null, "ADJUSTMENT", days, null, note.strip(), context.actor());
        audit.record(AuditEvent.builder("ADJUST", "hr")
                .entity("leave_balance", employeeId, employee.employeeNumber())
                .detail("leaveType", type.code())
                .detail("year", year)
                .detail("days", days.toPlainString())
                .detail("note", note.strip())
                .build());
        return balances(Access.HR, employeeId, year).stream()
                .filter(b -> b.leaveTypeId().equals(leaveTypeId))
                .findFirst()
                .orElseThrow();
    }

    // ------------------------------------------------------------------------------ helpers

    private HrViews.LeaveRequest cancel(EmployeeView employee, HrViews.LeaveRequest current, @Nullable String note) {
        boolean wasApproved = current.status() == LeaveRequestStatus.APPROVED;
        HrViews.LeaveRequest cancelled = transition(current, LeaveRequestStatus.Action.CANCEL, note);
        if (wasApproved) {
            ledger.append(
                    employee.companyId(),
                    current.employeeId(),
                    current.leaveTypeId(),
                    current.startDate().getYear(),
                    null,
                    "ADJUSTMENT",
                    current.days(),
                    current.id(),
                    "Cancelled leave request",
                    context.actor());
            status.sync(
                    employees
                            .lockForChange(employee.companyId(), employee.id(), null, context.today())
                            .orElseThrow(),
                    context.today());
        }
        return cancelled;
    }

    private record Booking(BigDecimal days) {}

    /** Validates the request against the employee and counts its working days. */
    private Booking booking(EmployeeView employee, HrCommands.LeaveRequest c) {
        UUID companyId = employee.companyId();
        List<FieldViolation> violations = new ArrayList<>();
        HrViews.LeaveType type = types.lockForUse(companyId, c.leaveTypeId()).orElse(null);
        if (type == null || !type.active()) {
            violations.add(
                    FieldViolation.atPointer("/leaveTypeId", "UNKNOWN_LEAVE_TYPE", "must be an active leave type"));
        }
        if (c.endDate().isBefore(c.startDate())) {
            violations.add(FieldViolation.atPointer("/endDate", "BEFORE_START", "must not be before the start date"));
        } else if (c.startDate().getYear() != c.endDate().getYear()) {
            violations.add(FieldViolation.atPointer(
                    "/endDate",
                    "CROSSES_LEAVE_YEAR",
                    "must be in the leave year of the start date; split the request"));
        }
        if (c.startDate().isBefore(employee.hireDate())) {
            violations.add(FieldViolation.atPointer("/startDate", "BEFORE_HIRE", "must not be before the hire date"));
        }
        if (c.halfDay() && !c.startDate().equals(c.endDate())) {
            violations.add(FieldViolation.atPointer("/halfDay", "INVALID_VALUE", "needs the same start and end date"));
        }
        if (!violations.isEmpty()) {
            throw ApiException.validationFailed("The leave request is invalid.", violations);
        }
        AssignmentView assignment = assignments
                .effectiveOn(companyId, List.of(employee.id()), c.startDate())
                .get(employee.id());
        WorkCalendar calendar = calendars.calendar(
                companyId, assignment == null ? null : assignment.branchId(), c.startDate(), c.endDate());
        int working = calendar.workingDays(c.startDate(), c.endDate());
        if (working == 0) {
            throw new ApiException(
                    HrErrorCode.NO_WORKING_DAYS,
                    "The leave from " + c.startDate() + " to " + c.endDate() + " has no working days.",
                    List.of(FieldViolation.atPointer(
                            "/startDate", "NO_WORKING_DAYS", "covers weekends and holidays only")));
        }
        return new Booking(c.halfDay() ? HALF : BigDecimal.valueOf(working));
    }

    private HrViews.LeaveRequest lockRequest(
            Access access, UUID id, @Nullable String ifMatch, LeaveRequestStatus.Action action) {
        HrViews.LeaveRequest current = requests.lock(context.companyId(), id).orElseThrow(ApiException::notFound);
        requireAccess(access, current.employeeId());
        EntityTags.requireMatch(ifMatch, current.version());
        if (!current.status().allows(action)) {
            throw new ApiException(
                    PlatformErrorCode.INVALID_STATE,
                    "A " + current.status() + " leave request cannot be "
                            + action.name()
                                    .toLowerCase(Locale.ROOT)
                                    .replace("edit", "edited")
                                    .replace("submit", "submitted")
                                    .replace("approve", "approved")
                                    .replace("reject", "rejected")
                                    .replace("cancel", "cancelled")
                            + ".");
        }
        return current;
    }

    /** HR: within the branch scope; own: the caller's employee; team: one of the caller's reports. */
    private void requireAccess(Access access, UUID employeeId) {
        UUID companyId = context.companyId();
        LocalDate today = context.today();
        boolean allowed = switch (access) {
            case HR ->
                employees
                        .find(companyId, employeeId, CurrentContext.require().branchScope(), today)
                        .isPresent();
            case OWN -> team.me().id().equals(employeeId);
            case TEAM -> team.manages(companyId, team.me().id(), employeeId, today);
        };
        if (!allowed) {
            throw ApiException.notFound();
        }
    }

    private EmployeeView lockEmployee(Access access, UUID employeeId) {
        UUID companyId = context.companyId();
        requireAccess(access, employeeId);
        EmployeeView employee = employees
                .lockForChange(companyId, employeeId, null, context.today())
                .orElseThrow(ApiException::notFound);
        if (employee.status() == EmployeeStatus.TERMINATED) {
            throw new ApiException(PlatformErrorCode.INVALID_STATE, "The employee is terminated.");
        }
        return employee;
    }

    /** Nobody decides their own leave. */
    private void requireNotOwnDecision(HrViews.LeaveRequest request) {
        employees
                .findByUser(context.companyId(), context.actor())
                .filter(e -> e.id().equals(request.employeeId()))
                .ifPresent(e -> {
                    throw new ApiException(
                            PlatformErrorCode.SOD_VIOLATION, "You cannot approve or reject your own leave request.");
                });
    }

    private HrViews.LeaveRequest transition(
            HrViews.LeaveRequest current, LeaveRequestStatus.Action action, @Nullable String note) {
        LeaveRequestStatus target = current.status().apply(action);
        boolean decision = action == LeaveRequestStatus.Action.APPROVE || action == LeaveRequestStatus.Action.REJECT;
        if (!requests.transition(
                context.companyId(),
                current.id(),
                current.version(),
                context.actor(),
                target,
                decision,
                reason(note))) {
            throw new ApiException(PlatformErrorCode.VERSION_CONFLICT, "The leave request was modified concurrently.");
        }
        AuditEvent.Builder event = AuditEvent.builder("STATE_CHANGE", "hr")
                .entity("leave_request", current.id(), null)
                .transition(current.status().name(), target.name())
                .detail("employeeId", current.employeeId())
                .detail("days", current.days().toPlainString());
        if (note != null && !note.isBlank()) {
            event.detail("note", note.strip());
        }
        audit.record(event.build());
        return requests.find(context.companyId(), current.id()).orElseThrow();
    }

    private static @Nullable String reason(@Nullable String text) {
        return text == null || text.isBlank() ? null : text.strip();
    }

    private static ApiException invalid(String pointer, String code, String message) {
        return ApiException.validationFailed(
                "The leave request is invalid.", List.of(FieldViolation.atPointer(pointer, code, message)));
    }

    private static ApiException insufficient(BigDecimal available, BigDecimal requested) {
        return new ApiException(
                HrErrorCode.LEAVE_BALANCE_INSUFFICIENT,
                "The leave needs " + requested.toPlainString() + " days; " + available.toPlainString()
                        + " are available.");
    }
}
