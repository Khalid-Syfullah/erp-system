package com.erp.hr.application;

import com.erp.hr.domain.EmployeeStatus;
import com.erp.hr.domain.LeaveEntitlement;
import com.erp.hr.persistence.EmployeeRepository;
import com.erp.hr.persistence.LeaveLedgerRepository;
import com.erp.hr.persistence.LeaveTypeRepository;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditPort;
import com.erp.platform.context.CurrentContext;
import com.erp.platform.context.RequestContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The leave accrual run (PRODUCT_SPEC.md §10.1, Q-19), idempotent and safe to repeat: for every
 * active leave type and every employee employed in the leave year up to {@code asOf}, the year's
 * annual grant or the monthly grants due so far; at the first run of a year the previous year is
 * closed: its positive balance leaves it ({@code EXPIRY}) and up to the type's maximum is carried
 * into the new year ({@code CARRY_FORWARD}). Unique ledger indexes make repeated runs no-ops.
 */
@Service
public class LeaveAccrualService {

    /** Entries written by one run. */
    public record Result(int accruals, int carryForwards, int expiries) {}

    private final LeaveTypeRepository types;
    private final EmployeeRepository employees;
    private final LeaveLedgerRepository ledger;
    private final HrContext context;
    private final AuditPort audit;

    LeaveAccrualService(
            LeaveTypeRepository types,
            EmployeeRepository employees,
            LeaveLedgerRepository ledger,
            HrContext context,
            AuditPort audit) {
        this.types = types;
        this.employees = employees;
        this.ledger = ledger;
        this.context = context;
        this.audit = audit;
    }

    @Transactional
    public Result accrue(LocalDate asOf) {
        UUID companyId = context.companyId();
        int year = asOf.getYear();
        LocalDate yearStart = LocalDate.of(year, 1, 1);
        UUID actor = actor();
        int accruals = 0;
        int carried = 0;
        int expired = 0;
        for (HrViews.LeaveType type : types.all(companyId)) {
            if (!type.active()) {
                continue;
            }
            for (EmployeeView e : employees.employedBetween(companyId, yearStart, asOf)) {
                if (e.status() == EmployeeStatus.TERMINATED
                        && e.terminationDate() != null
                        && e.terminationDate().isBefore(yearStart)) {
                    continue;
                }
                // Close the previous year once: its positive balance leaves it (EXPIRY) and the part
                // allowed is carried into this year (CARRY_FORWARD), so no day counts in both years.
                BigDecimal previous = ledger.balance(companyId, e.id(), type.id(), year - 1);
                if (previous.signum() > 0) {
                    var cf = LeaveEntitlement.carryForward(previous, type.maxCarryForwardDays());
                    if (ledger.append(
                            companyId,
                            e.id(),
                            type.id(),
                            year - 1,
                            null,
                            "EXPIRY",
                            previous.negate(),
                            null,
                            "Year closed: " + cf.carried().toPlainString() + " carried into " + year + ", "
                                    + cf.expired().toPlainString() + " expired",
                            actor)) {
                        expired++;
                        if (cf.carried().signum() > 0
                                && ledger.append(
                                        companyId,
                                        e.id(),
                                        type.id(),
                                        year,
                                        null,
                                        "CARRY_FORWARD",
                                        cf.carried(),
                                        null,
                                        "From " + (year - 1),
                                        actor)) {
                            carried++;
                        }
                    }
                }
                if ("MONTHLY".equals(type.accrualMethod())) {
                    BigDecimal grant = LeaveEntitlement.monthlyGrant(type.annualEntitlementDays());
                    if (grant.signum() <= 0) {
                        continue;
                    }
                    for (int month = 1; month <= asOf.getMonthValue(); month++) {
                        LocalDate monthStart = LocalDate.of(year, month, 1);
                        boolean employedInMonth = LeaveEntitlement.monthDue(e.hireDate(), year, month)
                                && (e.terminationDate() == null
                                        || !e.terminationDate().isBefore(monthStart));
                        if (employedInMonth
                                && ledger.append(
                                        companyId, e.id(), type.id(), year, month, "ACCRUAL", grant, null, null,
                                        actor)) {
                            accruals++;
                        }
                    }
                } else {
                    var grant = LeaveEntitlement.annualGrant(type.annualEntitlementDays(), e.hireDate(), year);
                    if (grant.isPresent()
                            && ledger.append(
                                    companyId,
                                    e.id(),
                                    type.id(),
                                    year,
                                    null,
                                    "ACCRUAL",
                                    grant.get(),
                                    null,
                                    null,
                                    actor)) {
                        accruals++;
                    }
                }
            }
        }
        if (accruals + carried + expired > 0) {
            audit.record(AuditEvent.builder("ACCRUE", "hr")
                    .entity("leave_ledger", null, String.valueOf(year))
                    .detail("asOf", asOf.toString())
                    .detail("accruals", accruals)
                    .detail("carryForwards", carried)
                    .detail("expiries", expired)
                    .build());
        }
        return new Result(accruals, carried, expired);
    }

    private static @Nullable UUID actor() {
        return CurrentContext.get()
                .map(RequestContext::actor)
                .map(a -> a.userId())
                .orElse(null);
    }
}
