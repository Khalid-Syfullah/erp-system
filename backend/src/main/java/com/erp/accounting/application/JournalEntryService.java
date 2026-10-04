package com.erp.accounting.application;

import com.erp.accounting.domain.EntryBalance;
import com.erp.accounting.domain.EntryStatus;
import com.erp.accounting.persistence.AccountRepository;
import com.erp.accounting.persistence.AccountingSettingsRepository;
import com.erp.accounting.persistence.CalendarRepository;
import com.erp.accounting.persistence.EntryRepository;
import com.erp.accounting.persistence.JournalRepository;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditPort;
import com.erp.platform.security.SegregationOfDuties;
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
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * Manual journal entries (PRODUCT_SPEC.md §8.4): drafts that may be edited or deleted, then posted
 * through the posting service — balanced, into an open period, never onto control accounts — by
 * someone other than their creator when the total reaches the company's approval threshold
 * (SoD). Posted entries are immutable; they are corrected by a reversal, a new entry linked both
 * ways. Entries booked from documents are corrected through their documents (credit note, debit
 * note, return, payment void), not reversed here.
 */
@Service
public class JournalEntryService {

    static final Set<String> USER_TYPES = PostingService.USER_ENTRY_TYPES;
    static final Set<String> PATCHABLE = Set.of("journalId", "entryDate", "description", "lines");
    static final List<MergePatchLines.Member> LINE_MEMBERS = List.of(
            MergePatchLines.Member.uuid("accountId", true),
            MergePatchLines.Member.decimal("debit", false),
            MergePatchLines.Member.decimal("credit", false),
            MergePatchLines.Member.text("currencyCode", 3),
            MergePatchLines.Member.decimal("amountCurrency", false),
            MergePatchLines.Member.uuid("partnerId", false),
            MergePatchLines.Member.uuid("branchId", false),
            MergePatchLines.Member.uuid("departmentId", false),
            MergePatchLines.Member.uuid("taxCodeId", false),
            MergePatchLines.Member.text("description", 500));

    private final EntryRepository entries;
    private final JournalRepository journals;
    private final CalendarRepository calendar;
    private final AccountRepository accounts;
    private final AccountingSettingsRepository settings;
    private final PostingService posting;
    private final AccountingContext context;
    private final AuditPort audit;

