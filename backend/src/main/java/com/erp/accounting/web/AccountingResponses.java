package com.erp.accounting.web;

import com.erp.accounting.application.AccountingViews;
import com.erp.accounting.application.ChartOfAccountsService;
import com.erp.platform.web.EntityTags;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;

/** Response bodies of the Accounting endpoints (API.md §17.8). */
final class AccountingResponses {

    static final String MERGE_PATCH = "application/merge-patch+json";

    private AccountingResponses() {}

    record Settings(
            UUID retainedEarningsAccountId,
            boolean allowManualEntriesInSoftClosed,
            int maxRoundingDifferenceMinorUnits,
            @Nullable BigDecimal manualEntryApprovalThresholdBase,
            String coaTemplate) {
        static ResponseEntity<Settings> entity(AccountingViews.Settings s) {
            return ResponseEntity.ok()
                    .eTag(EntityTags.forVersion(s.version()))
                    .body(new Settings(
                            s.retainedEarningsAccountId(),
                            s.allowManualEntriesInSoftClosed(),
                            s.maxRoundingDifferenceMinorUnits(),
                            s.manualEntryApprovalThresholdBase(),
                            s.coaTemplate()));
        }
    }

    record Account(
            UUID id,
            String code,
            String name,
            String accountType,
            String accountSubtype,
            @Nullable UUID parentId,
            boolean isPostable,
            boolean isControl,
            boolean isSystem,
            @Nullable String currencyCode,
            String status,
            @Nullable String description,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            int version) {
        static Account from(AccountingViews.Account a) {
            return new Account(
                    a.id(),
                    a.code(),
                    a.name(),
                    a.accountType(),
                    a.accountSubtype(),
                    a.parentId(),
                    a.postable(),
                    a.control(),
                    a.system(),
                    a.currencyCode(),
                    a.status(),
                    a.description(),
                    a.createdAt(),
                    a.updatedAt(),
                    a.version());
        }

        static ResponseEntity<Account> entity(AccountingViews.Account a) {
            return ResponseEntity.ok().eTag(EntityTags.forVersion(a.version())).body(from(a));
        }
    }

    record AccountNode(Account account, List<AccountNode> children) {
        static AccountNode from(ChartOfAccountsService.Node node) {
            return new AccountNode(
                    Account.from(node.account()),
                    node.children().stream().map(AccountNode::from).toList());
        }
    }

    record Mapping(
            UUID id,
            String mappingKey,
            String scopeType,
            @Nullable UUID scopeId,
            UUID accountId) {
        static Mapping from(AccountingViews.Mapping m) {
            return new Mapping(m.id(), m.mappingKey(), m.scopeType(), m.scopeId(), m.accountId());
        }
    }

    record Mappings(List<Mapping> mappings) {}

    record Period(
            UUID id,
            UUID fiscalYearId,
            int periodNo,
            LocalDate startDate,
            LocalDate endDate,
            String status,
            @Nullable OffsetDateTime closedAt,
            @Nullable UUID closedBy,
            int version) {
        static Period from(AccountingViews.Period p) {
            return new Period(
                    p.id(),
                    p.fiscalYearId(),
                    p.periodNo(),
                    p.startDate(),
                    p.endDate(),
                    p.status(),
                    p.closedAt(),
                    p.closedBy(),
                    p.version());
        }

        static ResponseEntity<Period> entity(AccountingViews.Period p) {
            return ResponseEntity.ok().eTag(EntityTags.forVersion(p.version())).body(from(p));
        }
    }

    record FiscalYear(
            UUID id,
            String code,
            LocalDate startDate,
            LocalDate endDate,
            String status,
            @Nullable UUID closingEntryId,
            @Nullable OffsetDateTime closedAt,
            @Nullable List<Period> periods,
            int version) {
        static FiscalYear from(AccountingViews.FiscalYear y, @Nullable List<AccountingViews.Period> periods) {
            return new FiscalYear(
                    y.id(),
                    y.code(),
                    y.startDate(),
                    y.endDate(),
                    y.status(),
                    y.closingEntryId(),
                    y.closedAt(),
                    periods == null ? null : periods.stream().map(Period::from).toList(),
                    y.version());
        }

        static ResponseEntity<FiscalYear> entity(AccountingViews.FiscalYearDetail d) {
            return ResponseEntity.ok()
                    .eTag(EntityTags.forVersion(d.year().version()))
                    .body(from(d.year(), d.periods()));
        }
    }

