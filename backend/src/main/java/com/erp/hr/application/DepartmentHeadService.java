package com.erp.hr.application;

import com.erp.hr.domain.EffectivePeriod;
import com.erp.hr.domain.EmployeeStatus;
import com.erp.hr.persistence.DepartmentHeadRepository;
import com.erp.hr.persistence.EmployeeRepository;
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
 * Department heads (effective-dated, one per department at a time). The head is an employee of the
 * company who is employed for the whole period; the department must be active. The caller must be
 * able to see the employee (branch scope).
 */
@Service
public class DepartmentHeadService {

    static final Set<String> PATCHABLE = Set.of("effectiveTo");

    private final DepartmentHeadRepository heads;
    private final EmployeeRepository employees;
    private final OrgFacade org;
    private final HrCalendar calendar;
    private final AuditPort audit;

    DepartmentHeadService(
            DepartmentHeadRepository heads,
            EmployeeRepository employees,
            OrgFacade org,
            HrCalendar calendar,
            AuditPort audit) {
        this.heads = heads;
        this.employees = employees;
        this.org = org;
        this.calendar = calendar;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public PageResponse<DepartmentHeadView> list(@Nullable LocalDate asOf, ListQuery query) {
        return heads.list(CurrentContext.requireCompany(), asOf, query);
    }

    @Transactional(readOnly = true)
    public DepartmentHeadView get(UUID id) {
        return heads.find(CurrentContext.requireCompany(), id).orElseThrow(ApiException::notFound);
    }

    @Transactional
    public DepartmentHeadView create(HrCommands.DepartmentHead command) {
        UUID companyId = CurrentContext.requireCompany();
        List<FieldViolation> violations = new ArrayList<>();
        if (command.effectiveTo() != null && command.effectiveTo().isBefore(command.effectiveFrom())) {
            violations.add(
                    FieldViolation.atPointer("/effectiveTo", "INVALID_VALUE", "must not be before effectiveFrom"));
            throw ApiException.validationFailed("The department head is invalid.", violations);
        }
        EffectivePeriod period = new EffectivePeriod(command.effectiveFrom(), command.effectiveTo());
        Optional<EmployeeView> employee = employees.lockForChange(
                companyId, command.employeeId(), CurrentContext.require().branchScope(), calendar.today(companyId));
        if (employee.isEmpty()) {
            violations.add(
                    FieldViolation.atPointer("/employeeId", "UNKNOWN_EMPLOYEE", "is not an employee of the company"));
        } else {
            employmentViolation(employee.get(), period).ifPresent(violations::add);
        }
        Optional<DepartmentSummary> department = org.departmentForUse(companyId, command.departmentId());
        if (department.isEmpty()) {
            violations.add(FieldViolation.atPointer(
                    "/departmentId", "UNKNOWN_DEPARTMENT", "is not a department of the company"));
        } else if (!department.get().active()) {
            violations.add(FieldViolation.atPointer("/departmentId", "INACTIVE", "must be an active department"));
        }
        if (!violations.isEmpty()) {
            throw ApiException.validationFailed("The department head is invalid.", violations);
        }
        requireNoOverlap(companyId, command.departmentId(), period, null);
        UUID id = heads.insert(companyId, command, CurrentContext.requireActor().userId());
        audit.record(AuditEvent.builder("CREATE", "hr")
                .entity("department_head", id, employee.get().employeeNumber())
                .detail("departmentId", command.departmentId())
                .detail("employeeId", command.employeeId())
                .detail("effectiveFrom", command.effectiveFrom().toString())
                .detail("effectiveTo", Objects.toString(command.effectiveTo(), null))
                .build());
        return get(id);
    }

    /** Ends or extends a headship; only {@code effectiveTo} can change. */
    @Transactional
    public DepartmentHeadView patch(UUID id, @Nullable String ifMatch, JsonNode document) {
        UUID companyId = CurrentContext.requireCompany();
        DepartmentHeadView current = heads.lockForChange(companyId, id).orElseThrow(ApiException::notFound);
        EntityTags.requireMatch(ifMatch, current.version());
        MergePatch patch = MergePatch.of(document, PATCHABLE);
        var effectiveTo = patch.date("effectiveTo", false);
        LocalDate newTo = effectiveTo.orElse(current.effectiveTo());
        if (newTo != null && newTo.isBefore(current.effectiveFrom())) {
            patch.reject("effectiveTo", "INVALID_VALUE", "must not be before effectiveFrom");
        }
        patch.throwIfInvalid();
        if (Objects.equals(newTo, current.effectiveTo())) {
            return current;
        }
        EffectivePeriod period = new EffectivePeriod(current.effectiveFrom(), newTo);
        EmployeeView employee = employees
                .lockForChange(
                        companyId,
                        current.employeeId(),
                        CurrentContext.require().branchScope(),
                        calendar.today(companyId))
                .orElseThrow(ApiException::notFound);
        employmentViolation(employee, period).ifPresent(v -> {
            throw ApiException.validationFailed("The department head is invalid.", List.of(v));
        });
        requireNoOverlap(companyId, current.departmentId(), period, id);
        if (!heads.updateEnd(
                companyId, id, current.version(), CurrentContext.requireActor().userId(), newTo)) {
            throw new ApiException(
                    PlatformErrorCode.VERSION_CONFLICT, "The department head was modified concurrently.");
        }
        DepartmentHeadView after = get(id);
        audit.record(AuditEvent.builder("UPDATE", "hr")
                .entity("department_head", id, employee.employeeNumber())
                .change(
                        "effectiveTo",
                        Objects.toString(current.effectiveTo(), null),
                        Objects.toString(after.effectiveTo(), null))
                .build());
        return after;
    }

    private static Optional<FieldViolation> employmentViolation(EmployeeView employee, EffectivePeriod period) {
        if (employee.status() == EmployeeStatus.TERMINATED
                && (period.to() == null || period.to().isAfter(employee.terminationDate()))) {
            return Optional.of(FieldViolation.atPointer(
                    "/employeeId", "TERMINATED", "leaves the company before the headship ends"));
        }
        if (period.from().isBefore(employee.hireDate())) {
            return Optional.of(
                    FieldViolation.atPointer("/effectiveFrom", "BEFORE_HIRE", "must not be before the hire date"));
        }
        return Optional.empty();
    }

    private void requireNoOverlap(UUID companyId, UUID departmentId, EffectivePeriod period, @Nullable UUID excludeId) {
        if (!heads.overlapping(companyId, departmentId, period, excludeId).isEmpty()) {
            throw new ApiException(
                    HrErrorCode.DEPARTMENT_HEAD_OVERLAP,
                    "The department already has a head in this period; end that headship first.");
        }
    }
}