    JournalEntryService(
            EntryRepository entries,
            JournalRepository journals,
            CalendarRepository calendar,
            AccountRepository accounts,
            AccountingSettingsRepository settings,
            PostingService posting,
            AccountingContext context,
            AuditPort audit) {
        this.entries = entries;
        this.journals = journals;
        this.calendar = calendar;
        this.accounts = accounts;
        this.settings = settings;
        this.posting = posting;
        this.context = context;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public PageResponse<AccountingViews.JournalEntry> list(ListQuery query) {
        return entries.list(context.companyId(), query);
    }

    @Transactional(readOnly = true)
    public AccountingViews.JournalEntryDetail get(UUID id) {
        UUID companyId = context.companyId();
        AccountingViews.JournalEntry entry = entries.find(companyId, id).orElseThrow(ApiException::notFound);
        return new AccountingViews.JournalEntryDetail(entry, entries.lines(companyId, id));
    }

    /** A draft manual entry; {@code postImmediately} also posts it (needs the post permission). */
    @Transactional
    public AccountingViews.JournalEntryDetail create(AccountingCommands.JournalEntry command, boolean postImmediately) {
        UUID companyId = context.companyId();
        Draft draft = draft(command);
        UUID actor = context.actor();
        UUID id = entries.insertHeader(
                companyId,
                draft.header(),
                draft.totals().debit(),
                draft.totals().credit(),
                actor);
        entries.insertLines(
                companyId, id, draft.header().entryDate(), draft.header().periodId(), draft.lines(), actor);
        audit.record(AuditEvent.builder("CREATE", "accounting")
                .entity("journal_entry", id, null)
                .detail("entryType", command.entryType())
                .detail("entryDate", command.entryDate())
                .detail("totalDebit", draft.totals().debit().toPlainString())
                .detail("lines", draft.lines().size())
                .build());
        if (postImmediately) {
            context.require(com.erp.accounting.AccountingPermissions.JOURNAL_ENTRY_POST, "Posting a journal entry");
            return post(id, EntityTags.forVersion(0));
        }
        return get(id);
    }

    @Transactional
    public AccountingViews.JournalEntryDetail patch(UUID id, @Nullable String ifMatch, JsonNode document) {
        UUID companyId = context.companyId();
        AccountingViews.JournalEntry current = lock(id, ifMatch, EntryStatus.Action.EDIT);
        MergePatch patch = MergePatch.of(document, PATCHABLE);
        var journal = patch.uuid("journalId", true);
        var date = patch.date("entryDate", true);
        var description = patch.text("description", true, 500);
        patch.throwIfInvalid();
        List<AccountingCommands.EntryLine> lines = document.has("lines")
                ? readLines(document.get("lines"))
                : entries.lines(companyId, id).stream()
                        .map(JournalEntryService::asCommand)
                        .toList();
        AccountingCommands.JournalEntry next = new AccountingCommands.JournalEntry(
                journal.orElse(current.journalId()),
                Objects.requireNonNull(date.orElse(current.entryDate())),
                current.entryType(),
                Objects.requireNonNull(description.orElse(current.description())),
                lines);
        Draft draft = draft(next);
        UUID actor = context.actor();
        entries.deleteLines(companyId, id);
        entries.insertLines(
                companyId, id, draft.header().entryDate(), draft.header().periodId(), draft.lines(), actor);
        if (!entries.updateDraft(
                companyId,
                id,
                current.version(),
                draft.header(),
                draft.totals().debit(),
                draft.totals().credit(),
                actor)) {
            throw new ApiException(PlatformErrorCode.VERSION_CONFLICT, "The entry was modified concurrently.");
        }
        audit.record(AuditEvent.builder("UPDATE", "accounting")
                .entity("journal_entry", id, null)
                .change("entryDate", current.entryDate(), next.entryDate())
                .change("description", current.description(), next.description())
                .change(
                        "totalDebit",
                        current.totalDebit().toPlainString(),
                        draft.totals().debit().toPlainString())
                .detail("linesReplaced", document.has("lines"))
                .build());
        return get(id);
    }

    @Transactional
    public void delete(UUID id, @Nullable String ifMatch) {
        AccountingViews.JournalEntry current = lock(id, ifMatch, EntryStatus.Action.DELETE);
        if (!entries.deleteDraft(context.companyId(), id, current.version())) {
            throw new ApiException(PlatformErrorCode.VERSION_CONFLICT, "The entry was modified concurrently.");
        }
        audit.record(AuditEvent.builder("DELETE", "accounting")
                .entity("journal_entry", id, null)
                .build());
    }

    /** DRAFT → POSTED through the posting service; SoD above the company threshold. */
    @Transactional
    public AccountingViews.JournalEntryDetail post(UUID id, @Nullable String ifMatch) {
        UUID companyId = context.companyId();
        AccountingViews.JournalEntry current = lock(id, ifMatch, EntryStatus.Action.POST);
        BigDecimal threshold = settings.find(companyId).orElseThrow().manualEntryApprovalThresholdBase();
        if (threshold != null && current.totalDebit().compareTo(threshold) >= 0) {
            SegregationOfDuties.requireDifferentUsers(
                    context.actor(),
                    current.createdBy(),
                    "post a journal entry of " + current.totalDebit().toPlainString() + " that you created");
        }
        posting.postDraft(current, entries.lines(companyId, id));
        return get(id);
    }

    /** Reverses a posted manual entry on {@code date} (ACC-3). */
    @Transactional
    public AccountingViews.JournalEntryDetail reverse(
            UUID id, @Nullable String ifMatch, LocalDate date, String reason) {
        AccountingViews.JournalEntry current = lock(id, ifMatch, EntryStatus.Action.REVERSE);
        if (!USER_TYPES.contains(current.entryType())) {
            throw new ApiException(
                    PlatformErrorCode.INVALID_STATE,
                    "A " + current.entryType() + " entry is corrected through its source document"
                            + (current.sourceNumber() == null ? "" : " (" + current.sourceNumber() + ")") + ".");
        }
        if (reason.isBlank()) {
            throw ApiException.validationFailed(
                    "A reason is required.", List.of(FieldViolation.atPointer("/reason", "REQUIRED", "is required")));
        }
        PostingService.Posted reversal = posting.reverse(
                current,
                date,
                "Reversal of " + current.number() + ": " + reason.strip(),
                new PostingService.Source("accounting", "JOURNAL_ENTRY", current.id(), current.number(), null));
        audit.record(AuditEvent.builder("REVERSE", "accounting")
                .entity("journal_entry", id, current.number())
                .detail("reversalEntryId", reversal.entryId())
                .detail("reversalNumber", reversal.number())
                .detail("reversalDate", date)
                .detail("reason", reason.strip())
                .build());
        return get(reversal.entryId());
    }

    // ------------------------------------------------------------------------------ helpers

    private record Draft(
            EntryRepository.Header header, List<EntryRepository.NewLine> lines, EntryBalance.Totals totals) {}

    private Draft draft(AccountingCommands.JournalEntry command) {
        UUID companyId = context.companyId();
        List<FieldViolation> violations = new ArrayList<>();
        if (!USER_TYPES.contains(command.entryType())) {
            violations.add(
                    FieldViolation.atPointer("/entryType", "INVALID_VALUE", "must be MANUAL, ADJUSTMENT or OPENING"));
        }
        if (command.description().isBlank() || command.description().length() > 500) {
            violations.add(FieldViolation.atPointer("/description", "INVALID_VALUE", "must be 1 to 500 characters"));
        }
        AccountingViews.Journal journal = command.journalId() == null
                ? journals.findByCode(companyId, ChartTemplate.Journals.GENERAL).orElseThrow()
                : journals.find(companyId, command.journalId()).orElse(null);
        if (journal == null || !journal.active()) {
            violations.add(FieldViolation.atPointer("/journalId", "INVALID_VALUE", "must be an active journal"));
        }
        AccountingViews.Period period =
                calendar.periodContaining(companyId, command.entryDate()).orElse(null);
        if (period == null) {
            violations.add(FieldViolation.atPointer(
                    "/entryDate", AccountingErrorCode.PERIOD_CLOSED.code(), "lies in no accounting period"));
        }
        String base = context.profile().baseCurrency();
        int minorUnits = context.baseRounding().minorUnits();
        if (command.lines().size() < 2 || command.lines().size() > 500) {
            violations.add(FieldViolation.atPointer("/lines", "SIZE", "must contain between 2 and 500 lines"));
        }
        List<EntryRepository.NewLine> lines = new ArrayList<>();
        List<EntryBalance.Amounts> amounts = new ArrayList<>();
        for (int i = 0; i < command.lines().size(); i++) {
            AccountingCommands.EntryLine l = command.lines().get(i);
            String at = "/lines/" + i;
            boolean oneSide = (l.debit().signum() > 0 && l.credit().signum() == 0)
                    || (l.credit().signum() > 0 && l.debit().signum() == 0);
            if (!oneSide) {
                violations.add(FieldViolation.atPointer(
                        at, "ONE_SIDE", "must have exactly one positive amount, debit or credit"));
                continue;
            }
            BigDecimal signed = l.debit().subtract(l.credit());
            if (signed.stripTrailingZeros().scale() > minorUnits) {
                violations.add(FieldViolation.atPointer(
                        at, "INVALID_VALUE", "has more decimals than the base currency (" + minorUnits + ")"));
            }
            var account = accounts.find(companyId, l.accountId()).orElse(null);
            if (account == null || !account.active() || !account.postable()) {
                violations.add(FieldViolation.atPointer(
                        at + "/accountId",
                        AccountingErrorCode.ACCOUNT_NOT_POSTABLE.code(),
                        "must be an active, postable account"));
            } else if (account.control()) {
                violations.add(FieldViolation.atPointer(
                        at + "/accountId",
                        AccountingErrorCode.CONTROL_ACCOUNT_MANUAL_POSTING.code(),
                        "is a control account; only system postings may use it"));
            }
            String currency = l.currencyCode() == null ? base : l.currencyCode();
            BigDecimal amountCurrency =
                    currency.equals(base) ? signed : l.amountCurrency() == null ? BigDecimal.ZERO : l.amountCurrency();
            if (amountCurrency.signum() != signed.signum()) {
                violations.add(FieldViolation.atPointer(
                        at + "/amountCurrency",
                        "INVALID_VALUE",
                        "is required in " + currency + " with the sign of the line (debit +, credit −)"));
            }
            amounts.add(new EntryBalance.Amounts(l.debit(), l.credit()));
            lines.add(new EntryRepository.NewLine(
                    i + 1,
                    l.accountId(),
                    l.debit(),
                    l.credit(),
                    currency,
                    amountCurrency,
                    l.partnerId(),
                    l.branchId(),
                    l.departmentId(),
                    l.taxCodeId(),
                    null,
                    l.description()));
        }
        if (!violations.isEmpty()) {
            throw ApiException.validationFailed("The journal entry is invalid.", violations);
        }
        return new Draft(
                new EntryRepository.Header(
                        Objects.requireNonNull(journal).id(),
                        command.entryDate(),
                        Objects.requireNonNull(period).id(),
                        command.entryType(),
                        command.description().strip(),
                        base,
                        BigDecimal.ONE,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null),
                lines,
                EntryBalance.totals(amounts));
    }

    private AccountingViews.JournalEntry lock(UUID id, @Nullable String ifMatch, EntryStatus.Action action) {
        AccountingViews.JournalEntry current =
                entries.lock(context.companyId(), id).orElseThrow(ApiException::notFound);
        EntityTags.requireMatch(ifMatch, current.version());
        if (!EntryStatus.valueOf(current.status()).allows(action)) {
            throw new ApiException(
                    PlatformErrorCode.INVALID_STATE,
                    "A " + current.status() + " journal entry does not allow "
                            + action.name().toLowerCase(Locale.ROOT) + "; posted entries are corrected by reversal.");
        }
        return current;
    }

    static List<AccountingCommands.EntryLine> readLines(JsonNode array) {
        return MergePatchLines.read(array, LINE_MEMBERS).stream()
                .map(v -> new AccountingCommands.EntryLine(
                        v.uuid("accountId"),
                        v.decimal("debit") == null ? BigDecimal.ZERO : v.decimal("debit"),
                        v.decimal("credit") == null ? BigDecimal.ZERO : v.decimal("credit"),
                        v.text("currencyCode"),
                        v.decimal("amountCurrency"),
                        v.uuid("partnerId"),
                        v.uuid("branchId"),
                        v.uuid("departmentId"),
                        v.uuid("taxCodeId"),
                        v.text("description")))
                .toList();
    }

    private static AccountingCommands.EntryLine asCommand(AccountingViews.JournalLine l) {
        return new AccountingCommands.EntryLine(
                l.accountId(),
                l.debit(),
                l.credit(),
                l.currencyCode(),
                l.amountCurrency(),
                l.partnerId(),
                l.branchId(),
                l.departmentId(),
                l.taxCodeId(),
                l.description());
    }
}
