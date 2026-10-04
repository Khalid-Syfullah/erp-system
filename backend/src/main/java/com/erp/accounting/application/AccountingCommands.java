package com.erp.accounting.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Write models of the Accounting module (built by the web layer from request DTOs). */
public final class AccountingCommands {

    private AccountingCommands() {}

    public record Account(
            String code,
            String name,
            String accountType,
            String accountSubtype,
            @Nullable UUID parentId,
            boolean postable,
            @Nullable String currencyCode,
            @Nullable String description) {}

    public record Mapping(
            String mappingKey, String scopeType, @Nullable UUID scopeId, UUID accountId) {}

    public record Journal(String code, String name, String journalType) {}

    /** A manual journal line: exactly one of debit and credit, in base currency. */
    public record EntryLine(
            UUID accountId,
            BigDecimal debit,
            BigDecimal credit,
            @Nullable String currencyCode,
            @Nullable BigDecimal amountCurrency,
            @Nullable UUID partnerId,
            @Nullable UUID branchId,
            @Nullable UUID departmentId,
            @Nullable UUID taxCodeId,
            @Nullable String description) {}

    public record JournalEntry(
            @Nullable UUID journalId,
            LocalDate entryDate,
            String entryType,
            String description,
            List<EntryLine> lines) {}

    public record BankAccount(
            String name,
            UUID accountId,
            String currencyCode,
            @Nullable String bankName,
            @Nullable String accountNumber,
            @Nullable String iban) {}

    public record Allocation(UUID openItemId, BigDecimal amount) {}

    public record Payment(
            String direction,
            UUID partnerId,
            UUID bankAccountId,
            @Nullable LocalDate paymentDate,
            BigDecimal amount,
            String method,
            @Nullable String reference,
            @Nullable String notes,
            List<Allocation> allocations) {}

    public record ExpenseLine(
            UUID accountId,
            @Nullable String description,
            BigDecimal amount,
            @Nullable UUID taxCodeId,
            @Nullable UUID branchId,
            @Nullable UUID departmentId) {}

    public record Expense(
            @Nullable LocalDate expenseDate,
            @Nullable LocalDate accountingDate,
            String payeeName,
            @Nullable UUID partnerId,
            UUID bankAccountId,
            boolean pricesIncludeTax,
            @Nullable String reference,
            @Nullable String notes,
            List<ExpenseLine> lines) {}

    public record Settings(
            UUID retainedEarningsAccountId,
            boolean allowManualEntriesInSoftClosed,
            int maxRoundingDifferenceMinorUnits,
            @Nullable BigDecimal manualEntryApprovalThresholdBase) {}
}
