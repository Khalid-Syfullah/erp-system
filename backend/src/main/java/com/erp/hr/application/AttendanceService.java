package com.erp.hr.application;

import com.erp.hr.domain.AttendanceStatus;
import com.erp.hr.domain.EmployeeStatus;
import com.erp.hr.persistence.AttendanceRepository;
import com.erp.hr.persistence.EmployeeRepository;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditPort;
import com.erp.platform.context.CurrentContext;
import com.erp.platform.web.ApiException;
import com.erp.platform.web.FieldViolation;
import com.erp.platform.web.PlatformErrorCode;
import com.erp.platform.web.paging.ListQuery;
import com.erp.platform.web.paging.PageResponse;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Basic daily attendance (ADR-039): HR records a day's status and times for any employee in scope;
 * employees clock in and out for the business date themselves. Worked minutes follow from the times.
 * Attendance is informational: Payroll does not read it.
 */
@Service
public class AttendanceService {

    private final AttendanceRepository attendance;
    private final EmployeeRepository employees;
    private final TeamService team;
    private final HrContext context;
    private final AuditPort audit;

    AttendanceService(
            AttendanceRepository attendance,
            EmployeeRepository employees,
            TeamService team,
            HrContext context,
            AuditPort audit) {
        this.attendance = attendance;
        this.employees = employees;
        this.team = team;
        this.context = context;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public PageResponse<HrViews.Attendance> list(ListQuery query) {
        return attendance.list(
                context.companyId(), CurrentContext.require().branchScope(), context.today(), null, query);
    }

    @Transactional(readOnly = true)
    public PageResponse<HrViews.Attendance> listOwn(ListQuery query) {
        return attendance.list(
                context.companyId(), null, context.today(), List.of(team.me().id()), query);
    }

    @Transactional(readOnly = true)
    public List<HrViews.AttendanceSummary> summary(LocalDate from, LocalDate to) {
        if (to.isBefore(from) || from.plusDays(366).isBefore(to)) {
            throw ApiException.validationFailed(
                    "The range is invalid.",
                    List.of(FieldViolation.atParameter("to", "INVALID_VALUE", "must be within 366 days after from")));
        }
        return attendance.summary(
                context.companyId(), CurrentContext.require().branchScope(), context.today(), from, to);
    }

    /** Creates or replaces the employee's record of the day (HR). */
    @Transactional
    public HrViews.Attendance record(UUID employeeId, LocalDate date, HrCommands.AttendanceEntry entry) {
        UUID companyId = context.companyId();
        EmployeeView employee = employees
                .lockForChange(companyId, employeeId, CurrentContext.require().branchScope(), context.today())
                .orElseThrow(ApiException::notFound);
        AttendanceStatus status = parse(entry.status());
        if (date.isBefore(employee.hireDate())
                || (employee.terminationDate() != null && date.isAfter(employee.terminationDate()))) {
            throw invalid("/workDate", "OUTSIDE_EMPLOYMENT", "must be within the employment");
        }
        if (date.isAfter(context.today())) {
            throw invalid("/workDate", "IN_FUTURE", "must not be in the future");
        }
        if (!status.worked() && (entry.checkIn() != null || entry.checkOut() != null)) {
            throw invalid("/checkIn", "INVALID_VALUE", "only days worked have check-in and check-out times");
        }
        Integer minutes = minutes(entry.checkIn(), entry.checkOut());
        Optional<HrViews.Attendance> existing = attendance.lock(companyId, employeeId, date);
        UUID id;
        if (existing.isPresent()) {
            HrViews.Attendance current = existing.get();
            id = current.id();
            if (!attendance.update(
                    companyId,
                    id,
                    current.version(),
                    status,
                    entry.checkIn(),
                    entry.checkOut(),
                    minutes,
                    "MANUAL",
                    note(entry.note()),
                    context.actor())) {
                throw new ApiException(PlatformErrorCode.VERSION_CONFLICT, "The record was modified concurrently.");
            }
        } else {
            id = attendance.insert(
                    companyId,
                    employeeId,
                    date,
                    status,
                    entry.checkIn(),
                    entry.checkOut(),
                    minutes,
                    "MANUAL",
                    note(entry.note()),
                    context.actor());
        }
        audit.record(AuditEvent.builder(existing.isPresent() ? "UPDATE" : "CREATE", "hr")
                .entity("attendance_record", id, employee.employeeNumber())
                .detail("workDate", date.toString())
                .change("status", existing.map(a -> a.status().name()).orElse(null), status.name())
                .build());
        return attendance.lock(companyId, employeeId, date).orElseThrow();
    }

    @Transactional
    public void delete(UUID employeeId, LocalDate date) {
        UUID companyId = context.companyId();
        EmployeeView employee = employees
                .find(companyId, employeeId, CurrentContext.require().branchScope(), context.today())
                .orElseThrow(ApiException::notFound);
        HrViews.Attendance current =
                attendance.lock(companyId, employeeId, date).orElseThrow(ApiException::notFound);
        attendance.delete(companyId, current.id());
        audit.record(AuditEvent.builder("DELETE", "hr")
                .entity("attendance_record", current.id(), employee.employeeNumber())
                .detail("workDate", date.toString())
                .build());
    }

    /** The employee starts their day (self-service). */
    @Transactional
    public HrViews.Attendance clockIn() {
        UUID companyId = context.companyId();
        EmployeeView me = activeMe();
        LocalDate today = context.today();
        if (attendance.lock(companyId, me.id(), today).isPresent()) {
            throw new ApiException(HrErrorCode.ATTENDANCE_STATE, "You have already clocked in today.");
        }
        OffsetDateTime now = OffsetDateTime.now(context.clock()).withOffsetSameInstant(ZoneOffset.UTC);
        UUID id = attendance.insert(
                companyId, me.id(), today, AttendanceStatus.PRESENT, now, null, null, "SELF", null, context.actor());
        audit.record(AuditEvent.builder("CREATE", "hr")
                .entity("attendance_record", id, me.employeeNumber())
                .detail("workDate", today.toString())
                .detail("source", "SELF")
                .build());
        return attendance.lock(companyId, me.id(), today).orElseThrow();
    }

    /** The employee ends their day (self-service). */
    @Transactional
    public HrViews.Attendance clockOut() {
        UUID companyId = context.companyId();
        EmployeeView me = activeMe();
        LocalDate today = context.today();
        HrViews.Attendance current = attendance
                .lock(companyId, me.id(), today)
                .filter(a -> a.checkIn() != null && a.checkOut() == null)
                .orElseThrow(() -> new ApiException(HrErrorCode.ATTENDANCE_STATE, "You have not clocked in today."));
        OffsetDateTime now = OffsetDateTime.now(context.clock()).withOffsetSameInstant(ZoneOffset.UTC);
        Integer minutes = minutes(current.checkIn(), now);
        if (!attendance.update(
                companyId,
                current.id(),
                current.version(),
                current.status(),
                current.checkIn(),
                now,
                minutes,
                "SELF",
                current.note(),
                context.actor())) {
            throw new ApiException(PlatformErrorCode.VERSION_CONFLICT, "The record was modified concurrently.");
        }
        audit.record(AuditEvent.builder("UPDATE", "hr")
                .entity("attendance_record", current.id(), me.employeeNumber())
                .detail("workDate", today.toString())
                .detail("workedMinutes", minutes)
                .build());
        return attendance.lock(companyId, me.id(), today).orElseThrow();
    }

    private EmployeeView activeMe() {
        EmployeeView me = team.me();
        if (me.status() == EmployeeStatus.TERMINATED || me.status() == EmployeeStatus.ONBOARDING) {
            throw new ApiException(PlatformErrorCode.INVALID_STATE, "Only active employees clock in and out.");
        }
        return me;
    }

    private static @Nullable Integer minutes(@Nullable OffsetDateTime checkIn, @Nullable OffsetDateTime checkOut) {
        try {
            return AttendanceStatus.workedMinutes(checkIn, checkOut);
        } catch (IllegalArgumentException e) {
            throw invalid("/checkOut", "INVALID_VALUE", "must be within 24 hours after check-in");
        }
    }

    private static AttendanceStatus parse(String status) {
        try {
            return AttendanceStatus.valueOf(status);
        } catch (IllegalArgumentException e) {
            throw invalid("/status", "INVALID_VALUE", "is not an attendance status");
        }
    }

    private static @Nullable String note(@Nullable String note) {
        return note == null || note.isBlank() ? null : note.strip();
    }

    private static ApiException invalid(String pointer, String code, String message) {
        return ApiException.validationFailed(
                "The attendance record is invalid.", List.of(FieldViolation.atPointer(pointer, code, message)));
    }
}
