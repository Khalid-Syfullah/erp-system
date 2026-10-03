package com.erp.sales.application;

import com.erp.org.api.CompanyProfile;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditPort;
import com.erp.platform.context.CurrentContext;
import com.erp.platform.numbering.DocumentNumberService;
import com.erp.platform.numbering.FiscalYears;
import com.erp.platform.web.ApiException;
import com.erp.platform.web.EntityTags;
import com.erp.platform.web.FieldViolation;
import com.erp.platform.web.MergePatch;
import com.erp.platform.web.PlatformErrorCode;
import com.erp.platform.web.paging.ListQuery;
import com.erp.platform.web.paging.PageResponse;
import com.erp.sales.SalesPermissions;
import com.erp.sales.domain.QuotationStatus;
import com.erp.sales.persistence.QuotationRepository;
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
 * Quotations (PRODUCT_SPEC.md §9.2): priced drafts (SAL-1), numbered when sent, then accepted by the
 * customer (which creates a draft sales order with the quoted prices), rejected, expired by the daily
 * job after {@code valid_until}, or cancelled. Only drafts are edited.
 */
@Service
public class QuotationService {

    static final Set<String> PATCHABLE = Set.of(
            "customerId",
            "warehouseId",
            "quotationDate",
            "validUntil",
            "currencyCode",
            "priceListId",
            "paymentTermsId",
            "notes",
            "lines");

    private final QuotationRepository quotations;
    private final SalesDrafts drafts;
    private final SalesOrderService orders;
    private final SalesSettingsService settings;
    private final SalesContext context;
    private final DocumentNumberService numbering;
    private final AuditPort audit;

