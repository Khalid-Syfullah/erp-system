package com.erp.accounting.application;

import com.erp.accounting.domain.DocumentStatus;
import com.erp.accounting.domain.MappingKey;
import com.erp.accounting.domain.MappingKey.ScopeType;
import com.erp.accounting.persistence.AccountRepository;
import com.erp.accounting.persistence.EntryRepository;
import com.erp.accounting.persistence.ExpenseRepository;
import com.erp.org.api.OrgFacade;
import com.erp.org.api.TaxCalculator;
import com.erp.org.api.TaxCodeSummary;
import com.erp.partners.api.PartnersFacade;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditPort;
import com.erp.platform.numbering.DocumentNumberService;
import com.erp.platform.numbering.FiscalYears;
import com.erp.platform.web.ApiException;
import com.erp.platform.web.EntityTags;
import com.erp.platform.web.FieldViolation;
import com.erp.platform.web.MergePatch;
import com.erp.platform.web.MergePatchLines;
import com.erp.platform.web.PlatformErrorCode;
import com.erp.platform.web.paging.ListQuery;
import com.erp.platform.web.paging.PageResponse;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * Expense vouchers (PRODUCT_SPEC.md §8.9): immediate expenses paid from a bank or cash account, in its
 * currency. Posting debits the expense accounts (net) and input tax, and credits the bank account;
 * a posted voucher is undone by a reversal ({@code POSTED → REVERSED}). Expenses owed on credit go
 * through supplier bills instead.
 */
@Service
public class ExpenseService {

    static final Set<String> PATCHABLE = Set.of(
            "expenseDate",
            "accountingDate",
            "payeeName",
            "partnerId",
            "bankAccountId",
            "pricesIncludeTax",
            "reference",
            "notes",
            "lines");
    static final List<MergePatchLines.Member> LINE_MEMBERS = List.of(
            MergePatchLines.Member.uuid("accountId", true),
            MergePatchLines.Member.text("description", 300),
            MergePatchLines.Member.decimal("amount", true),
            MergePatchLines.Member.uuid("taxCodeId", false),
            MergePatchLines.Member.uuid("branchId", false),
            MergePatchLines.Member.uuid("departmentId", false));

    private final ExpenseRepository expenses;
    private final EntryRepository entries;
    private final AccountRepository accounts;
    private final CompanyBankAccountService bankAccounts;
    private final PostingService posting;
    private final AccountDetermination determination;
    private final TaxCalculator taxes;
    private final OrgFacade org;
    private final PartnersFacade partners;
    private final AccountingContext context;
    private final DocumentNumberService numbering;
    private final AuditPort audit;

