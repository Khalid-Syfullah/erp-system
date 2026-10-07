package com.erp.accounting.application;

import com.erp.accounting.AccountingPermissions;
import com.erp.accounting.domain.EntryBalance;
import com.erp.accounting.domain.MappingKey;
import com.erp.accounting.domain.PeriodStatus;
import com.erp.accounting.persistence.AccountRepository;
import com.erp.accounting.persistence.AccountingSettingsRepository;
import com.erp.accounting.persistence.CalendarRepository;
import com.erp.accounting.persistence.EntryRepository;
import com.erp.accounting.persistence.JournalRepository;
import com.erp.accounting.persistence.OpenItemRepository;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditPort;
import com.erp.platform.money.RoundingPolicy;
import com.erp.platform.numbering.DocumentNumberService;
import com.erp.platform.numbering.NumberFormat;
import com.erp.platform.web.ApiException;
import com.erp.platform.web.FieldViolation;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one path into the general ledger (DATABASE.md §8.1, PRODUCT_SPEC.md §8.3). Every entry — from
 * an operational event, a payment, an expense, a manual entry, a reversal or the year-end close —
 * is validated (ACC-1, ACC-2, accounts postable, no manual lines on control accounts), dated into a
 * period that takes postings (ACC-4, the period row {@code FOR SHARE} so a concurrent close waits),
 * numbered gaplessly per journal and fiscal year, then written in the order the triggers expect:
 * header as DRAFT, lines, lines marked posted, header POSTED. It runs in the caller's transaction,
 * so the entry commits or rolls back with the document that caused it.
 */
@Component
class PostingService {

    static final int MAX_LINES = 10_000;
    static final String TYPE_PREFIX = "JOURNAL:";

    /** Entry types users create through the API: they never touch control accounts. */
    static final Set<String> USER_ENTRY_TYPES = Set.of("MANUAL", "ADJUSTMENT", "OPENING");

    /**
     * A line to post: {@code base} is signed (+ debit, − credit) in base currency, {@code amountCurrency}
     * the same signed amount in {@code currencyCode}. {@code newOpenItem} links the line to the open
     * item the request creates; {@code openItemId} to an existing one.
     */
    record Line(
            UUID accountId,
            BigDecimal base,
            String currencyCode,
            BigDecimal amountCurrency,
            @Nullable UUID partnerId,
            @Nullable UUID branchId,
            @Nullable UUID departmentId,
            @Nullable UUID taxCodeId,
            @Nullable String description,
            boolean newOpenItem,
            @Nullable UUID openItemId) {

        /** A line in base currency only. */
        static Line base(UUID accountId, BigDecimal base, String baseCurrency) {
            return new Line(accountId, base, baseCurrency, base, null, null, null, null, null, false, null);
        }

        Line withPartner(@Nullable UUID partner) {
            return new Line(
                    accountId,
                    base,
                    currencyCode,
                    amountCurrency,
                    partner,
                    branchId,
                    departmentId,
                    taxCodeId,
                    description,
                    newOpenItem,
                    openItemId);
        }

        Line withDimensions(@Nullable UUID branch, @Nullable UUID department, @Nullable UUID taxCode) {
            return new Line(
                    accountId,
                    base,
                    currencyCode,
                    amountCurrency,
                    partnerId,
                    branch,
                    department,
                    taxCode,
                    description,
                    newOpenItem,
                    openItemId);
        }

        Line withDescription(@Nullable String text) {
            return new Line(
                    accountId,
                    base,
                    currencyCode,
                    amountCurrency,
                    partnerId,
                    branchId,
                    departmentId,
                    taxCodeId,
                    text,
                    newOpenItem,
                    openItemId);
        }

        Line linkedToNewOpenItem() {
            return new Line(
                    accountId,
                    base,
                    currencyCode,
                    amountCurrency,
                    partnerId,
                    branchId,
                    departmentId,
                    taxCodeId,
                    description,
                    true,
                    openItemId);
        }

        Line linkedTo(@Nullable UUID item) {
            return new Line(
                    accountId,
                    base,
                    currencyCode,
                    amountCurrency,
                    partnerId,
                    branchId,
                    departmentId,
                    taxCodeId,
                    description,
                    newOpenItem,
                    item);
        }
    }

    /** The document an entry books, and the event it was booked from (ACC-5). */
    record Source(
            String module,
            String type,
            UUID id,
            @Nullable String number,
            @Nullable UUID eventId) {}

