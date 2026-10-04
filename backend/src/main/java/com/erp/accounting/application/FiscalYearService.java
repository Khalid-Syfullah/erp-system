package com.erp.accounting.application;

import com.erp.accounting.domain.AccountType;
import com.erp.accounting.domain.FiscalCalendar;
import com.erp.accounting.persistence.AccountRepository;
import com.erp.accounting.persistence.AccountingSettingsRepository;
import com.erp.accounting.persistence.CalendarRepository;
import com.erp.accounting.persistence.LedgerRepository;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditPort;
import com.erp.platform.web.ApiException;
import com.erp.platform.web.EntityTags;
import com.erp.platform.web.FieldViolation;
import com.erp.platform.web.PlatformErrorCode;
import com.erp.platform.web.paging.ListQuery;
import com.erp.platform.web.paging.PageResponse;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fiscal years (PRODUCT_SPEC.md §8.5): created with their twelve monthly periods, and closed at year
 * end once all periods are closed. The year-end close posts one CLOSING entry dated the last day of
 * the year that zeroes every revenue and expense account into retained earnings — the single entry
 * allowed into a closed period (ADR-038) — refreshes the last period's snapshot, marks the year
 * closed and opens the next year if it does not exist. Balance-sheet accounts carry forward because
 * balances are cumulative.
 */
@Service
public class FiscalYearService {

    private final CalendarRepository calendar;
    private final AccountRepository accounts;
    private final AccountingSettingsRepository settings;
    private final LedgerRepository ledger;
    private final PostingService posting;
    private final AccountingContext context;
    private final AuditPort audit;

