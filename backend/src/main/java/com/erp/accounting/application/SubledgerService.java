package com.erp.accounting.application;

import com.erp.accounting.domain.MappingKey;
import com.erp.accounting.domain.Settlement;
import com.erp.accounting.persistence.EntryRepository;
import com.erp.accounting.persistence.OpenItemRepository;
import com.erp.accounting.persistence.PaymentRepository;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditPort;
import com.erp.platform.web.ApiException;
import com.erp.platform.web.FieldViolation;
import com.erp.platform.web.PlatformErrorCode;
import com.erp.platform.web.paging.ListQuery;
import com.erp.platform.web.paging.PageResponse;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The AR/AP subledger (PRODUCT_SPEC.md §8.7): open items and the allocations that settle them. An
 * allocation reduces a positive item (invoice, bill) and a negative one (payment, credit or debit
 * note, on-account remainder) of the same partner, kind and currency (ADR-022) by the same amount;
 * the difference of their base reductions is the realized FX difference, posted against the control
 * account so that it always equals Σ open items in base currency (ACC-6). Undoing an allocation
 * restores both items and reverses the FX entry. Items are locked {@code FOR UPDATE} in ID order.
 */
@Service
public class SubledgerService {

    private final OpenItemRepository openItems;
    private final PaymentRepository payments;
    private final EntryRepository entries;
    private final PostingService posting;
    private final AccountDetermination determination;
    private final AccountingContext context;
    private final AuditPort audit;

