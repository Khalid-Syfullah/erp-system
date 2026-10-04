package com.erp.hr.application;

import com.erp.hr.api.HrFacade;
import com.erp.hr.domain.EffectivePeriod;
import com.erp.hr.persistence.EmployeeBankAccountRepository;
import com.erp.hr.persistence.EmployeeRepository;
import com.erp.hr.persistence.EmploymentAssignmentRepository;
import com.erp.hr.persistence.PositionRepository;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** {@link HrFacade} for Payroll; every call is scoped to the current company. */
@Service
class HrDirectory implements HrFacade {

    private final EmployeeRepository employees;
    private final EmploymentAssignmentRepository assignments;
    private final PositionRepository positions;
    private final EmployeeBankAccountRepository bankAccounts;
    private final EmployeeBankAccountService bankAccountService;
    private final WorkCalendarService calendars;
    private final HrContext context;

    HrDirectory(
            EmployeeRepository employees,
            EmploymentAssignmentRepository assignments,
            PositionRepository positions,
            EmployeeBankAccountRepository bankAccounts,
            EmployeeBankAccountService bankAccountService,
            WorkCalendarService calendars,
            HrContext context) {
        this.employees = employees;
        this.assignments = assignments;
        this.positions = positions;
        this.bankAccounts = bankAccounts;
        this.bankAccountService = bankAccountService;
        this.calendars = calendars;
        this.context = context;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<EmployeeInfo> employee(UUID employeeId) {
        return Optional.ofNullable(employees(List.of(employeeId)).get(employeeId));
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, EmployeeInfo> employees(Collection<UUID> employeeIds) {
        Map<UUID, EmployeeInfo> result = new LinkedHashMap<>();
        for (EmployeeView e : employees.findAll(context.companyId(), employeeIds)) {
            result.put(
                    e.id(),
                    new EmployeeInfo(
                            e.id(),
                            e.employeeNumber(),
                            e.displayName(),
                            e.hireDate(),
                            e.terminationDate(),
                            e.status().name()));
        }
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> employeeOfUser(UUID userId) {
        return employees.findByUser(context.companyId(), userId).map(EmployeeView::id);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AssignmentSpan> assignments(Collection<UUID> employeeIds, LocalDate from, LocalDate to) {
        UUID companyId = context.companyId();
        List<AssignmentView> spans = assignments.overlappingAll(companyId, employeeIds, new EffectivePeriod(from, to));
        Set<UUID> positionIds = new HashSet<>();
        spans.stream().map(AssignmentView::positionId).filter(Objects::nonNull).forEach(positionIds::add);
        Map<UUID, String> titles = positions.titles(companyId, positionIds);
        return spans.stream()
                .map(a -> new AssignmentSpan(
                        a.employeeId(),
                        a.branchId(),
                        a.departmentId(),
                        a.positionId() == null ? null : titles.get(a.positionId()),
                        a.effectiveFrom(),
                        a.effectiveTo()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public int workingDays(@Nullable UUID branchId, LocalDate from, LocalDate to) {
        return calendars.workingDays(context.companyId(), branchId, from, to);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, BankDetails> primaryBankAccounts(Collection<UUID> employeeIds) {
        Map<UUID, BankDetails> result = new LinkedHashMap<>();
        for (HrViews.EncryptedBankAccount stored : bankAccounts.primaryEncrypted(context.companyId(), employeeIds)) {
            var revealed = bankAccountService.decrypt(stored);
            result.put(
                    stored.employeeId(),
                    new BankDetails(
                            stored.employeeId(),
                            stored.accountHolder(),
                            stored.bankName(),
                            revealed.accountNumber(),
                            revealed.iban(),
                            stored.swiftBic()));
        }
        return result;
    }

    @Override
    public LocalDate today() {
        return context.today();
    }
}