    FiscalYearService(
            CalendarRepository calendar,
            AccountRepository accounts,
            AccountingSettingsRepository settings,
            LedgerRepository ledger,
            PostingService posting,
            AccountingContext context,
            AuditPort audit) {
        this.calendar = calendar;
        this.accounts = accounts;
        this.settings = settings;
        this.ledger = ledger;
        this.posting = posting;
        this.context = context;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public PageResponse<AccountingViews.FiscalYear> list(ListQuery query) {
        return calendar.years(context.companyId(), query);
    }

    @Transactional(readOnly = true)
    public AccountingViews.FiscalYearDetail get(UUID id) {
        UUID companyId = context.companyId();
        AccountingViews.FiscalYear year = calendar.findYear(companyId, id).orElseThrow(ApiException::notFound);
        return new AccountingViews.FiscalYearDetail(year, calendar.periods(companyId, id));
    }

    /** A new fiscal year starting on the first day of the company's fiscal start month. */
    @Transactional
    public AccountingViews.FiscalYearDetail create(LocalDate startDate) {
        UUID companyId = context.companyId();
        int startMonth = context.profile().fiscalYearStartMonth();
        if (startDate.getDayOfMonth() != 1 || startDate.getMonthValue() != startMonth) {
            throw ApiException.validationFailed(
                    "The fiscal year start is invalid.",
                    List.of(FieldViolation.atPointer(
                            "/startDate",
                            "INVALID_VALUE",
                            "must be the first day of the company's fiscal start month (" + startMonth + ")")));
        }
        LocalDate end = FiscalCalendar.yearEnd(startDate);
        if (calendar.yearOverlaps(companyId, startDate, end)) {
            throw new ApiException(PlatformErrorCode.CONFLICT, "A fiscal year covering these dates exists already.");
        }
        UUID id = open(companyId, startDate, context.actor());
        return get(id);
    }

    /** The year-end close; see the class comment. */
    @Transactional
    public AccountingViews.FiscalYearDetail close(UUID id, @Nullable String ifMatch) {
        UUID companyId = context.companyId();
        AccountingViews.FiscalYear year = calendar.lockYear(companyId, id).orElseThrow(ApiException::notFound);
        EntityTags.requireMatch(ifMatch, year.version());
        if (!"OPEN".equals(year.status())) {
            throw new ApiException(
                    PlatformErrorCode.INVALID_STATE, "Fiscal year " + year.code() + " is closed already.");
        }
        if (!calendar.openYearsBefore(companyId, year.startDate()).isEmpty()) {
            throw new ApiException(PlatformErrorCode.INVALID_STATE, "Earlier fiscal years must be closed first.");
        }
        List<AccountingViews.Period> periods = calendar.periods(companyId, id);
        List<AccountingViews.Period> open =
                periods.stream().filter(p -> !"CLOSED".equals(p.status())).toList();
        if (!open.isEmpty()) {
            throw new ApiException(
                    PlatformErrorCode.INVALID_STATE,
                    open.size() + " periods of fiscal year " + year.code() + " are not closed.");
        }
        UUID retained = settings.find(companyId).orElseThrow().retainedEarningsAccountId();
        String base = context.profile().baseCurrency();
        Map<UUID, BigDecimal> balances = ledger.balancesAsOf(companyId, year.endDate());
        List<PostingService.Line> lines = new ArrayList<>();
        BigDecimal result = BigDecimal.ZERO;
        for (AccountingViews.Account account : accounts.all(companyId)) {
            if (AccountType.valueOf(account.accountType()).balanceSheet()) {
                continue;
            }
            BigDecimal balance = balances.getOrDefault(account.id(), BigDecimal.ZERO);
            if (balance.signum() != 0) {
                lines.add(PostingService.Line.base(account.id(), balance.negate(), base));
                result = result.add(balance);
            }
        }
        UUID closingEntry = null;
        if (!lines.isEmpty()) {
            if (result.signum() != 0) {
                lines.add(PostingService.Line.base(retained, result, base).withDescription("Result of the year"));
            }
            closingEntry = posting.post(new PostingService.Request(
                            ChartTemplate.Journals.CLOSING,
                            year.endDate(),
                            "CLOSING",
                            "Year-end closing " + year.code(),
                            base,
                            BigDecimal.ONE,
                            new PostingService.Source("accounting", "FISCAL_YEAR", year.id(), year.code(), null),
                            null,
                            lines,
                            null,
                            false,
                            true))
                    .entryId();
            AccountingViews.Period last = periods.getLast();
            ledger.writeSnapshot(
                    companyId,
                    last.id(),
                    ledger.movements(companyId, last.startDate(), last.endDate(), null)
                            .values());
        }
        UUID actor = context.actor();
        if (!calendar.closeYear(companyId, id, year.version(), closingEntry, actor)) {
            throw new ApiException(PlatformErrorCode.VERSION_CONFLICT, "The fiscal year was modified concurrently.");
        }
        LocalDate nextStart = year.endDate().plusDays(1);
        if (calendar.yearStarting(companyId, nextStart).isEmpty()
                && !calendar.yearOverlaps(companyId, nextStart, FiscalCalendar.yearEnd(nextStart))) {
            open(companyId, nextStart, actor);
        }
        audit.record(AuditEvent.builder("CLOSE", "accounting")
                .entity("fiscal_year", id, year.code())
                .transition("OPEN", "CLOSED")
                .detail("closingEntryId", closingEntry)
                .detail("result", result.negate().toPlainString())
                .build());
        return get(id);
    }

    private UUID open(UUID companyId, LocalDate start, @Nullable UUID actor) {
        UUID id =
                calendar.insertYear(companyId, FiscalCalendar.code(start), start, FiscalCalendar.yearEnd(start), actor);
        for (FiscalCalendar.Period period : FiscalCalendar.periods(start)) {
            calendar.insertPeriod(companyId, id, period.number(), period.start(), period.end(), actor);
        }
        audit.record(AuditEvent.builder("CREATE", "accounting")
                .entity("fiscal_year", id, FiscalCalendar.code(start))
                .detail("startDate", start)
                .detail("endDate", FiscalCalendar.yearEnd(start))
                .build());
        return id;
    }
}