    QuotationService(
            QuotationRepository quotations,
            SalesDrafts drafts,
            SalesOrderService orders,
            SalesSettingsService settings,
            SalesContext context,
            DocumentNumberService numbering,
            AuditPort audit) {
        this.quotations = quotations;
        this.drafts = drafts;
        this.orders = orders;
        this.settings = settings;
        this.context = context;
        this.numbering = numbering;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public PageResponse<SalesViews.Quotation> list(ListQuery query) {
        return quotations.list(
                CurrentContext.requireCompany(), CurrentContext.require().branchScope(), query);
    }

    @Transactional(readOnly = true)
    public SalesViews.QuotationDetail get(UUID id) {
        UUID companyId = CurrentContext.requireCompany();
        SalesViews.Quotation quotation = visible(quotations.find(companyId, id).orElseThrow(ApiException::notFound));
        return new SalesViews.QuotationDetail(quotation, quotations.lines(companyId, id));
    }

    @Transactional
    public SalesViews.QuotationDetail create(SalesCommands.Quotation command) {
        UUID companyId = CurrentContext.requireCompany();
        Prepared prepared = prepare(command, null, List.of());
        UUID actor = context.actor();
        UUID id = quotations.insert(companyId, prepared.header(), actor);
        quotations.insertLines(companyId, id, prepared.lines(), actor);
        audit.record(AuditEvent.builder("CREATE", "sales")
                .entity("quotation", id, null)
                .detail("customerId", command.customerId())
                .detail("currencyCode", prepared.header().currencyCode())
                .detail("total", prepared.header().total().toPlainString())
                .detail("lines", prepared.lines().size())
                .build());
        return get(id);
    }

    /** Edits a draft; {@code lines} replaces all lines and the quotation is repriced. */
    @Transactional
    public SalesViews.QuotationDetail patch(UUID id, @Nullable String ifMatch, JsonNode document) {
        UUID companyId = CurrentContext.requireCompany();
        SalesViews.Quotation current = lock(companyId, id, ifMatch, QuotationStatus.Action.EDIT);
        List<SalesViews.Line> oldLines = quotations.lines(companyId, id);
        MergePatch patch = MergePatch.of(document, PATCHABLE);
        var customer = patch.uuid("customerId", true);
        var warehouse = patch.uuid("warehouseId", true);
        var date = patch.date("quotationDate", true);
        var validUntil = patch.date("validUntil", true);
        var currency = patch.text("currencyCode", true, 3, v -> v.matches("^[A-Z]{3}$") ? null : "must be ISO 4217");
        var priceList = patch.uuid("priceListId", false);
        var terms = patch.uuid("paymentTermsId", false);
        var notes = patch.text("notes", false, 2000);
        patch.throwIfInvalid();
        List<SalesCommands.PricedLine> lines = document.has("lines")
                ? SalesDrafts.readLines(document.get("lines"))
                : oldLines.stream().map(SalesDrafts::asCommand).toList();
        SalesCommands.Quotation next = new SalesCommands.Quotation(
                Objects.requireNonNull(customer.orElse(current.customerId())),
                Objects.requireNonNull(warehouse.orElse(current.warehouseId())),
                date.orElse(current.quotationDate()),
                validUntil.orElse(current.validUntil()),
                currency.orElse(current.currencyCode()),
                priceList.orElse(current.priceListId()),
                terms.orElse(current.paymentTermsId()),
                notes.orElse(current.notes()),
                lines);
        Prepared prepared = prepare(next, current, oldLines);
        UUID actor = context.actor();
        quotations.deleteLines(companyId, id);
        quotations.insertLines(companyId, id, prepared.lines(), actor);
        if (!quotations.updateDraft(companyId, id, current.version(), actor, prepared.header())) {
            throw new ApiException(PlatformErrorCode.VERSION_CONFLICT, "The quotation was modified concurrently.");
        }
        SalesViews.Quotation after = quotations.find(companyId, id).orElseThrow();
        audit.record(AuditEvent.builder("UPDATE", "sales")
                .entity("quotation", id, current.number())
                .change("customerId", current.customerId(), after.customerId())
                .change("warehouseId", current.warehouseId(), after.warehouseId())
                .change("quotationDate", current.quotationDate(), after.quotationDate())
                .change("validUntil", current.validUntil(), after.validUntil())
                .change("currencyCode", current.currencyCode(), after.currencyCode())
                .change("priceListId", current.priceListId(), after.priceListId())
                .change("paymentTermsId", current.paymentTermsId(), after.paymentTermsId())
                .change("total", current.total().toPlainString(), after.total().toPlainString())
                .change("notes", current.notes(), after.notes())
                .detail("linesReplaced", document.has("lines"))
                .build());
        return get(id);
    }

    @Transactional
    public void delete(UUID id, @Nullable String ifMatch) {
        UUID companyId = CurrentContext.requireCompany();
        SalesViews.Quotation current = lock(companyId, id, ifMatch, QuotationStatus.Action.DELETE);
        if (!quotations.delete(companyId, id, current.version())) {
            throw new ApiException(PlatformErrorCode.VERSION_CONFLICT, "The quotation was modified concurrently.");
        }
        audit.record(AuditEvent.builder("DELETE", "sales")
                .entity("quotation", id, null)
                .build());
    }

    /** DRAFT → SENT; numbered (G-6). Sending by e-mail is a later phase (ADR-037). */
    @Transactional
    public SalesViews.QuotationDetail send(UUID id, @Nullable String ifMatch) {
        UUID companyId = CurrentContext.requireCompany();
        SalesViews.Quotation current = lock(companyId, id, ifMatch, QuotationStatus.Action.SEND);
        drafts.usableCustomer(current.customerId());
        CompanyProfile profile = context.profile();
        if (current.validUntil().isBefore(context.today(profile))) {
            throw ApiException.validationFailed(
                    "The quotation is no longer valid.",
                    List.of(FieldViolation.atPointer("/validUntil", "IN_THE_PAST", "must not be in the past")));
        }
        String number = numbering.next(
                companyId,
                SalesConfiguration.QUOTATION,
                FiscalYears.label(current.quotationDate(), profile.fiscalYearStartMonth()));
        transition(current, QuotationStatus.Action.SEND, number, null, null, null);
        return get(id);
    }

    /**
     * SENT → ACCEPTED (the customer accepted it within its validity): creates a draft sales order
     * with the quotation's lines and prices and links it. Returns the new order.
     */
    @Transactional
    public SalesViews.SalesOrderDetail accept(UUID id, @Nullable String ifMatch) {
        UUID companyId = CurrentContext.requireCompany();
        SalesViews.Quotation current = lock(companyId, id, ifMatch, QuotationStatus.Action.ACCEPT);
        context.require(SalesPermissions.ORDER_CREATE, "Accepting a quotation into a sales order");
        if (current.validUntil().isBefore(context.today(context.profile()))) {
            throw new ApiException(
                    PlatformErrorCode.INVALID_STATE,
                    "The quotation expired on " + current.validUntil() + "; it can no longer be accepted.");
        }
        SalesViews.SalesOrderDetail order = orders.createFromQuotation(current, quotations.lines(companyId, id));
        transition(current, QuotationStatus.Action.ACCEPT, null, order.order().id(), null, null);
        return order;
    }

    /** SENT → REJECTED with the customer's reason. */
    @Transactional
    public SalesViews.QuotationDetail reject(UUID id, @Nullable String ifMatch, String reason) {
        UUID companyId = CurrentContext.requireCompany();
        SalesViews.Quotation current = lock(companyId, id, ifMatch, QuotationStatus.Action.REJECT);
        transition(current, QuotationStatus.Action.REJECT, null, null, reason, reason);
        return get(id);
    }

    @Transactional
    public SalesViews.QuotationDetail cancel(UUID id, @Nullable String ifMatch, @Nullable String reason) {
        UUID companyId = CurrentContext.requireCompany();
        SalesViews.Quotation current = lock(companyId, id, ifMatch, QuotationStatus.Action.CANCEL);
        transition(current, QuotationStatus.Action.CANCEL, null, null, null, reason);
        return get(id);
    }

    // ------------------------------------------------------------------------------ helpers

    private record Prepared(QuotationRepository.Header header, List<QuotationRepository.NewLine> lines) {}

    private Prepared prepare(
            SalesCommands.Quotation command, SalesViews.@Nullable Quotation current, List<SalesViews.Line> approved) {
        CompanyProfile profile = context.profile();
        LocalDate date = command.quotationDate() != null ? command.quotationDate() : context.today(profile);
        LocalDate validUntil = command.validUntil() != null
                ? command.validUntil()
                : date.plusDays(
                        settings.current(CurrentContext.requireCompany()).quotationValidityDays());
        List<FieldViolation> violations = new ArrayList<>();
        if (validUntil.isBefore(date)) {
            violations.add(FieldViolation.atPointer(
                    "/validUntil", "BEFORE_QUOTATION_DATE", "must not be before the quotation date"));
        }
        SalesDrafts.Draft draft = drafts.build(
                new SalesDrafts.Input(
                        command.customerId(),
                        current == null ? null : current.customerId(),
                        command.warehouseId(),
                        date,
                        command.currencyCode(),
                        command.priceListId(),
                        null,
                        command.paymentTermsId(),
                        command.lines(),
                        approved,
                        violations),
                profile);
        return new Prepared(
                new QuotationRepository.Header(
                        command.customerId(),
                        draft.branchId(),
                        command.warehouseId(),
                        date,
                        validUntil,
                        draft.currencyCode(),
                        draft.priceListId(),
                        draft.pricesIncludeTax(),
                        draft.paymentTermsId(),
                        draft.totals().subtotal(),
                        draft.totals().taxTotal(),
                        draft.totals().total(),
                        command.notes()),
                draft.lines());
    }

    SalesViews.Quotation visible(SalesViews.Quotation quotation) {
        if (!context.canSeeBranch(quotation.branchId())) {
            throw ApiException.notFound();
        }
        return quotation;
    }

    private SalesViews.Quotation lock(
            UUID companyId, UUID id, @Nullable String ifMatch, QuotationStatus.Action action) {
        SalesViews.Quotation current = visible(quotations.lock(companyId, id).orElseThrow(ApiException::notFound));
        EntityTags.requireMatch(ifMatch, current.version());
        if (!QuotationStatus.valueOf(current.status()).allows(action)) {
            throw new ApiException(
                    PlatformErrorCode.INVALID_STATE,
                    "A " + current.status() + " quotation does not allow "
                            + action.name().toLowerCase(Locale.ROOT) + ".");
        }
        return current;
    }

    private void transition(
            SalesViews.Quotation current,
            QuotationStatus.Action action,
            @Nullable String number,
            @Nullable UUID salesOrderId,
            @Nullable String rejectionReason,
            @Nullable String detail) {
        QuotationStatus from = QuotationStatus.valueOf(current.status());
        QuotationStatus to = from.apply(action);
        if (!quotations.transition(
                current.companyId(),
                current.id(),
                current.version(),
                context.actor(),
                to.name(),
                number,
                salesOrderId,
                rejectionReason)) {
            throw new ApiException(PlatformErrorCode.VERSION_CONFLICT, "The quotation was modified concurrently.");
        }
        AuditEvent.Builder event = AuditEvent.builder("STATE_CHANGE", "sales")
                .entity("quotation", current.id(), number != null ? number : current.number())
                .transition(from.name(), to.name());
        if (salesOrderId != null) {
            event.detail("salesOrderId", salesOrderId);
        }
        if (detail != null && !detail.isBlank()) {
            event.detail("reason", detail);
        }
        audit.record(event.build());
    }
}
