package com.erp.hr.application;

import com.erp.hr.domain.AttendanceStatus;
import com.erp.hr.domain.LeaveRequestStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Read models of the Phase 9 HR features. */
public final class HrViews {

    /** Company HR settings; {@code weekendDays} are ISO day numbers (1 = Monday). */
    public record Settings(List<Integer> weekendDays, int standardWorkMinutes, int version) {}

    /** A bank account as listed: masked. */
    public record BankAccount(
            UUID id,
            UUID employeeId,
            String bankName,
            String accountHolder,
            String last4,
            @Nullable String swiftBic,
            boolean hasIban,
            boolean primary,
            OffsetDateTime createdAt,
            int version) {}

    /** A bank account with its encrypted numbers (reveal, payroll bank file). */
    public record EncryptedBankAccount(
            UUID id,
            UUID employeeId,
            String bankName,
            String accountHolder,
            byte[] accountNumber,
            byte @Nullable [] iban,
            @Nullable String swiftBic,
            boolean primary) {}

    public record Document(
            UUID id,
            UUID employeeId,
            String documentType,
            String title,
            UUID fileId,
            String fileName,
            String contentType,
            long sizeBytes,
            @Nullable LocalDate validUntil,
            OffsetDateTime createdAt,
            int version) {}

    public record LeaveType(
            UUID id,
            String code,
            String name,
            boolean paid,
            BigDecimal annualEntitlementDays,
            String accrualMethod,
            BigDecimal maxCarryForwardDays,
            boolean allowNegativeBalance,
            boolean active,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            int version) {}

    public record LeaveRequest(
            UUID id,
            UUID employeeId,
            UUID leaveTypeId,
            LocalDate startDate,
            LocalDate endDate,
            BigDecimal days,
            @Nullable String reason,
            LeaveRequestStatus status,
            @Nullable OffsetDateTime submittedAt,
            @Nullable UUID decidedBy,
            @Nullable OffsetDateTime decidedAt,
            @Nullable String decisionNote,
            @Nullable UUID createdBy,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            int version) {}

    public record LedgerEntry(
            UUID id,
            UUID employeeId,
            UUID leaveTypeId,
            int leaveYear,
            @Nullable Integer accrualMonth,
            String entryType,
            BigDecimal days,
            @Nullable UUID leaveRequestId,
            @Nullable String note,
            OffsetDateTime createdAt,
            @Nullable UUID createdBy) {}

    /**
     * A leave balance for one type and year: {@code balance} sums the ledger; {@code pending} are the
     * days of submitted requests; {@code available = balance − pending}.
     */
    public record Balance(
            UUID leaveTypeId,
            String leaveTypeCode,
            String leaveTypeName,
            int year,
            BigDecimal accrued,
            BigDecimal carriedForward,
            BigDecimal taken,
            BigDecimal adjusted,
            BigDecimal expired,
            BigDecimal balance,
            BigDecimal pending,
            BigDecimal available) {}

    public record Holiday(UUID id, @Nullable UUID branchId, LocalDate date, String name, int version) {}

    public record Attendance(
            UUID id,
            UUID employeeId,
            LocalDate workDate,
            AttendanceStatus status,
            @Nullable OffsetDateTime checkIn,
            @Nullable OffsetDateTime checkOut,
            @Nullable Integer workedMinutes,
            String source,
            @Nullable String note,
            OffsetDateTime updatedAt,
            int version) {}

    /** Days per attendance status and minutes worked of one employee in a range. */
    public record AttendanceSummary(
            UUID employeeId, String employeeNumber, String name, Map<String, Integer> days, int workedMinutes) {}

    /** A report as a manager sees them (HR-3): basic data only. */
    public record TeamMember(
            UUID employeeId,
            String employeeNumber,
            String name,
            String status,
            @Nullable UUID managerEmployeeId,
            UUID branchId,
            UUID departmentId,
            @Nullable UUID positionId,
            @Nullable String workEmail) {}

    /** Headcount on a date by branch and department. */
    public record Headcount(LocalDate asOf, int total, List<HeadcountRow> rows, Map<String, Integer> byStatus) {}

    public record HeadcountRow(UUID branchId, UUID departmentId, int employees, BigDecimal fte) {}

    private HrViews() {}
}