    record Journal(
            UUID id, String code, String name, String journalType, boolean isSystem, boolean isActive, int version) {
        static Journal from(AccountingViews.Journal j) {
            return new Journal(j.id(), j.code(), j.name(), j.journalType(), j.system(), j.active(), j.version());
        }

        static ResponseEntity<Journal> entity(AccountingViews.Journal j) {
            return ResponseEntity.ok().eTag(EntityTags.forVersion(j.version())).body(from(j));
        }
    }

    record JournalLine(
            UUID id,
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
            @Nullable String description) {
        static JournalLine from(AccountingViews.JournalLine l) {
            return new JournalLine(
                    l.id(),
                    l.lineNo(),
                    l.accountId(),
                    l.debit(),
                    l.credit(),
                    l.currencyCode(),
                    l.amountCurrency(),
                    l.partnerId(),
                    l.branchId(),
                    l.departmentId(),
                    l.taxCodeId(),
                    l.openItemId(),
                    l.description());
        }
    }

    record JournalEntry(
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
            @Nullable UUID reversalOfId,
            @Nullable UUID reversedById,
            BigDecimal totalDebit,
            BigDecimal totalCredit,
            @Nullable OffsetDateTime postedAt,
            @Nullable UUID postedBy,
            @Nullable UUID createdBy,
            @Nullable List<JournalLine> lines,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            int version) {
        static JournalEntry from(AccountingViews.JournalEntry e, @Nullable List<AccountingViews.JournalLine> lines) {
            return new JournalEntry(
                    e.id(),
                    e.journalId(),
                    e.number(),
                    e.entryDate(),
                    e.periodId(),
                    e.entryType(),
                    e.status(),
                    e.description(),
                    e.currencyCode(),
                    e.exchangeRate(),
                    e.sourceModule(),
                    e.sourceType(),
                    e.sourceId(),
                    e.sourceNumber(),
                    e.reversalOfId(),
                    e.reversedById(),
                    e.totalDebit(),
                    e.totalCredit(),
                    e.postedAt(),
                    e.postedBy(),
                    e.createdBy(),
                    lines == null ? null : lines.stream().map(JournalLine::from).toList(),
                    e.createdAt(),
                    e.updatedAt(),
                    e.version());
        }

        static ResponseEntity<JournalEntry> entity(AccountingViews.JournalEntryDetail d) {
            return ResponseEntity.ok()
                    .eTag(EntityTags.forVersion(d.entry().version()))
                    .body(from(d.entry(), d.lines()));
        }
    }

    record OpenItem(
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
            int version) {
        static OpenItem from(AccountingViews.OpenItem i) {
            return new OpenItem(
                    i.id(),
                    i.kind(),
                    i.partnerId(),
                    i.accountId(),
                    i.sourceModule(),
                    i.sourceType(),
                    i.sourceId(),
                    i.documentNumber(),
                    i.documentDate(),
                    i.dueDate(),
                    i.currencyCode(),
                    i.originalAmount(),
                    i.openAmount(),
                    i.originalAmountBase(),
                    i.openAmountBase(),
                    i.exchangeRate(),
                    i.journalEntryId(),
                    i.status(),
                    i.settledAt(),
                    i.version());
        }
    }

    record Allocation(
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
            OffsetDateTime createdAt) {
        static Allocation from(AccountingViews.Allocation a) {
            return new Allocation(
                    a.id(),
                    a.paymentId(),
                    a.openItemId(),
                    a.counterOpenItemId(),
                    a.allocationDate(),
                    a.amount(),
                    a.amountBase(),
                    a.fxDifferenceBase(),
                    a.journalEntryId(),
                    a.reversedAt(),
                    a.reversalJournalEntryId(),
                    a.createdAt());
        }
    }

