package com.erp.accounting.application;

import com.erp.accounting.api.AccountingReports;
import com.erp.accounting.domain.DocumentStatus;
import com.erp.accounting.domain.MappingKey;
import com.erp.accounting.domain.MappingKey.ScopeType;
import com.erp.accounting.persistence.AccountRepository;
import com.erp.accounting.persistence.EntryRepository;
import com.erp.accounting.persistence.OpenItemRepository;
import com.erp.accounting.persistence.PaymentRepository;
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
import java.util.HashSet;
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
 * Payments (PRODUCT_SPEC.md §8.6–§8.8): customer receipts (INBOUND) and supplier payments (OUTBOUND)
 * through a bank or cash account, in its currency. Posting books the whole amount against the
 * partner's control account (Dr bank / Cr AR, or Dr AP / Cr bank) and creates the payment's own open
 * item for it (negative), from which the requested allocations settle the partner's items; what is
 * left stays on account (ADR-014). A void reverses the allocations and the entry; a posted payment is
 * never edited. The payment row is locked first, then the open items in ID order.
 */
@Service
public class PaymentService {

    static final Set<String> PATCHABLE =
            Set.of("bankAccountId", "paymentDate", "amount", "method", "reference", "notes", "allocations");
    static final Set<String> METHODS = Set.of("CASH", "BANK_TRANSFER", "CHEQUE", "CARD", "OTHER");
    static final List<MergePatchLines.Member> ALLOCATION_MEMBERS =
            List.of(MergePatchLines.Member.uuid("openItemId", true), MergePatchLines.Member.decimal("amount", true));

    private final PaymentRepository payments;
    private final OpenItemRepository openItems;
    private final EntryRepository entries;
    private final AccountRepository accounts;
    private final CompanyBankAccountService bankAccounts;
    private final SubledgerService subledger;
    private final PostingService posting;
    private final AccountDetermination determination;
    private final PartnersFacade partners;
    private final AccountingContext context;
    private final DocumentNumberService numbering;
    private final AuditPort audit;