    ExpenseService(
            ExpenseRepository expenses,
            EntryRepository entries,
            AccountRepository accounts,
            CompanyBankAccountService bankAccounts,
            PostingService posting,
            AccountDetermination determination,
            TaxCalculator taxes,
            OrgFacade org,
            PartnersFacade partners,
            AccountingContext context,
            DocumentNumberService numbering,
            AuditPort audit) {
        this.expenses = expenses;
        this.entries = entries;
        this.accounts = accounts;
        this.bankAccounts = bankAccounts;
        this.posting = posting;
        this.determination = determination;
        this.taxes = taxes;
        this.org = org;
        this.partners = partners;
        this.context = context;
        this.numbering = numbering;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public PageResponse<AccountingViews.Expense> list(ListQuery query) {
        return expenses.list(context.companyId(), query);
    }

    @Transactional(readOnly = true)
    public AccountingViews.ExpenseDetail get(UUID id) {
        UUID companyId = context.companyId();
        AccountingViews.Expense expense = expenses.find(companyId, id).orElseThrow(ApiException::notFound);
        return new AccountingViews.ExpenseDetail(expense, expenses.lines(companyId, id));
    }

    @Transactional
    public AccountingViews.ExpenseDetail create(AccountingCommands.Expense command) {
        UUID companyId = context.companyId();
        Draft draft = draft(command);
        UUID actor = context.actor();
        UUID id = expenses.insert(companyId, draft.header(), actor);
        expenses.insertLines(companyId, id, draft.lines(), actor);
        audit.record(AuditEvent.builder("CREATE", "accounting")
                .entity("expense", id, null)
                .detail("payeeName", command.payeeName())
                .detail("bankAccountId", command.bankAccountId())
                .detail("total", draft.header().total().toPlainString())
                .detail("lines", draft.lines().size())
                .build());
        return get(id);
    }

    @Transactional
    public AccountingViews.ExpenseDetail patch(UUID id, @Nullable String ifMatch, JsonNode document) {
        UUID companyId = context.companyId();
        AccountingViews.Expense current = lock(id, ifMatch, DocumentStatus.Action.EDIT);
        MergePatch patch = MergePatch.of(document, PATCHABLE);
        var expenseDate = patch.date("expenseDate", true);
        var accountingDate = patch.date("accountingDate", true);
        var payee = patch.text("payeeName", true, 200);
        var partner = patch.uuid("partnerId", false);
        var bank = patch.uuid("bankAccountId", true);
        var inclusive = patch.bool("pricesIncludeTax");
        var reference = patch.text("reference", false, 100);
        var notes = patch.text("notes", false, 2000);
        patch.throwIfInvalid();
        List<AccountingCommands.ExpenseLine> lines = document.has("lines")
                ? readLines(document.get("lines"))
                : expenses.lines(companyId, id).stream()
                        .map(l -> new AccountingCommands.ExpenseLine(
                                l.accountId(),
                                l.description(),
                                current.pricesIncludeTax() ? l.totalAmount() : l.netAmount(),
                                l.taxCodeId(),
                                l.branchId(),
                                l.departmentId()))
                        .toList();
        AccountingCommands.Expense next = new AccountingCommands.Expense(
                expenseDate.orElse(current.expenseDate()),
                accountingDate.orElse(current.accountingDate()),
                Objects.requireNonNull(payee.orElse(current.payeeName())),
                partner.orElse(current.partnerId()),
                Objects.requireNonNull(bank.orElse(current.bankAccountId())),
                Boolean.TRUE.equals(inclusive.orElse(current.pricesIncludeTax())),
                reference.orElse(current.reference()),
                notes.orElse(current.notes()),
                lines);
        Draft draft = draft(next);
        UUID actor = context.actor();
        expenses.deleteLines(companyId, id);
        expenses.insertLines(companyId, id, draft.lines(), actor);
        if (!expenses.updateDraft(companyId, id, current.version(), draft.header(), actor)) {
            throw new ApiException(PlatformErrorCode.VERSION_CONFLICT, "The expense was modified concurrently.");
        }
        audit.record(AuditEvent.builder("UPDATE", "accounting")
                .entity("expense", id, null)
                .change(
                        "total",
                        current.total().toPlainString(),
                        draft.header().total().toPlainString())
                .change(
                        "accountingDate",
                        current.accountingDate(),
                        draft.header().accountingDate())
                .detail("linesReplaced", document.has("lines"))
                .build());
        return get(id);
    }

    @Transactional
    public void delete(UUID id, @Nullable String ifMatch) {
        AccountingViews.Expense current = lock(id, ifMatch, DocumentStatus.Action.DELETE);
        if (!expenses.delete(context.companyId(), id, current.version())) {
            throw new ApiException(PlatformErrorCode.VERSION_CONFLICT, "The expense was modified concurrently.");
        }
        audit.record(AuditEvent.builder("DELETE", "accounting")
                .entity("expense", id, null)
                .build());
    }

    /** DRAFT → POSTED: Dr expense accounts and input tax, Cr the bank or cash account. */
    @Transactional
    public AccountingViews.ExpenseDetail post(UUID id, @Nullable String ifMatch) {
        UUID companyId = context.companyId();
        AccountingViews.Expense expense = lock(id, ifMatch, DocumentStatus.Action.POST);
        List<AccountingViews.ExpenseLine> lines = expenses.lines(companyId, id);
        AccountingViews.BankAccount bank = bankAccounts.forUse(expense.bankAccountId(), "/bankAccountId");
        BigDecimal rate = context.exchangeRate(expense.currencyCode(), expense.accountingDate());
        var baseRounding = context.baseRounding();
        String currency = expense.currencyCode();
        List<PostingService.Line> postingLines = new ArrayList<>();
        BigDecimal totalBase = BigDecimal.ZERO;
        for (AccountingViews.ExpenseLine line : lines) {
            BigDecimal netBase = baseRounding.round(line.netAmount().multiply(rate));
            totalBase = totalBase.add(netBase);
            postingLines.add(new PostingService.Line(
                    line.accountId(),
                    netBase,
                    currency,
                    line.netAmount(),
                    expense.partnerId(),
                    line.branchId(),
                    line.departmentId(),
                    line.taxCodeId(),
                    line.description(),
                    false,
                    null));
            if (line.taxAmount().signum() > 0) {
                BigDecimal taxBase = baseRounding.round(line.taxAmount().multiply(rate));
                totalBase = totalBase.add(taxBase);
                postingLines.add(new PostingService.Line(
                        determination.resolve(
                                MappingKey.TAX_INPUT, AccountDetermination.of(ScopeType.TAX_CODE, line.taxCodeId())),
                        taxBase,
                        currency,
                        line.taxAmount(),
                        null,
                        line.branchId(),
                        line.departmentId(),
                        line.taxCodeId(),
                        null,
                        false,
                        null));
            }
        }
        postingLines.add(new PostingService.Line(
                bank.accountId(),
                totalBase.negate(),
                currency,
                expense.total().negate(),
                expense.partnerId(),
                null,
                null,
                null,
                expense.payeeName(),
                false,
                null));
        String number = numbering.next(
                companyId,
                AccountingConfiguration.EXPENSE_VOUCHER,
                FiscalYears.label(expense.accountingDate(), context.profile().fiscalYearStartMonth()));
        String journal = "CASH"
                        .equals(accounts.find(companyId, bank.accountId())
                                .orElseThrow()
                                .accountSubtype())
                ? ChartTemplate.Journals.CASH
                : ChartTemplate.Journals.BANK;
        PostingService.Posted posted = posting.post(new PostingService.Request(
                journal,
                expense.accountingDate(),
                "SYSTEM",
                "Expense " + number + " (" + expense.payeeName() + ")",
                currency,
                rate,
                new PostingService.Source("accounting", "EXPENSE", expense.id(), number, null),
                null,
                postingLines,
                null,
                false,
                false));
        if (!expenses.markPosted(companyId, id, expense.version(), number, posted.entryId(), context.actor())) {
            throw new ApiException(PlatformErrorCode.VERSION_CONFLICT, "The expense was modified concurrently.");
        }
        audit.record(AuditEvent.builder("POST", "accounting")
                .entity("expense", id, number)
                .transition("DRAFT", "POSTED")
                .detail("journalEntryId", posted.entryId())
                .detail("totalBase", totalBase.toPlainString())
                .build());
        return get(id);
    }

    /** POSTED → REVERSED: the expense entry is reversed on {@code date}. */
    @Transactional
    public AccountingViews.ExpenseDetail reverse(UUID id, @Nullable String ifMatch, LocalDate date, String reason) {
        UUID companyId = context.companyId();
        AccountingViews.Expense expense = lock(id, ifMatch, DocumentStatus.Action.REVERSE);
        if (reason.isBlank()) {
            throw ApiException.validationFailed(
                    "A reason is required.", List.of(FieldViolation.atPointer("/reason", "REQUIRED", "is required")));
        }
        AccountingViews.JournalEntry entry = entries.lock(companyId, Objects.requireNonNull(expense.journalEntryId()))
                .orElseThrow();
        PostingService.Posted reversal = posting.reverse(
                entry,
                date,
                "Reversal of expense " + expense.number() + ": " + reason.strip(),
                new PostingService.Source("accounting", "EXPENSE_REVERSAL", expense.id(), expense.number(), null));
        if (!expenses.markReversed(
                companyId, id, expense.version(), reversal.entryId(), reason.strip(), context.actor())) {
            throw new ApiException(PlatformErrorCode.VERSION_CONFLICT, "The expense was modified concurrently.");
        }
        audit.record(AuditEvent.builder("REVERSE", "accounting")
                .entity("expense", id, expense.number())
                .transition("POSTED", "REVERSED")
                .detail("reason", reason.strip())
                .detail("reversalEntryId", reversal.entryId())
                .build());
        return get(id);
    }

    // ------------------------------------------------------------------------------ helpers

    private record Draft(ExpenseRepository.Header header, List<ExpenseRepository.NewLine> lines) {}

    private Draft draft(AccountingCommands.Expense command) {
        UUID companyId = context.companyId();
        List<FieldViolation> violations = new ArrayList<>();
        AccountingViews.BankAccount bank = bankAccounts.forUse(command.bankAccountId(), "/bankAccountId");
        LocalDate expenseDate = command.expenseDate() != null ? command.expenseDate() : context.today();
        LocalDate accountingDate = command.accountingDate() != null ? command.accountingDate() : expenseDate;
        if (command.payeeName().isBlank()) {
            violations.add(FieldViolation.atPointer("/payeeName", "REQUIRED", "is required"));
        }
        if (command.partnerId() != null
                && partners.partners(List.of(command.partnerId())).isEmpty()) {
            violations.add(
                    FieldViolation.atPointer("/partnerId", "UNKNOWN_PARTNER", "is not a partner of the company"));
        }
        if (command.lines().isEmpty() || command.lines().size() > 500) {
            violations.add(FieldViolation.atPointer("/lines", "SIZE", "must contain between 1 and 500 lines"));
        }
        String currency = bank.currencyCode();
        int minorUnits = context.rounding(currency).minorUnits();
        Map<Integer, BigDecimal> rates = new HashMap<>();
        for (int i = 0; i < command.lines().size(); i++) {
            AccountingCommands.ExpenseLine line = command.lines().get(i);
            String at = "/lines/" + i;
            var account = accounts.find(companyId, line.accountId()).orElse(null);
            if (account == null
                    || !account.active()
                    || !account.postable()
                    || !"EXPENSE".equals(account.accountType())) {
                violations.add(FieldViolation.atPointer(
                        at + "/accountId", "INVALID_VALUE", "must be an active, postable EXPENSE account"));
            }
            if (line.amount().signum() <= 0
                    || line.amount().stripTrailingZeros().scale() > minorUnits) {
                violations.add(FieldViolation.atPointer(
                        at + "/amount", "INVALID_VALUE", "must be positive with at most " + minorUnits + " decimals"));
            }
            if (line.taxCodeId() != null) {
                TaxCodeSummary code = org.taxCode(companyId, line.taxCodeId()).orElse(null);
                if (code == null || !code.appliesToPurchases() || !code.usableOn(accountingDate)) {
                    violations.add(FieldViolation.atPointer(
                            at + "/taxCodeId",
                            "INVALID_TAX_CODE",
                            "must be a purchase tax code valid on " + accountingDate));
                } else {
                    rates.put(i, code.exempt() ? BigDecimal.ZERO : code.ratePercent());
                }
            }
        }
        if (!violations.isEmpty()) {
            throw ApiException.validationFailed("The expense is invalid.", violations);
        }
        TaxCalculator.Result result = taxes.calculate(new TaxCalculator.Request(
                java.util.stream.IntStream.range(0, command.lines().size())
                        .mapToObj(i -> {
                            AccountingCommands.ExpenseLine l = command.lines().get(i);
                            return new TaxCalculator.Line(
                                    BigDecimal.ONE,
                                    l.amount(),
                                    BigDecimal.ZERO,
                                    l.taxCodeId() == null ? null : new TaxCalculator.Rate(l.taxCodeId(), rates.get(i)));
                        })
                        .toList(),
                command.pricesIncludeTax(),
                context.rounding(currency),
                TaxCalculator.TaxRounding.valueOf(context.profile().taxRounding())));
        List<ExpenseRepository.NewLine> lines = new ArrayList<>();
        for (int i = 0; i < command.lines().size(); i++) {
            AccountingCommands.ExpenseLine l = command.lines().get(i);
            TaxCalculator.LineResult r = result.lines().get(i);
            lines.add(new ExpenseRepository.NewLine(
                    i + 1,
                    l.accountId(),
                    l.description(),
                    r.net(),
                    l.taxCodeId(),
                    r.tax(),
                    r.total(),
                    l.branchId(),
                    l.departmentId()));
        }
        BigDecimal rate = context.exchangeRate(currency, accountingDate);
        return new Draft(
                new ExpenseRepository.Header(
                        expenseDate,
                        accountingDate,
                        command.payeeName().strip(),
                        command.partnerId(),
                        bank.id(),
                        currency,
                        rate,
                        command.pricesIncludeTax(),
                        result.subtotal(),
                        result.taxTotal(),
                        result.total(),
                        context.baseRounding().round(result.total().multiply(rate)),
                        command.reference(),
                        command.notes()),
                lines);
    }

    private AccountingViews.Expense lock(UUID id, @Nullable String ifMatch, DocumentStatus.Action action) {
        AccountingViews.Expense current = expenses.lock(context.companyId(), id).orElseThrow(ApiException::notFound);
        EntityTags.requireMatch(ifMatch, current.version());
        if (!DocumentStatus.valueOf(current.status()).allows(action)) {
            throw new ApiException(
                    PlatformErrorCode.INVALID_STATE,
                    "A " + current.status() + " expense does not allow "
                            + action.name().toLowerCase(Locale.ROOT) + ".");
        }
        return current;
    }

    static List<AccountingCommands.ExpenseLine> readLines(JsonNode array) {
        return MergePatchLines.read(array, LINE_MEMBERS).stream()
                .map(v -> new AccountingCommands.ExpenseLine(
                        v.uuid("accountId"),
                        v.text("description"),
                        v.decimal("amount"),
                        v.uuid("taxCodeId"),
                        v.uuid("branchId"),
                        v.uuid("departmentId")))
                .toList();
    }
}
