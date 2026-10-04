package com.erp.hr.api;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** What Payroll reads from HR (ARCHITECTURE.md §5.2). Every call is scoped to the current company. */
public interface HrFacade {

    /** An employee as Payroll sees it. */
    record EmployeeInfo(
            UUID id,
            String employeeNumber,
            String displayName,
            LocalDate hireDate,
            @Nullable LocalDate terminationDate,
            String status) {}

    /** An employment assignment's span (both ends inclusive; {@code to} null = open). */
    record AssignmentSpan(
            UUID employeeId,
            UUID branchId,
            UUID departmentId,
            @Nullable String positionTitle,
            LocalDate from,
            @Nullable LocalDate to) {}

    /** An employee's primary bank account, decrypted. The caller audits its use. */
    record BankDetails(
            UUID employeeId,
            String accountHolder,
            String bankName,
            String accountNumber,
            @Nullable String iban,
            @Nullable String swiftBic) {}

    Optional<EmployeeInfo> employee(UUID employeeId);

    Map<UUID, EmployeeInfo> employees(Collection<UUID> employeeIds);

    /** The employee linked to the user, if any (self-service). */
    Optional<UUID> employeeOfUser(UUID userId);

    /** Assignments of the employees overlapping {@code from}–{@code to}. */
    List<AssignmentSpan> assignments(Collection<UUID> employeeIds, LocalDate from, LocalDate to);

    /** Working days from {@code from} to {@code to} for the branch: weekends and holidays excluded. */
    int workingDays(@Nullable UUID branchId, LocalDate from, LocalDate to);

    Map<UUID, BankDetails> primaryBankAccounts(Collection<UUID> employeeIds);

    /** The company's business date. */
    LocalDate today();
}