    PaymentService(
            PaymentRepository payments,
            OpenItemRepository openItems,
            EntryRepository entries,
            AccountRepository accounts,
            CompanyBankAccountService bankAccounts,
            SubledgerService subledger,
            PostingService posting,
            AccountDetermination determination,
            PartnersFacade partners,
            AccountingContext context,
            DocumentNumberService numbering,
            AuditPort audit) {
        this.payments = payments;
        this.openItems = openItems;
        this.entries = entries;
        this.accounts = accounts;
        this.bankAccounts = bankAccounts;
        this.subledger = subledger;
        this.posting = posting;
        this.determination = determination;
        this.partners = partners;
        this.context = context;
        this.numbering = numbering;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public PageResponse<AccountingViews.Payment> list(ListQuery query) {
        return payments.list(context.companyId(), query);
    }

    @Transactional(readOnly = true)
    public AccountingViews.PaymentDetail get(UUID id) {
        UUID companyId = context.companyId();
        AccountingViews.Payment payment = payments.find(companyId, id).orElseThrow(ApiException::notFound);
        AccountingViews.OpenItem item = payment.openItemId() == null
                ? null
                : openItems.find(companyId, payment.openItemId()).orElse(null);
        return new AccountingViews.PaymentDetail(payment, item, payments.allocations(companyId, id));
    }

    @Transactional
    public AccountingViews.PaymentDetail create(AccountingCommands.Payment command) {
        UUID companyId = context.companyId();
        PaymentRepository.Values values = values(command);
        UUID id = payments.insert(companyId, values, context.actor());
        audit.record(AuditEvent.builder("CREATE", "accounting")
                .entity("payment", id, null)
                .detail("direction", values.direction())
                .detail("partnerId", values.partnerId())
                .detail("bankAccountId", values.bankAccountId())
                .detail("amount", values.amount().toPlainString())
                .detail("currencyCode", values.currencyCode())
                .detail("allocations", values.allocations().size())
                .build());
        return get(id);
    }

    @Transactional
    public AccountingViews.PaymentDetail patch(UUID id, @Nullable String ifMatch, JsonNode document) {
        UUID companyId = context.companyId();
        AccountingViews.Payment current = lock(id, ifMatch, DocumentStatus.Action.EDIT);
        MergePatch patch = MergePatch.of(document, PATCHABLE);
        var bank = patch.uuid("bankAccountId", true);
        var date = patch.date("paymentDate", true);
        var amount = patch.decimal("amount", new BigDecimal("0.0001"), new BigDecimal("999999999999999"), 4);
        var method = patch.text("method", true, 20, v -> METHODS.contains(v) ? null : "is not a payment method");
        var reference = patch.text("reference", false, 100);
        var notes = patch.text("notes", false, 2000);
        patch.throwIfInvalid();
        List<AccountingCommands.Allocation> allocations = document.has("allocations")
                ? MergePatchLines.read(document.get("allocations"), ALLOCATION_MEMBERS).stream()
                        .map(v -> new AccountingCommands.Allocation(v.uuid("openItemId"), v.decimal("amount")))
                        .toList()
                : current.requestedAllocations().stream()
                        .map(a -> new AccountingCommands.Allocation(a.openItemId(), a.amount()))
                        .toList();
        PaymentRepository.Values next = values(new AccountingCommands.Payment(
                current.direction(),
                Objects.requireNonNull(current.partnerId()),
                Objects.requireNonNull(bank.orElse(current.bankAccountId())),
                date.orElse(current.paymentDate()),
                Objects.requireNonNull(amount.orElse(current.amount())),
                Objects.requireNonNull(method.orElse(current.method())),
                reference.orElse(current.reference()),
                notes.orElse(current.notes()),
                allocations));
        if (!payments.updateDraft(companyId, id, current.version(), next, context.actor())) {
            throw new ApiException(PlatformErrorCode.VERSION_CONFLICT, "The payment was modified concurrently.");
        }
        audit.record(AuditEvent.builder("UPDATE", "accounting")
                .entity("payment", id, null)
                .change("bankAccountId", current.bankAccountId(), next.bankAccountId())
                .change("paymentDate", current.paymentDate(), next.paymentDate())
                .change(
                        "amount",
                        current.amount().toPlainString(),
                        next.amount().toPlainString())
                .change("method", current.method(), next.method())
                .change("reference", current.reference(), next.reference())
                .detail("allocationsReplaced", document.has("allocations"))
                .build());
        return get(id);
    }

    @Transactional
    public void delete(UUID id, @Nullable String ifMatch) {
        AccountingViews.Payment current = lock(id, ifMatch, DocumentStatus.Action.DELETE);
        if (!payments.delete(context.companyId(), id, current.version())) {
            throw new ApiException(PlatformErrorCode.VERSION_CONFLICT, "The payment was modified concurrently.");
        }
        audit.record(AuditEvent.builder("DELETE", "accounting")
                .entity("payment", id, null)
                .build());
    }

    /** DRAFT → POSTED; see the class comment. */
    @Transactional
    public AccountingViews.PaymentDetail post(UUID id, @Nullable String ifMatch) {
        UUID companyId = context.companyId();
        AccountingViews.Payment payment = lock(id, ifMatch, DocumentStatus.Action.POST);
        boolean inbound = payment.inbound();
        UUID partnerId = Objects.requireNonNull(payment.partnerId());
        UUID group = partnerGroup(inbound, partnerId);
        AccountingReports.BankAccount bank = bankAccounts.forUse(payment.bankAccountId(), "/bankAccountId");
        if (!bank.currencyCode().equals(payment.currencyCode())) {
            throw ApiException.validationFailed(
                    "The payment currency differs from the bank account's.",
                    List.of(FieldViolation.atPointer(
                            "/bankAccountId", "CURRENCY_MISMATCH", "must be in the payment's currency")));
        }
        BigDecimal rate = context.exchangeRate(payment.currencyCode(), payment.paymentDate());
        BigDecimal amountBase = context.baseRounding().round(payment.amount().multiply(rate));
        UUID control = determination.resolve(
                inbound ? MappingKey.AR_CONTROL : MappingKey.AP_CONTROL,
                AccountDetermination.of(ScopeType.PARTNER_GROUP, group));
        String journal = "CASH"
                        .equals(accounts.find(companyId, bank.accountId())
                                .orElseThrow()
                                .accountSubtype())
                ? ChartTemplate.Journals.CASH
                : ChartTemplate.Journals.BANK;
        String number = numbering.next(
                companyId,
                inbound ? AccountingConfiguration.CUSTOMER_RECEIPT : AccountingConfiguration.SUPPLIER_PAYMENT,
                FiscalYears.label(payment.paymentDate(), context.profile().fiscalYearStartMonth()));
        BigDecimal sign = inbound ? BigDecimal.ONE : BigDecimal.ONE.negate();
        List<PostingService.Line> lines = List.of(
                new PostingService.Line(
                        bank.accountId(),
                        amountBase.multiply(sign),
                        payment.currencyCode(),
                        payment.amount().multiply(sign),
                        null,
                        null,
                        null,
                        null,
                        payment.reference(),
                        false,
                        null),
                new PostingService.Line(
                                control,
                                amountBase.multiply(sign).negate(),
                                payment.currencyCode(),
                                payment.amount().multiply(sign).negate(),
                                partnerId,
                                null,
                                null,
                                null,
                                payment.reference(),
                                false,
                                null)
                        .linkedToNewOpenItem());
        PostingService.Posted posted = posting.post(new PostingService.Request(
                journal,
                payment.paymentDate(),
                "SYSTEM",
                (inbound ? "Customer receipt " : "Supplier payment ") + number,
                payment.currencyCode(),
                rate,
                new PostingService.Source("accounting", "PAYMENT", payment.id(), number, null),
                null,
                lines,
                new OpenItemRepository.NewItem(
                        inbound ? "RECEIVABLE" : "PAYABLE",
                        partnerId,
                        control,
                        "accounting",
                        "PAYMENT",
                        payment.id(),
                        number,
                        payment.paymentDate(),
                        payment.paymentDate(),
                        payment.currencyCode(),
                        payment.amount().negate(),
                        amountBase.negate(),
                        rate),
                false,
                false));
        UUID actor = context.actor();
        if (!payments.markPosted(
                companyId,
                id,
                payment.version(),
                number,
                rate,
                amountBase,
                posted.entryId(),
                Objects.requireNonNull(posted.openItemId()),
                actor)) {
            throw new ApiException(PlatformErrorCode.VERSION_CONFLICT, "The payment was modified concurrently.");
        }
        allocate(payment.id(), posted.openItemId(), toCommands(payment.requestedAllocations()), payment.paymentDate());
        audit.record(AuditEvent.builder("POST", "accounting")
                .entity("payment", id, number)
                .transition("DRAFT", "POSTED")
                .detail("journalEntryId", posted.entryId())
                .detail("amountBase", amountBase.toPlainString())
                .detail("exchangeRate", rate.toPlainString())
                .build());
        return get(id);
    }

    /** Allocates more of a posted payment to the partner's open items. */
    @Transactional
    public AccountingViews.PaymentDetail allocate(
            UUID id, @Nullable String ifMatch, List<AccountingCommands.Allocation> allocations) {
        UUID companyId = context.companyId();
        AccountingViews.Payment payment = lock(id, ifMatch, DocumentStatus.Action.ALLOCATE);
        if (allocations.isEmpty()) {
            throw ApiException.validationFailed(
                    "No allocations given.",
                    List.of(FieldViolation.atPointer("/allocations", "SIZE", "must contain at least one allocation")));
        }
        allocate(payment.id(), Objects.requireNonNull(payment.openItemId()), allocations, context.today());
        if (!payments.touch(companyId, id, payment.version(), context.actor())) {
            throw new ApiException(PlatformErrorCode.VERSION_CONFLICT, "The payment was modified concurrently.");
        }
        return get(id);
    }

    /**
     * Voids a posted payment (PRODUCT_SPEC.md §8.8): its allocations are undone (their FX entries
     * reversed), the payment entry is reversed on {@code date} and its open item closed as VOIDED.
     */
    @Transactional
    public AccountingViews.PaymentDetail voidPayment(UUID id, @Nullable String ifMatch, String reason) {
        UUID companyId = context.companyId();
        AccountingViews.Payment payment = lock(id, ifMatch, DocumentStatus.Action.VOID);
        if (reason.isBlank()) {
            throw ApiException.validationFailed(
                    "A reason is required.", List.of(FieldViolation.atPointer("/reason", "REQUIRED", "is required")));
        }
        LocalDate date = context.today();
        UUID itemId = Objects.requireNonNull(payment.openItemId());
        for (AccountingViews.Allocation allocation : openItems.allocationsOf(companyId, itemId)) {
            if (allocation.active()) {
                subledger.undo(allocation, date);
            }
        }
        AccountingViews.JournalEntry entry = entries.lock(companyId, Objects.requireNonNull(payment.journalEntryId()))
                .orElseThrow();
        PostingService.Posted reversal = posting.reverse(
                entry,
                date,
                "Void of payment " + payment.number() + ": " + reason.strip(),
                new PostingService.Source("accounting", "PAYMENT_VOID", payment.id(), payment.number(), null));
        AccountingViews.OpenItem item =
                openItems.lock(companyId, List.of(itemId)).get(itemId);
        openItems.updateOpen(companyId, item.id(), BigDecimal.ZERO, BigDecimal.ZERO, "VOIDED", context.actor());
        if (!payments.markVoided(
                companyId, id, payment.version(), reversal.entryId(), reason.strip(), context.actor())) {
            throw new ApiException(PlatformErrorCode.VERSION_CONFLICT, "The payment was modified concurrently.");
        }
        audit.record(AuditEvent.builder("VOID", "accounting")
                .entity("payment", id, payment.number())
                .transition("POSTED", "VOIDED")
                .detail("reason", reason.strip())
                .detail("reversalEntryId", reversal.entryId())
                .build());
        return get(id);
    }

    // ------------------------------------------------------------------------------ helpers

    /** Settles open items from the payment's own item, in request order, all items locked by ID. */
    private void allocate(
            UUID paymentId, UUID paymentItemId, List<AccountingCommands.Allocation> allocations, LocalDate date) {
        if (allocations.isEmpty()) {
            return;
        }
        UUID companyId = context.companyId();
        List<UUID> ids = new ArrayList<>();
        ids.add(paymentItemId);
        allocations.forEach(a -> ids.add(a.openItemId()));
        Map<UUID, AccountingViews.OpenItem> locked = openItems.lock(companyId, ids);
        for (int i = 0; i < allocations.size(); i++) {
            AccountingCommands.Allocation a = allocations.get(i);
            AccountingViews.OpenItem target = locked.get(a.openItemId());
            if (target == null) {
                throw ApiException.validationFailed(
                        "The open item is unknown.",
                        List.of(FieldViolation.atPointer(
                                "/allocations/" + i + "/openItemId", "UNKNOWN_OPEN_ITEM", "is not an open item")));
            }
            AccountingViews.OpenItem counter =
                    openItems.find(companyId, paymentItemId).orElseThrow();
            subledger.allocate(paymentId, target, counter, a.amount(), date, "/allocations/" + i);
        }
    }

    private PaymentRepository.Values values(AccountingCommands.Payment command) {
        List<FieldViolation> violations = new ArrayList<>();
        boolean inbound = "INBOUND".equals(command.direction());
        if (!inbound && !"OUTBOUND".equals(command.direction())) {
            violations.add(FieldViolation.atPointer("/direction", "INVALID_VALUE", "must be INBOUND or OUTBOUND"));
        }
        if (!METHODS.contains(command.method())) {
            violations.add(FieldViolation.atPointer("/method", "INVALID_VALUE", "is not a payment method"));
        }
        partnerGroupOrViolation(inbound, command.partnerId(), violations);
        AccountingReports.BankAccount bank = bankAccounts.forUse(command.bankAccountId(), "/bankAccountId");
        LocalDate date = command.paymentDate() != null ? command.paymentDate() : context.today();
        int minorUnits = context.rounding(bank.currencyCode()).minorUnits();
        if (command.amount().signum() <= 0
                || command.amount().stripTrailingZeros().scale() > minorUnits) {
            violations.add(FieldViolation.atPointer(
                    "/amount", "INVALID_VALUE", "must be positive with at most " + minorUnits + " decimals"));
        }
        BigDecimal allocated = BigDecimal.ZERO;
        Set<UUID> seen = new HashSet<>();
        for (int i = 0; i < command.allocations().size(); i++) {
            AccountingCommands.Allocation a = command.allocations().get(i);
            String at = "/allocations/" + i;
            AccountingViews.OpenItem item =
                    openItems.find(context.companyId(), a.openItemId()).orElse(null);
            if (item == null
                    || !item.partnerId().equals(command.partnerId())
                    || item.receivable() != inbound
                    || !item.currencyCode().equals(bank.currencyCode())
                    || item.originalAmount().signum() <= 0) {
                violations.add(FieldViolation.atPointer(
                        at + "/openItemId",
                        AccountingErrorCode.ALLOCATION_INVALID.code(),
                        "must be an open invoice or bill of the partner in the payment's currency"));
            } else if (!seen.add(item.id())) {
                violations.add(FieldViolation.atPointer(at + "/openItemId", "DUPLICATE", "appears twice"));
            } else if (a.amount().signum() <= 0 || a.amount().compareTo(item.openAmount()) > 0) {
                violations.add(FieldViolation.atPointer(
                        at + "/amount",
                        AccountingErrorCode.ALLOCATION_INVALID.code(),
                        "must be positive and at most the open amount "
                                + item.openAmount().toPlainString()));
            }
            allocated = allocated.add(a.amount());
        }
        if (allocated.compareTo(command.amount()) > 0) {
            violations.add(FieldViolation.atPointer(
                    "/allocations", AccountingErrorCode.ALLOCATION_INVALID.code(), "exceed the payment amount"));
        }
        if (!violations.isEmpty()) {
            throw ApiException.validationFailed("The payment is invalid.", violations);
        }
        BigDecimal rate = context.exchangeRate(bank.currencyCode(), date);
        return new PaymentRepository.Values(
                inbound ? "INBOUND" : "OUTBOUND",
                command.partnerId(),
                inbound ? "CUSTOMER" : "SUPPLIER",
                bank.id(),
                date,
                bank.currencyCode(),
                command.amount(),
                rate,
                context.baseRounding().round(command.amount().multiply(rate)),
                command.method(),
                command.reference(),
                command.notes(),
                command.allocations().stream()
                        .map(a -> new AccountingViews.RequestedAllocation(a.openItemId(), a.amount()))
                        .toList());
    }

    /** The partner's group (for the control account): a customer for receipts, a supplier for payments. */
    private @Nullable UUID partnerGroup(boolean inbound, UUID partnerId) {
        List<FieldViolation> violations = new ArrayList<>();
        UUID group = partnerGroupOrViolation(inbound, partnerId, violations);
        if (!violations.isEmpty()) {
            throw ApiException.validationFailed("The partner is invalid.", violations);
        }
        return group;
    }

    private @Nullable UUID partnerGroupOrViolation(boolean inbound, UUID partnerId, List<FieldViolation> violations) {
        if (inbound) {
            var customer = partners.customer(partnerId);
            if (customer.isEmpty()) {
                violations.add(FieldViolation.atPointer("/partnerId", "UNKNOWN_CUSTOMER", "is not a customer"));
                return null;
            }
            return customer.get().customerGroupId();
        }
        var supplier = partners.supplier(partnerId);
        if (supplier.isEmpty()) {
            violations.add(FieldViolation.atPointer("/partnerId", "UNKNOWN_SUPPLIER", "is not a supplier"));
            return null;
        }
        return supplier.get().supplierGroupId();
    }

    private static List<AccountingCommands.Allocation> toCommands(List<AccountingViews.RequestedAllocation> requested) {
        return requested.stream()
                .map(a -> new AccountingCommands.Allocation(a.openItemId(), a.amount()))
                .toList();
    }

    private AccountingViews.Payment lock(UUID id, @Nullable String ifMatch, DocumentStatus.Action action) {
        AccountingViews.Payment current = payments.lock(context.companyId(), id).orElseThrow(ApiException::notFound);
        EntityTags.requireMatch(ifMatch, current.version());
        if (!DocumentStatus.valueOf(current.status()).allows(action)) {
            throw new ApiException(
                    PlatformErrorCode.INVALID_STATE,
                    "A " + current.status() + " payment does not allow "
                            + action.name().toLowerCase(Locale.ROOT) + ".");
        }
        return current;
    }
}