    record BankAccount(
            UUID id,
            String name,
            UUID accountId,
            String currencyCode,
            @Nullable String bankName,
            @Nullable String accountNumberMasked,
            boolean hasIban,
            boolean isActive,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            int version) {
        static BankAccount from(AccountingViews.BankAccount b) {
            return new BankAccount(
                    b.id(),
                    b.name(),
                    b.accountId(),
                    b.currencyCode(),
                    b.bankName(),
                    b.accountNumberLast4() == null
                            ? null
                            : com.erp.platform.banking.BankAccountNumbers.masked(b.accountNumberLast4()),
                    b.hasIban(),
                    b.active(),
                    b.createdAt(),
                    b.updatedAt(),
                    b.version());
        }

        static ResponseEntity<BankAccount> entity(AccountingViews.BankAccount b) {
            return ResponseEntity.ok().eTag(EntityTags.forVersion(b.version())).body(from(b));
        }
    }

    record RequestedAllocation(UUID openItemId, BigDecimal amount) {}

    record Payment(
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
            @Nullable BigDecimal unallocatedAmount,
            @Nullable UUID journalEntryId,
            @Nullable UUID voidJournalEntryId,
            @Nullable String voidedReason,
            @Nullable OffsetDateTime postedAt,
            @Nullable List<Allocation> allocations,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            int version) {
        static Payment from(
                AccountingViews.Payment p,
                AccountingViews.@Nullable OpenItem item,
                @Nullable List<AccountingViews.Allocation> allocations) {
            return new Payment(
                    p.id(),
                    p.number(),
                    p.direction(),
                    p.partnerId(),
                    p.paymentKind(),
                    p.bankAccountId(),
                    p.paymentDate(),
                    p.currencyCode(),
                    p.amount(),
                    p.exchangeRate(),
                    p.amountBase(),
                    p.method(),
                    p.reference(),
                    p.notes(),
                    p.status(),
                    p.requestedAllocations().stream()
                            .map(a -> new RequestedAllocation(a.openItemId(), a.amount()))
                            .toList(),
                    p.openItemId(),
                    item == null ? null : item.openAmount().abs(),
                    p.journalEntryId(),
                    p.voidJournalEntryId(),
                    p.voidedReason(),
                    p.postedAt(),
                    allocations == null
                            ? null
                            : allocations.stream().map(Allocation::from).toList(),
                    p.createdAt(),
                    p.updatedAt(),
                    p.version());
        }

        static ResponseEntity<Payment> entity(AccountingViews.PaymentDetail d) {
            return ResponseEntity.ok()
                    .eTag(EntityTags.forVersion(d.payment().version()))
                    .body(from(d.payment(), d.openItem(), d.allocations()));
        }
    }

    record ExpenseLine(
            UUID id,
            int lineNo,
            UUID accountId,
            @Nullable String description,
            BigDecimal netAmount,
            @Nullable UUID taxCodeId,
            BigDecimal taxAmount,
            BigDecimal totalAmount,
            @Nullable UUID branchId,
            @Nullable UUID departmentId) {
        static ExpenseLine from(AccountingViews.ExpenseLine l) {
            return new ExpenseLine(
                    l.id(),
                    l.lineNo(),
                    l.accountId(),
                    l.description(),
                    l.netAmount(),
                    l.taxCodeId(),
                    l.taxAmount(),
                    l.totalAmount(),
                    l.branchId(),
                    l.departmentId());
        }
    }

    record Expense(
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
            @Nullable List<ExpenseLine> lines,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            int version) {
        static Expense from(AccountingViews.Expense e, @Nullable List<AccountingViews.ExpenseLine> lines) {
            return new Expense(
                    e.id(),
                    e.number(),
                    e.expenseDate(),
                    e.accountingDate(),
                    e.payeeName(),
                    e.partnerId(),
                    e.bankAccountId(),
                    e.currencyCode(),
                    e.exchangeRate(),
                    e.pricesIncludeTax(),
                    e.subtotal(),
                    e.taxTotal(),
                    e.total(),
                    e.totalBase(),
                    e.reference(),
                    e.notes(),
                    e.status(),
                    e.journalEntryId(),
                    e.reversalEntryId(),
                    e.reversalReason(),
                    e.postedAt(),
                    lines == null ? null : lines.stream().map(ExpenseLine::from).toList(),
                    e.createdAt(),
                    e.updatedAt(),
                    e.version());
        }

        static ResponseEntity<Expense> entity(AccountingViews.ExpenseDetail d) {
            return ResponseEntity.ok()
                    .eTag(EntityTags.forVersion(d.expense().version()))
                    .body(from(d.expense(), d.lines()));
        }
    }

    record Reconciliation(int lines) {}
}
