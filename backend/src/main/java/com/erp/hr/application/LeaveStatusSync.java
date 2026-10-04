package com.erp.hr.application;

import com.erp.hr.domain.EmployeeStatus;
import com.erp.hr.persistence.EmployeeRepository;
import com.erp.hr.persistence.LeaveRequestRepository;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditPort;
import com.erp.platform.context.CurrentContext;
import com.erp.platform.context.RequestContext;
import com.erp.platform.web.ApiException;
import com.erp.platform.web.PlatformErrorCode;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The {@code ACTIVE ⇄ ON_LEAVE} transitions driven by leave (PRODUCT_SPEC.md §10.1): an active
 * employee with approved leave covering the business date is on leave, and back when it ends.
 */
@Component
public class LeaveStatusSync {

    private final EmployeeRepository employees;
    private final LeaveRequestRepository requests;
    private final AuditPort audit;

    LeaveStatusSync(EmployeeRepository employees, LeaveRequestRepository requests, AuditPort audit) {
        this.employees = employees;
        this.requests = requests;
        this.audit = audit;
    }

    /** Aligns the (locked) employee's status with their leave on {@code today}. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void sync(EmployeeView employee, LocalDate today) {
        boolean onLeave = requests.approvedOn(employee.companyId(), employee.id(), today);
        EmployeeStatus target = null;
        if (onLeave && employee.status() == EmployeeStatus.ACTIVE) {
            target = EmployeeStatus.ON_LEAVE;
        } else if (!onLeave && employee.status() == EmployeeStatus.ON_LEAVE) {
            target = EmployeeStatus.ACTIVE;
        }
        if (target == null) {
            return;
        }
        if (!employees.updateStatus(
                employee.companyId(), employee.id(), employee.version(), actor(), target, null, null)) {
            throw new ApiException(PlatformErrorCode.VERSION_CONFLICT, "The employee was modified concurrently.");
        }
        audit.record(AuditEvent.builder("STATE_CHANGE", "hr")
                .entity("employee", employee.id(), employee.employeeNumber())
                .transition(employee.status().name(), target.name())
                .detail("reason", "LEAVE")
                .build());
    }

    /** The daily run for one company: every active or on-leave employee whose state is out of date. */
    @Transactional
    public int syncCompany(UUID companyId, LocalDate today) {
        Set<UUID> onLeave = requests.onLeave(companyId, today);
        int changed = 0;
        for (EmployeeView e : employees.employedBetween(companyId, today, today)) {
            boolean shouldBeOnLeave = onLeave.contains(e.id());
            if ((shouldBeOnLeave && e.status() == EmployeeStatus.ACTIVE)
                    || (!shouldBeOnLeave && e.status() == EmployeeStatus.ON_LEAVE)) {
                sync(employees.lockForChange(companyId, e.id(), null, today).orElseThrow(), today);
                changed++;
            }
        }
        return changed;
    }

    private static @org.jspecify.annotations.Nullable UUID actor() {
        return CurrentContext.get()
                .map(RequestContext::actor)
                .map(a -> a.userId())
                .orElse(null);
    }
}