    SubledgerService(
            OpenItemRepository openItems,
            PaymentRepository payments,
            EntryRepository entries,
            PostingService posting,
            AccountDetermination determination,
            AccountingContext context,
            AuditPort audit) {
        this.openItems = openItems;
        this.payments = payments;
        this.entries = entries;
        this.posting = posting;
        this.determination = determination;
        this.context = context;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public PageResponse<AccountingViews.OpenItem> list(String kind, ListQuery query) {
        return openItems.list(context.companyId(), kind, query);
    }

    @Transactional(readOnly = true)
    public AccountingViews.OpenItem get(String kind, UUID id) {
        return openItems
                .find(context.companyId(), id)
                .filter(i -> i.kind().equals(kind))
                .orElseThrow(ApiException::notFound);
    }

    @Transactional(readOnly = true)
    public List<AccountingViews.Allocation> allocations(String kind, UUID id) {
        get(kind, id);
        return openItems.allocationsOf(context.companyId(), id);
    }

    /** Nets a credit note, debit note or on-account remainder against an invoice or bill. */
    @Transactional
    public AccountingViews.Allocation net(UUID targetId, UUID counterId, BigDecimal amount) {
        UUID companyId = context.companyId();
        Map<UUID, AccountingViews.OpenItem> items = openItems.lock(companyId, List.of(targetId, counterId));
        AccountingViews.OpenItem target = items.get(targetId);
        AccountingViews.OpenItem counter = items.get(counterId);
        if (target == null || counter == null) {
            throw ApiException.notFound();
        }
        UUID paymentId = payments.byOpenItem(companyId, counterId)
                .map(AccountingViews.Payment::id)
                .orElse(null);
        return allocate(paymentId, target, counter, amount, context.today(), "/debitItemId");
    }

    /**
     * Allocates {@code amount} of the negative item {@code counter} to the positive item {@code
     * target}; both must be locked by the caller. Returns the allocation.
     */
    AccountingViews.Allocation allocate(
            @Nullable UUID paymentId,
            AccountingViews.OpenItem target,
            AccountingViews.OpenItem counter,
            BigDecimal amount,
            LocalDate date,
            String pointer) {
        UUID companyId = context.companyId();
        String problem = null;
        if (target.originalAmount().signum() <= 0 || counter.originalAmount().signum() >= 0) {
            problem =
                    "must pair an item to settle (invoice, bill) with a settling item (payment, credit or debit note)";
        } else if (!target.kind().equals(counter.kind()) || !target.partnerId().equals(counter.partnerId())) {
            problem = "must be items of the same partner and kind";
        } else if (!target.currencyCode().equals(counter.currencyCode())) {
            problem = "must be items in the same currency (ADR-022)";
        } else if (amount.signum() <= 0
                || amount.stripTrailingZeros().scale() > 4
                || amount.compareTo(target.openAmount().abs()) > 0
                || amount.compareTo(counter.openAmount().abs()) > 0) {
            problem = "must be positive and at most what is open on both items ("
                    + target.openAmount().abs().min(counter.openAmount().abs()).toPlainString() + ")";
        }
        if (problem != null) {
            throw new ApiException(
                    AccountingErrorCode.ALLOCATION_INVALID,
                    "The allocation is invalid: " + problem + ".",
                    List.of(FieldViolation.atPointer(pointer, AccountingErrorCode.ALLOCATION_INVALID.code(), problem)));
        }
        boolean receivable = target.receivable();
        var rounding = context.baseRounding();
        Settlement.Result result = Settlement.allocate(
                new Settlement.Open(
                        target.openAmount().abs(), target.openAmountBase().abs(), target.exchangeRate()),
                new Settlement.Open(
                        counter.openAmount().abs(), counter.openAmountBase().abs(), counter.exchangeRate()),
                amount,
                receivable,
                rounding.minorUnits(),
                rounding.mode());
        UUID actor = context.actorOrNull();
        update(
                target,
                target.openAmount().subtract(amount),
                target.openAmountBase().subtract(result.targetBase()));
        update(
                counter,
                counter.openAmount().add(amount),
                counter.openAmountBase().add(result.counterBase()));
        UUID allocationId = openItems.newId();
        UUID fxEntry = null;
        if (result.fxDifferenceBase().signum() != 0) {
            fxEntry = fxEntry(allocationId, target, result.fxDifferenceBase(), date, paymentId);
        }
        openItems.insertAllocation(
                companyId,
                allocationId,
                new OpenItemRepository.NewAllocation(
                        paymentId,
                        target.id(),
                        counter.id(),
                        date,
                        amount,
                        result.targetBase(),
                        result.fxDifferenceBase(),
                        fxEntry),
                actor);
        audit.record(AuditEvent.builder("ALLOCATE", "accounting")
                .entity("payment_allocation", allocationId, target.documentNumber())
                .detail("openItemId", target.id())
                .detail("counterOpenItemId", counter.id())
                .detail("paymentId", paymentId)
                .detail("amount", amount.toPlainString())
                .detail("fxDifferenceBase", result.fxDifferenceBase().toPlainString())
                .build());
        return openItems.allocationsOf(companyId, target.id()).stream()
                .filter(a -> a.id().equals(allocationId))
                .findFirst()
                .orElseThrow();
    }

    /**
     * Undoes an allocation: both items get their amounts back and the FX entry is reversed. The
     * payment (a document header) is locked before the allocation, as voiding does (ARCHITECTURE.md
     * §6.2).
     */
    @Transactional
    public void unallocate(UUID allocationId) {
        UUID companyId = context.companyId();
        UUID paymentId = openItems
                .findAllocation(companyId, allocationId)
                .orElseThrow(ApiException::notFound)
                .paymentId();
        if (paymentId != null) {
            AccountingViews.Payment payment =
                    payments.lock(companyId, paymentId).orElseThrow();
            if (!"POSTED".equals(payment.status())) {
                throw new ApiException(PlatformErrorCode.INVALID_STATE, "The payment is " + payment.status() + ".");
            }
            payments.touch(companyId, payment.id(), payment.version(), context.actor());
        }
        AccountingViews.Allocation allocation =
                openItems.lockAllocation(companyId, allocationId).orElseThrow(ApiException::notFound);
        undo(allocation, context.today());
    }

    /** Undoes an allocation whose payment (if any) the caller has locked. */
    void undo(AccountingViews.Allocation allocation, LocalDate date) {
        UUID companyId = context.companyId();
        if (!allocation.active()) {
            throw new ApiException(PlatformErrorCode.INVALID_STATE, "The allocation has been undone already.");
        }
        Map<UUID, AccountingViews.OpenItem> items =
                openItems.lock(companyId, List.of(allocation.openItemId(), allocation.counterOpenItemId()));
        AccountingViews.OpenItem target = items.get(allocation.openItemId());
        AccountingViews.OpenItem counter = items.get(allocation.counterOpenItemId());
        BigDecimal fx = allocation.fxDifferenceBase();
        BigDecimal counterBase = allocation.amountBase().add(target.receivable() ? fx.negate() : fx);
        update(
                target,
                target.openAmount().add(allocation.amount()),
                target.openAmountBase().add(allocation.amountBase()));
        update(
                counter,
                counter.openAmount().subtract(allocation.amount()),
                counter.openAmountBase().subtract(counterBase));
        UUID reversal = null;
        if (allocation.journalEntryId() != null) {
            AccountingViews.JournalEntry fxEntry =
                    entries.lock(companyId, allocation.journalEntryId()).orElseThrow();
            reversal = posting.reverse(
                            fxEntry,
                            date,
                            "Reversal of FX difference: allocation to " + target.documentNumber() + " undone",
                            new PostingService.Source(
                                    "accounting",
                                    "ALLOCATION_REVERSAL",
                                    allocation.id(),
                                    target.documentNumber(),
                                    null))
                    .entryId();
        }
        openItems.markAllocationReversed(companyId, allocation.id(), reversal, context.actor());
        audit.record(AuditEvent.builder("UNALLOCATE", "accounting")
                .entity("payment_allocation", allocation.id(), target.documentNumber())
                .detail("openItemId", target.id())
                .detail("counterOpenItemId", counter.id())
                .detail("amount", allocation.amount().toPlainString())
                .detail("reversalJournalEntryId", reversal)
                .build());
    }

    /** Sets an item's open amounts and the status they imply. */
    void update(AccountingViews.OpenItem item, BigDecimal open, BigDecimal openBase) {
        String status = open.signum() == 0
                ? "SETTLED"
                : open.compareTo(item.originalAmount()) == 0 ? "OPEN" : "PARTIALLY_SETTLED";
        openItems.updateOpen(
                context.companyId(),
                item.id(),
                open,
                open.signum() == 0 ? BigDecimal.ZERO : openBase,
                status,
                context.actorOrNull());
    }

    /**
     * The realized FX entry of an allocation: a loss (+) debits FX_REALIZED_LOSS and credits the
     * control account, a gain (−) the reverse — for AR and AP alike, seen from the control account.
     */
    private UUID fxEntry(
            UUID allocationId,
            AccountingViews.OpenItem target,
            BigDecimal fx,
            LocalDate date,
            @Nullable UUID paymentId) {
        String base = context.profile().baseCurrency();
        UUID fxAccount =
                determination.resolve(fx.signum() > 0 ? MappingKey.FX_REALIZED_LOSS : MappingKey.FX_REALIZED_GAIN);
        List<PostingService.Line> lines = List.of(
                PostingService.Line.base(fxAccount, fx, base).withDescription("Realized FX difference"),
                PostingService.Line.base(target.accountId(), fx.negate(), base)
                        .withPartner(target.partnerId())
                        .linkedTo(target.id()));
        return posting.post(new PostingService.Request(
                        ChartTemplate.Journals.GENERAL,
                        date,
                        "SYSTEM",
                        "Realized FX difference on " + target.documentNumber(),
                        base,
                        BigDecimal.ONE,
                        new PostingService.Source(
                                "accounting", "ALLOCATION", allocationId, target.documentNumber(), null),
                        null,
                        lines,
                        null,
                        false,
                        false))
                .entryId();
    }
}
