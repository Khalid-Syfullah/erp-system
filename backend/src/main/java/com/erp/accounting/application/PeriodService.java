package com.erp.accounting.application;

import com.erp.accounting.domain.PeriodStatus;
import com.erp.accounting.persistence.CalendarRepository;
import com.erp.accounting.persistence.EntryRepository;
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
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Accounting periods (PRODUCT_SPEC.md §8.5). Soft close leaves posting to privileged users; close
 * needs no draft entries in the period, all earlier periods closed and a level trial balance, and
 * writes the period balance snapshots; reopen (with a reason, in an open year, latest closed period
 * first) deletes them. Close and reopen lock the period {@code FOR UPDATE}, so they serialize with
 * postings, which lock it {@code FOR SHARE} (DATABASE.md §9).
 */
@Service
public class PeriodService {

    private final CalendarRepository calendar;
    private final EntryRepository entries;
    private final LedgerRepository ledger;
    private final AccountingContext context;
    private final AuditPort audit;

    PeriodService(
            CalendarRepository calendar,
            EntryRepository entries,
            LedgerRepository ledger,
            AccountingContext context,
            AuditPort audit) {
        this.calendar = calendar;
        this.entries = entries;
        this.ledger = ledger;
        this.context = context;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public PageResponse<AccountingViews.Period> list(ListQuery query) {
        return calendar.listPeriods(context.companyId(), query);
    }

    @Transactional(readOnly = true)
    public AccountingViews.Period get(UUID id) {
        return calendar.findPeriod(context.companyId(), id).orElseThrow(ApiException::notFound);
    }

    @Transactional
    public AccountingViews.Period softClose(UUID id, @Nullable String ifMatch) {
        AccountingViews.Period period = lock(id, ifMatch, PeriodStatus.Action.SOFT_CLOSE);
        return transition(period, PeriodStatus.Action.SOFT_CLOSE, null);
    }

    @Transactional
    public AccountingViews.Period close(UUID id, @Nullable String ifMatch) {
        UUID companyId = context.companyId();
        AccountingViews.Period period = lock(id, ifMatch, PeriodStatus.Action.CLOSE);
        int drafts = entries.draftsInPeriod(companyId, period.id());
        if (drafts > 0) {
            throw new ApiException(
                    PlatformErrorCode.INVALID_STATE,
                    drafts + " draft journal entries are dated in the period; post, move or delete them first.");
        }
        List<AccountingViews.Period> earlier = calendar.unclosedBefore(companyId, period.startDate());
        if (!earlier.isEmpty()) {
            throw new ApiException(
                    PlatformErrorCode.INVALID_STATE,
                    "The period starting " + earlier.getFirst().startDate() + " must be closed first.");
        }
        var movements = ledger.movements(companyId, period.startDate(), period.endDate(), null);
        BigDecimal debit = BigDecimal.ZERO;
        BigDecimal credit = BigDecimal.ZERO;
        for (var m : movements.values()) {
            debit = debit.add(m.debit());
            credit = credit.add(m.credit());
        }
        if (debit.compareTo(credit) != 0) {
            throw new ApiException(
                    AccountingErrorCode.UNBALANCED_ENTRY,
                    "The trial balance of the period is not level (debits " + debit.toPlainString() + ", credits "
                            + credit.toPlainString() + ").");
        }
        ledger.writeSnapshot(companyId, period.id(), movements.values());
        return transition(period, PeriodStatus.Action.CLOSE, null);
    }

    @Transactional
    public AccountingViews.Period reopen(UUID id, @Nullable String ifMatch, String reason) {
        UUID companyId = context.companyId();
        AccountingViews.Period period = lock(id, ifMatch, PeriodStatus.Action.REOPEN);
        if (reason.isBlank()) {
            throw ApiException.validationFailed(
                    "A reason is required.", List.of(FieldViolation.atPointer("/reason", "REQUIRED", "is required")));
        }
        var year = calendar.findYear(companyId, period.fiscalYearId()).orElseThrow();
        if (!"OPEN".equals(year.status())) {
            throw new ApiException(
                    PlatformErrorCode.INVALID_STATE,
                    "Fiscal year " + year.code() + " is closed; its periods cannot be reopened.");
        }
        if ("CLOSED".equals(period.status())) {
            List<AccountingViews.Period> later = calendar.closedAfter(companyId, period.endDate());
            if (!later.isEmpty()) {
                throw new ApiException(
                        PlatformErrorCode.INVALID_STATE,
                        "The later period starting " + later.getLast().startDate() + " must be reopened first.");
            }
            ledger.deleteSnapshot(companyId, period.id());
        }
        return transition(period, PeriodStatus.Action.REOPEN, reason.strip());
    }

    private AccountingViews.Period lock(UUID id, @Nullable String ifMatch, PeriodStatus.Action action) {
        AccountingViews.Period period =
                calendar.lockPeriod(context.companyId(), id).orElseThrow(ApiException::notFound);
        EntityTags.requireMatch(ifMatch, period.version());
        if (!PeriodStatus.valueOf(period.status()).allows(action)) {
            throw new ApiException(
                    PlatformErrorCode.INVALID_STATE,
                    "A " + period.status() + " period does not allow "
                            + action.name().toLowerCase(Locale.ROOT).replace('_', ' ') + ".");
        }
        return period;
    }

    private AccountingViews.Period transition(
            AccountingViews.Period period, PeriodStatus.Action action, @Nullable String reason) {
        PeriodStatus to = PeriodStatus.valueOf(period.status()).apply(action);
        if (!calendar.transition(context.companyId(), period.id(), period.version(), to.name(), context.actor())) {
            throw new ApiException(PlatformErrorCode.VERSION_CONFLICT, "The period was modified concurrently.");
        }
        AuditEvent.Builder event = AuditEvent.builder("STATE_CHANGE", "accounting")
                .entity("period", period.id(), period.startDate() + "/" + period.endDate())
                .transition(period.status(), to.name());
        if (reason != null) {
            event.detail("reason", reason);
        }
        audit.record(event.build());
        return get(period.id());
    }
}