    /**
     * An entry to post.
     *
     * @param roundingAllowed system entries: an imbalance within the company tolerance is booked on
     *     ROUNDING_DIFFERENCE instead of refused
     * @param yearEndClosing the year-end CLOSING entry, which alone may enter a closed period
     */
    record Request(
            String journalCode,
            LocalDate date,
            String entryType,
            String description,
            String currencyCode,
            BigDecimal exchangeRate,
            @Nullable Source source,
            @Nullable UUID reversalOfId,
            List<Line> lines,
            OpenItemRepository.@Nullable NewItem openItem,
            boolean roundingAllowed,
            boolean yearEndClosing) {}

    record Posted(
            UUID entryId,
            String number,
            UUID periodId,
            @Nullable UUID openItemId) {}

    private final EntryRepository entries;
    private final AccountRepository accounts;
    private final CalendarRepository calendar;
    private final JournalRepository journals;
    private final OpenItemRepository openItems;
    private final AccountingSettingsRepository settings;
    private final AccountDetermination determination;
    private final AccountingContext context;
    private final DocumentNumberService numbering;
    private final AuditPort audit;

    PostingService(
            EntryRepository entries,
            AccountRepository accounts,
            CalendarRepository calendar,
            JournalRepository journals,
            OpenItemRepository openItems,
            AccountingSettingsRepository settings,
            AccountDetermination determination,
            AccountingContext context,
            DocumentNumberService numbering,
            AuditPort audit) {
        this.entries = entries;
        this.accounts = accounts;
        this.calendar = calendar;
        this.journals = journals;
        this.openItems = openItems;
        this.settings = settings;
        this.determination = determination;
        this.context = context;
        this.numbering = numbering;
        this.audit = audit;
    }

