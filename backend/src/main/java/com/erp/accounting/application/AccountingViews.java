package com.erp.accounting.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Read models of the Accounting module. */
public final class AccountingViews {

    private AccountingViews() {}

    public record Settings(
            UUID retainedEarningsAccountId,
            boolean allowManualEntriesInSoftClosed,
            int maxRoundingDifferenceMinorUnits,
            @Nullable BigDecimal manualEntryApprovalThresholdBase,
            String coaTemplate,
            int version) {}

    public record Account(
            UUID id,
            String code,
            String name,
            String accountType,
            String accountSubtype,
            @Nullable UUID parentId,
            boolean postable,
            boolean control,
            boolean system,
            @Nullable String currencyCode,
            String status,
            @Nullable String description,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            int version) {

        public boolean active() {
            return "ACTIVE".equals(status);
        }
    }

    public record Mapping(
            UUID id,
            String mappingKey,
            String scopeType,
            @Nullable UUID scopeId,
            UUID accountId) {}

    public record FiscalYear(
            UUID id,
            String code,
            LocalDate startDate,
            LocalDate endDate,
            String status,
            @Nullable UUID closingEntryId,
            @Nullable OffsetDateTime closedAt,
            @Nullable UUID closedBy,
            int version) {}

    public record Period(
            UUID id,
            UUID fiscalYearId,
            int periodNo,
            LocalDate startDate,
            LocalDate endDate,
            String status,
            @Nullable OffsetDateTime closedAt,
            @Nullable UUID closedBy,
            int version) {}

    public record FiscalYearDetail(FiscalYear year, List<Period> periods) {}

    public record Journal(
            UUID id, String code, String name, String journalType, boolean system, boolean active, int version) {}

    public record JournalEntry(
            UUID id,
            UUID journalId,
            @Nullable String number,
            LocalDate entryDate,
            UUID periodId,
            String entryType,
            String status,
            String description,
            String currencyCode,
            BigDecimal exchangeRate,
            @Nullable String sourceModule,
            @Nullable String sourceType,
            @Nullable UUID sourceId,
            @Nullable String sourceNumber,
            @Nullable UUID sourceEventId,
            @Nullable UUID reversalOfId,
            @Nullable UUID reversedById,
            BigDecimal totalDebit,
            BigDecimal totalCredit,
            @Nullable OffsetDateTime postedAt,
            @Nullable UUID postedBy,
            @Nullable UUID createdBy,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            int version) {}

    public record JournalLine(
            UUID id,
            UUID journalEntryId,
            int lineNo,
            UUID accountId,
            BigDecimal debit,
            BigDecimal credit,
            String currencyCode,
            BigDecimal amountCurrency,
            @Nullable UUID partnerId,
            @Nullable UUID branchId,
            @Nullable UUID departmentId,
            @Nullable UUID taxCodeId,
            @Nullable UUID openItemId,
            @Nullable String description) {}

    public record JournalEntryDetail(JournalEntry entry, List<JournalLine> lines) {}

    public record JournalReport(LocalDate from, LocalDate to, List<JournalEntryDetail> entries) {}

    public record OpenItem(
            UUID id,
            String kind,
            UUID partnerId,
            UUID accountId,
            String sourceModule,
            String sourceType,
            UUID sourceId,
            String documentNumber,
            LocalDate documentDate,
            LocalDate dueDate,
            String currencyCode,
            BigDecimal originalAmount,
            BigDecimal openAmount,
            BigDecimal originalAmountBase,
            BigDecimal openAmountBase,
            BigDecimal exchangeRate,
            UUID journalEntryId,
            String status,
            @Nullable OffsetDateTime settledAt,
            OffsetDateTime createdAt,
            int version) {

        public boolean receivable() {
            return "RECEIVABLE".equals(kind);
        }
    }

    public record Allocation(
            UUID id,
            @Nullable UUID paymentId,
            UUID openItemId,
            UUID counterOpenItemId,
            LocalDate allocationDate,
            BigDecimal amount,
            BigDecimal amountBase,
            BigDecimal fxDifferenceBase,
            @Nullable UUID journalEntryId,
            @Nullable OffsetDateTime reversedAt,
            @Nullable UUID reversalJournalEntryId,
            @Nullable UUID createdBy,
            OffsetDateTime createdAt) {

        public boolean active() {
            return reversedAt == null;
        }
    }

    public record RequestedAllocation(UUID openItemId, BigDecimal amount) {}

    public record Payment(
            UUID id,
            @Nullable String number,
            String direction,
            @Nullable UUID partnerId,
            String paymentKind,
            UUID bankAccountId,
            LocalDate paymentDate,
            String currencyCode,
            BigDecimal amount,
            BigDecimal exchangeRate,
            BigDecimal amountBase,
            String method,
            @Nullable String reference,
            @Nullable String notes,
            String status,
            List<RequestedAllocation> requestedAllocations,
            @Nullable UUID openItemId,
            @Nullable UUID journalEntryId,
            @Nullable UUID voidJournalEntryId,
            @Nullable String voidedReason,
            @Nullable OffsetDateTime postedAt,
            @Nullable UUID createdBy,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            int version) {

        public boolean inbound() {
            return "INBOUND".equals(direction);
        }
    }

    public record PaymentDetail(Payment payment, @Nullable OpenItem openItem, List<Allocation> allocations) {}

    public record Expense(
            UUID id,
            @Nullable String number,
            LocalDate expenseDate,
            LocalDate accountingDate,
            String payeeName,
            @Nullable UUID partnerId,
            UUID bankAccountId,
            String currencyCode,
            BigDecimal exchangeRate,
            boolean pricesIncludeTax,
            BigDecimal subtotal,
            BigDecimal taxTotal,
            BigDecimal total,
            BigDecimal totalBase,
            @Nullable String reference,
            @Nullable String notes,
            String status,
            @Nullable UUID journalEntryId,
            @Nullable UUID reversalEntryId,
            @Nullable String reversalReason,
            @Nullable OffsetDateTime postedAt,
            @Nullable UUID createdBy,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            int version) {}

    public record ExpenseLine(
            UUID id,
            int lineNo,
            UUID accountId,
            @Nullable String description,
            BigDecimal netAmount,
            @Nullable UUID taxCodeId,
            BigDecimal taxAmount,
            BigDecimal totalAmount,
            @Nullable UUID branchId,
            @Nullable UUID departmentId) {}

    public record ExpenseDetail(Expense expense, List<ExpenseLine> lines) {}

    /** A posted line on a bank or cash account, with its reconciliation mark if any. */
}