    /** Posts a new entry; see the class comment for the sequence. */
    @Transactional(propagation = Propagation.MANDATORY)
    Posted post(Request request) {
        UUID companyId = context.companyId();
        AccountingViews.Settings companySettings = companySettings(companyId);
        String baseCurrency = context.profile().baseCurrency();
        RoundingPolicy rounding = context.baseRounding();
        List<Line> lines = normalize(request.lines(), baseCurrency, rounding);
        if (request.roundingAllowed()) {
            BigDecimal tolerance = BigDecimal.ONE
                    .movePointLeft(rounding.minorUnits())
                    .multiply(BigDecimal.valueOf(companySettings.maxRoundingDifferenceMinorUnits()));
            EntryBalance.Totals totals = EntryBalance.totals(amounts(lines));
            try {
                EntryBalance.roundingLine(totals, tolerance)
                        .ifPresent(r -> lines.add(Line.base(
                                        determination.resolve(MappingKey.ROUNDING_DIFFERENCE), r.signed(), baseCurrency)
                                .withDescription("Rounding difference")));
            } catch (IllegalArgumentException e) {
                throw unbalanced(List.of(e.getMessage()));
            }
        }
        List<String> problems = EntryBalance.problems(amounts(lines));
        if (!problems.isEmpty()) {
            throw unbalanced(problems);
        }
        if (lines.size() > MAX_LINES) {
            throw unbalanced(List.of("An entry has at most " + MAX_LINES + " lines."));
        }
        checkAccounts(companyId, lines, request.entryType());
        AccountingViews.Period period =
                openPeriod(companyId, request.date(), request.entryType(), request.yearEndClosing(), companySettings);
        AccountingViews.Journal journal = journal(companyId, request.journalCode());
        String number = number(companyId, journal, period);
        EntryBalance.Totals totals = EntryBalance.totals(amounts(lines));
        UUID actor = context.actorOrNull();
        Source source = request.source();
        UUID id = entries.insertHeader(
                companyId,
                new EntryRepository.Header(
                        journal.id(),
                        request.date(),
                        period.id(),
                        request.entryType(),
                        request.description(),
                        request.currencyCode(),
                        request.exchangeRate(),
                        source == null ? null : source.module(),
                        source == null ? null : source.type(),
                        source == null ? null : source.id(),
                        source == null ? null : source.number(),
                        source == null ? null : source.eventId(),
                        request.reversalOfId()),
                totals.debit(),
                totals.credit(),
                actor);
        UUID openItemId =
                request.openItem() == null ? null : openItems.insert(companyId, request.openItem(), id, actor);
        List<EntryRepository.NewLine> rows = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            Line l = lines.get(i);
            EntryBalance.Amounts a = EntryBalance.Amounts.signed(l.base());
            rows.add(new EntryRepository.NewLine(
                    i + 1,
                    l.accountId(),
                    a.debit(),
                    a.credit(),
                    l.currencyCode(),
                    l.amountCurrency(),
                    l.partnerId(),
                    l.branchId(),
                    l.departmentId(),
                    l.taxCodeId(),
                    l.newOpenItem() ? openItemId : l.openItemId(),
                    l.description()));
        }
        entries.insertLines(companyId, id, request.date(), period.id(), rows, actor);
        finish(companyId, id, number, request.date(), period.id(), totals, actor);
        audit.record(AuditEvent.builder("POST", "accounting")
                .entity("journal_entry", id, number)
                .detail("journal", journal.code())
                .detail("entryType", request.entryType())
                .detail("entryDate", request.date())
                .detail("source", source == null ? null : source.module() + "/" + source.type() + "/" + source.id())
                .detail("sourceNumber", source == null ? null : source.number())
                .detail("reversalOfId", request.reversalOfId())
                .detail("totalDebit", totals.debit().toPlainString())
                .detail("totalCredit", totals.credit().toPlainString())
                .build());
        return new Posted(id, number, period.id(), openItemId);
    }

    /** Posts a manual draft as it is stored (no rounding line is added). */
    @Transactional(propagation = Propagation.MANDATORY)
    Posted postDraft(AccountingViews.JournalEntry draft, List<AccountingViews.JournalLine> stored) {
        UUID companyId = context.companyId();
        List<EntryBalance.Amounts> amounts = stored.stream()
                .map(l -> new EntryBalance.Amounts(l.debit(), l.credit()))
                .toList();
        List<String> problems = EntryBalance.problems(amounts);
        if (!problems.isEmpty()) {
            throw unbalanced(problems);
        }
        List<Line> lines = stored.stream()
                .map(l -> new Line(
                        l.accountId(),
                        l.debit().subtract(l.credit()),
                        l.currencyCode(),
                        l.amountCurrency(),
                        l.partnerId(),
                        l.branchId(),
                        l.departmentId(),
                        l.taxCodeId(),
                        l.description(),
                        false,
                        l.openItemId()))
                .toList();
        checkAccounts(companyId, lines, draft.entryType());
        AccountingViews.Period period =
                openPeriod(companyId, draft.entryDate(), draft.entryType(), false, companySettings(companyId));
        AccountingViews.Journal journal =
                journals.find(companyId, draft.journalId()).orElseThrow();
        if (!journal.active()) {
            throw ApiException.validationFailed(
                    "The journal is inactive.",
                    List.of(FieldViolation.atPointer("/journalId", "INACTIVE", "must be an active journal")));
        }
        String number = number(companyId, journal, period);
        EntryBalance.Totals totals = EntryBalance.totals(amounts);
        UUID actor = context.actorOrNull();
        finish(companyId, draft.id(), number, draft.entryDate(), period.id(), totals, actor);
        audit.record(AuditEvent.builder("POST", "accounting")
                .entity("journal_entry", draft.id(), number)
                .transition("DRAFT", "POSTED")
                .detail("journal", journal.code())
                .detail("entryType", draft.entryType())
                .detail("entryDate", draft.entryDate())
                .detail("totalDebit", totals.debit().toPlainString())
                .detail("totalCredit", totals.credit().toPlainString())
                .build());
        return new Posted(draft.id(), number, period.id(), null);
    }

    /**
     * Reverses a posted entry (ACC-3): a new REVERSAL entry in the same journal with every line's
     * sides swapped, linked both ways. The original stays untouched apart from the link.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    Posted reverse(AccountingViews.JournalEntry original, LocalDate date, String description, @Nullable Source source) {
        UUID companyId = context.companyId();
        if (!"POSTED".equals(original.status())) {
            throw new ApiException(
                    com.erp.platform.web.PlatformErrorCode.INVALID_STATE, "Only a posted entry can be reversed.");
        }
        if (original.reversedById() != null) {
            throw new ApiException(
                    com.erp.platform.web.PlatformErrorCode.INVALID_STATE, "The entry has already been reversed.");
        }
        List<Line> lines = new ArrayList<>();
        for (AccountingViews.JournalLine l : entries.lines(companyId, original.id())) {
            lines.add(new Line(
                    l.accountId(),
                    l.credit().subtract(l.debit()),
                    l.currencyCode(),
                    l.amountCurrency().negate(),
                    l.partnerId(),
                    l.branchId(),
                    l.departmentId(),
                    l.taxCodeId(),
                    l.description(),
                    false,
                    l.openItemId()));
        }
        String journalCode =
                journals.find(companyId, original.journalId()).orElseThrow().code();
        Posted posted = post(new Request(
                journalCode,
                date,
                "REVERSAL",
                description,
                original.currencyCode(),
                original.exchangeRate(),
                source,
                original.id(),
                lines,
                null,
                false,
                false));
        if (!entries.setReversedBy(companyId, original.id(), posted.entryId(), context.actorOrNull())) {
            throw new ApiException(
                    com.erp.platform.web.PlatformErrorCode.INVALID_STATE, "The entry has already been reversed.");
        }
        return posted;
    }

    /** The journal ID of a system journal code. */
    AccountingViews.Journal journal(UUID companyId, String code) {
        AccountingViews.Journal journal = journals.findByCode(companyId, code)
                .orElseThrow(() -> new ApiException(
                        AccountingErrorCode.ACCOUNT_MAPPING_MISSING,
                        "The company has no journal " + code + "; its accounting is not set up."));
        if (!journal.active()) {
            throw ApiException.validationFailed(
                    "The journal is inactive.",
                    List.of(FieldViolation.atPointer("/journalId", "INACTIVE", "must be an active journal")));
        }
        return journal;
    }

    // ------------------------------------------------------------------------------ helpers

    private void finish(
            UUID companyId,
            UUID id,
            String number,
            LocalDate date,
            UUID periodId,
            EntryBalance.Totals totals,
            @Nullable UUID actor) {
        entries.markLinesPosted(companyId, id, date, periodId);
        if (!entries.markPosted(companyId, id, number, totals.debit(), totals.credit(), actor)) {
            throw new ApiException(
                    com.erp.platform.web.PlatformErrorCode.VERSION_CONFLICT, "The entry was modified concurrently.");
        }
        entries.postingFlags(false, false);
    }

    private AccountingViews.Settings companySettings(UUID companyId) {
        return settings.find(companyId)
                .orElseThrow(() -> new ApiException(
                        AccountingErrorCode.ACCOUNT_MAPPING_MISSING, "The company's accounting is not set up."));
    }

    /**
     * ACC-4: the period containing the date, locked {@code FOR SHARE}. OPEN takes every posting;
     * SOFT_CLOSED only from users with {@code accounting.period.post_soft_closed} (manual entries only
     * if the company allows them there); CLOSED only the year-end closing entry.
     */
    private AccountingViews.Period openPeriod(
            UUID companyId,
            LocalDate date,
            String entryType,
            boolean yearEndClosing,
            AccountingViews.Settings companySettings) {
        AccountingViews.Period period = calendar.periodForPosting(companyId, date)
                .orElseThrow(() -> periodClosed("No accounting period covers " + date + ".", date, null));
        PeriodStatus status = PeriodStatus.valueOf(period.status());
        boolean privileged = context.isGranted(AccountingPermissions.PERIOD_POST_SOFT_CLOSED)
                && (!USER_ENTRY_TYPES.contains(entryType) || companySettings.allowManualEntriesInSoftClosed());
        if (status == PeriodStatus.CLOSED && yearEndClosing && "CLOSING".equals(entryType)) {
            entries.postingFlags(false, true);
            return period;
        }
        if (!status.takesPostings(privileged)) {
            throw periodClosed(
                    "The period " + period.startDate() + " – " + period.endDate() + " is " + period.status()
                            + " and takes no postings dated " + date + ".",
                    date,
                    period);
        }
        entries.postingFlags(status == PeriodStatus.SOFT_CLOSED, false);
        return period;
    }

    private static ApiException periodClosed(String message, LocalDate date, AccountingViews.@Nullable Period period) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("date", date.toString());
        if (period != null) {
            meta.put("periodId", period.id().toString());
            meta.put("status", period.status());
        }
        return new ApiException(
                AccountingErrorCode.PERIOD_CLOSED,
                message,
                List.of(new FieldViolation(
                        "/entryDate", null, AccountingErrorCode.PERIOD_CLOSED.code(), message, meta)));
    }

    /** Accounts exist, are active and postable, take the line's currency; control accounts take no manual lines. */
    private void checkAccounts(UUID companyId, List<Line> lines, String entryType) {
        Set<UUID> ids = new LinkedHashSet<>();
        lines.forEach(l -> ids.add(l.accountId()));
        Map<UUID, AccountingViews.Account> found = accounts.forPosting(companyId, ids);
        List<FieldViolation> notPostable = new ArrayList<>();
        List<FieldViolation> control = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            Line line = lines.get(i);
            AccountingViews.Account account = found.get(line.accountId());
            String at = "/lines/" + i + "/accountId";
            if (account == null || !account.active() || !account.postable()) {
                notPostable.add(FieldViolation.atPointer(
                        at, AccountingErrorCode.ACCOUNT_NOT_POSTABLE.code(), "must be an active, postable account"));
            } else if (account.currencyCode() != null && !account.currencyCode().equals(line.currencyCode())) {
                notPostable.add(FieldViolation.atPointer(
                        at,
                        AccountingErrorCode.ACCOUNT_NOT_POSTABLE.code(),
                        "takes postings in " + account.currencyCode() + " only"));
            } else if (account.control() && USER_ENTRY_TYPES.contains(entryType)) {
                control.add(FieldViolation.atPointer(
                        at,
                        AccountingErrorCode.CONTROL_ACCOUNT_MANUAL_POSTING.code(),
                        "is a control account; only system postings may use it"));
            }
        }
        if (!notPostable.isEmpty()) {
            throw new ApiException(
                    AccountingErrorCode.ACCOUNT_NOT_POSTABLE, "Some accounts take no postings.", notPostable);
        }
        if (!control.isEmpty()) {
            throw new ApiException(
                    AccountingErrorCode.CONTROL_ACCOUNT_MANUAL_POSTING,
                    "Manual entries cannot post to control accounts.",
                    control);
        }
    }

    private String number(UUID companyId, AccountingViews.Journal journal, AccountingViews.Period period) {
        String fiscalYear = calendar.findYear(companyId, period.fiscalYearId())
                .orElseThrow()
                .code();
        return numbering.next(
                companyId, TYPE_PREFIX + journal.code(), fiscalYear, new NumberFormat(journal.code() + "-{FY}-", 6));
    }

    /**
     * Merges lines with the same account and dimensions, rounds base amounts to the base currency's
     * minor units and drops lines that come to zero. A line whose document-currency amount does not
     * carry the base amount's sign (zero after rounding) is kept in base currency.
     */
    static List<Line> normalize(List<Line> lines, String baseCurrency, RoundingPolicy rounding) {
        Map<List<Object>, Line> merged = new LinkedHashMap<>();
        for (Line l : lines) {
            List<Object> key = new ArrayList<>();
            key.add(l.accountId());
            key.add(l.currencyCode());
            key.add(Objects.toString(l.partnerId()));
            key.add(Objects.toString(l.branchId()));
            key.add(Objects.toString(l.departmentId()));
            key.add(Objects.toString(l.taxCodeId()));
            key.add(Objects.toString(l.description()));
            key.add(l.newOpenItem());
            key.add(Objects.toString(l.openItemId()));
            key.add(l.base().signum() >= 0);
            merged.merge(
                    key,
                    l,
                    (a, b) -> new Line(
                            a.accountId(),
                            a.base().add(b.base()),
                            a.currencyCode(),
                            a.amountCurrency().add(b.amountCurrency()),
                            a.partnerId(),
                            a.branchId(),
                            a.departmentId(),
                            a.taxCodeId(),
                            a.description(),
                            a.newOpenItem(),
                            a.openItemId()));
        }
        List<Line> result = new ArrayList<>();
        for (Line l : merged.values()) {
            BigDecimal base = rounding.round(l.base());
            if (base.signum() == 0) {
                continue;
            }
            BigDecimal currency = l.amountCurrency().setScale(4, RoundingMode.HALF_UP);
            String code = l.currencyCode();
            if (currency.signum() != base.signum()) {
                code = baseCurrency;
                currency = base;
            }
            result.add(new Line(
                    l.accountId(),
                    base,
                    code,
                    currency,
                    l.partnerId(),
                    l.branchId(),
                    l.departmentId(),
                    l.taxCodeId(),
                    l.description(),
                    l.newOpenItem(),
                    l.openItemId()));
        }
        return result;
    }

    private static List<EntryBalance.Amounts> amounts(List<Line> lines) {
        return lines.stream().map(l -> EntryBalance.Amounts.signed(l.base())).toList();
    }

    private static ApiException unbalanced(List<String> problems) {
        return new ApiException(
                AccountingErrorCode.UNBALANCED_ENTRY,
                "The journal entry is invalid: " + String.join(" ", problems),
                problems.stream()
                        .map(p -> FieldViolation.atPointer("/lines", AccountingErrorCode.UNBALANCED_ENTRY.code(), p))
                        .toList());
    }
}
