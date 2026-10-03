package com.erp.sales.application;

import com.erp.inventory.api.InventoryFacade;
import com.erp.org.api.CompanyProfile;
import com.erp.org.api.OrgFacade;
import com.erp.org.api.TaxCalculator;
import com.erp.partners.api.PartnersFacade;
import com.erp.partners.api.PartnersFacade.CustomerInfo;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditPort;
import com.erp.platform.context.CurrentContext;
import com.erp.platform.events.DomainEvents;
import com.erp.platform.money.RoundingPolicy;
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
import com.erp.sales.SalesPermissions;
import com.erp.sales.api.InvoiceSettlementPort;
import com.erp.sales.domain.DocumentStatus;
import com.erp.sales.domain.SalesOrderStatus;
import com.erp.sales.events.InvoicePosted;
import com.erp.sales.persistence.DeliveryRepository;
import com.erp.sales.persistence.InvoiceRepository;
import com.erp.sales.persistence.SalesOrderRepository;
import com.erp.sales.persistence.SalesReturnRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * Customer invoices and credit notes (PRODUCT_SPEC.md §9, SAL-5, SAL-6). An invoice bills lines of
 * one sales order up to their invoiceable quantity (DELIVERED policy: delivered − returned −
 * invoiced; ORDERED: ordered − returned − invoiced), or — with {@code sales.invoice.create_direct} —
 * service products without an order. A credit note credits lines of a posted invoice up to the
 * quantity not yet credited, optionally for the goods of a received sales return (which gives the
 * order line back its invoiceable quantity). Posting locks the document, then the order, then the
 * credited invoice; it books nothing itself: Accounting (Phase 8) books the AR entry and open item
 * from {@code sales.invoice.posted} / {@code sales.credit_note.posted} (ADR-005, ADR-037).
 */
@Service
public class InvoiceService {

    static final String INVOICE = "INVOICE";
    static final String CREDIT_NOTE = "CREDIT_NOTE";
    static final Set<String> PATCHABLE = Set.of("invoiceDate", "accountingDate", "dueDate", "notes", "lines");
    static final List<MergePatchLines.Member> LINE_MEMBERS = List.of(
            MergePatchLines.Member.uuid("salesOrderLineId", false),
            MergePatchLines.Member.uuid("originalInvoiceLineId", false),
            MergePatchLines.Member.uuid("salesReturnLineId", false),
            MergePatchLines.Member.uuid("variantId", false),
            MergePatchLines.Member.text("description", 300),
            MergePatchLines.Member.decimal("quantity", true),
            MergePatchLines.Member.uuid("uomId", false),
            MergePatchLines.Member.decimal("unitPrice", false),
            MergePatchLines.Member.decimal("discountPercent", false),
            MergePatchLines.Member.uuid("taxCodeId", false),
            MergePatchLines.Member.uuid("branchId", false),
            MergePatchLines.Member.uuid("departmentId", false));

    private final InvoiceRepository invoices;
    private final SalesOrderRepository orders;
    private final DeliveryRepository deliveries;
    private final SalesReturnRepository returns;
    private final SalesOrderService orderService;
    private final SalesPricing pricing;
    private final SalesDrafts drafts;
    private final PartnersFacade partners;
    private final InventoryFacade inventory;
    private final OrgFacade org;
    private final TaxCalculator taxes;
    private final SalesConfiguration.Receivables receivables;
    private final SalesContext context;
    private final DocumentNumberService numbering;
    private final ApplicationEventPublisher events;
    private final AuditPort audit;
    private final Clock clock;

    InvoiceService(
            InvoiceRepository invoices,
            SalesOrderRepository orders,
            DeliveryRepository deliveries,
            SalesReturnRepository returns,
            SalesOrderService orderService,
            SalesPricing pricing,
            SalesDrafts drafts,
            PartnersFacade partners,
            InventoryFacade inventory,
            OrgFacade org,
            TaxCalculator taxes,
            SalesConfiguration.Receivables receivables,
            SalesContext context,
            DocumentNumberService numbering,
            ApplicationEventPublisher events,
            AuditPort audit,
            Clock clock) {
        this.invoices = invoices;
        this.orders = orders;
        this.deliveries = deliveries;
        this.returns = returns;
        this.orderService = orderService;
        this.pricing = pricing;
        this.drafts = drafts;
        this.partners = partners;
        this.inventory = inventory;
        this.org = org;
        this.taxes = taxes;
        this.receivables = receivables;
        this.context = context;
        this.numbering = numbering;
        this.events = events;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PageResponse<SalesViews.Invoice> list(ListQuery query) {
        return invoices.list(CurrentContext.requireCompany(), query);
    }

    @Transactional(readOnly = true)
    public SalesViews.InvoiceDetail get(UUID id) {
        UUID companyId = CurrentContext.requireCompany();
        SalesViews.Invoice invoice = invoices.find(companyId, id).orElseThrow(ApiException::notFound);
        return new SalesViews.InvoiceDetail(invoice, invoices.lines(companyId, id), invoices.taxes(companyId, id));
    }

    /** How much of a posted invoice is still open, from Accounting ({@code UNKNOWN} until Phase 8). */
    @Transactional(readOnly = true)
    public InvoiceSettlementPort.Settlement settlement(UUID id) {
        UUID companyId = CurrentContext.requireCompany();
        SalesViews.Invoice invoice = invoices.find(companyId, id).orElseThrow(ApiException::notFound);
        return receivables.settlement(companyId, invoice.id());
    }

    @Transactional
    public SalesViews.InvoiceDetail create(SalesCommands.Invoice command) {
        UUID companyId = CurrentContext.requireCompany();
        Draft draft = draft(command, null);
        UUID actor = context.actor();
        UUID id = invoices.insert(companyId, draft.header(), actor);
        invoices.insertLines(companyId, id, draft.lines(), actor);
        invoices.replaceTaxes(companyId, id, draft.taxes());
        audit.record(AuditEvent.builder("CREATE", "sales")
                .entity("invoice", id, null)
                .detail("documentType", command.documentType())
                .detail("customerId", command.customerId())
                .detail("salesOrderId", draft.header().salesOrderId())
                .detail("originalInvoiceId", command.originalInvoiceId())
                .detail("salesReturnId", command.salesReturnId())
                .detail("total", draft.header().total().toPlainString())
                .detail("lines", draft.lines().size())
                .build());
        return get(id);
    }

    /**
     * A draft invoice of everything still invoiceable on an order (SAL-5); with {@code deliveryIds},
     * only what those deliveries delivered and was not returned.
     */
    @Transactional
    public SalesViews.InvoiceDetail fromOrder(UUID salesOrderId, @Nullable List<UUID> deliveryIds) {
        UUID companyId = CurrentContext.requireCompany();
        SalesViews.SalesOrder order = orders.find(companyId, salesOrderId)
                .filter(o -> context.canSeeBranch(o.branchId()))
                .orElseThrow(() -> ApiException.validationFailed(
                        "The sales order is unknown.",
                        List.of(FieldViolation.atPointer(
                                "/salesOrderId", "UNKNOWN_SALES_ORDER", "is not a sales order you can use"))));
        requireInvoiceable(order);
        Map<UUID, BigDecimal> deliveredCap = null;
        Map<UUID, UUID> deliveryLineOf = new HashMap<>();
        if (deliveryIds != null && !deliveryIds.isEmpty()) {
            deliveredCap = new HashMap<>();
            Map<UUID, Integer> count = new HashMap<>();
            for (int i = 0; i < deliveryIds.size(); i++) {
                SalesViews.Delivery delivery =
                        deliveries.find(companyId, deliveryIds.get(i)).orElse(null);
                if (delivery == null
                        || !delivery.salesOrderId().equals(order.id())
                        || !"POSTED".equals(delivery.status())) {
                    throw ApiException.validationFailed(
                            "The delivery is invalid.",
                            List.of(FieldViolation.atPointer(
                                    "/deliveryIds/" + i, "INVALID_VALUE", "must be a posted delivery of the order")));
                }
                for (SalesViews.DeliveryLine line : deliveries.lines(companyId, delivery.id())) {
                    deliveredCap.merge(
                            line.salesOrderLineId(),
                            line.quantityBase().subtract(line.returnedQuantityBase()),
                            BigDecimal::add);
                    count.merge(line.salesOrderLineId(), 1, Integer::sum);
                    deliveryLineOf.put(line.salesOrderLineId(), line.id());
                }
            }
            count.forEach((orderLine, n) -> {
                if (n > 1) {
                    deliveryLineOf.remove(orderLine);
                }
            });
        }
        List<SalesCommands.InvoiceLine> lines = new ArrayList<>();
        for (SalesViews.SalesOrderLine line : orders.lines(companyId, order.id())) {
            BigDecimal quantity = SalesOrderService.invoiceable(order, line);
            if (deliveredCap != null) {
                if (!line.stockable() || !deliveredCap.containsKey(line.id())) {
                    continue;
                }
                quantity = quantity.min(deliveredCap.get(line.id()));
            }
            if (quantity.signum() <= 0) {
                continue;
            }
            boolean whole = quantity.compareTo(line.line().quantityBase()) == 0;
            UUID baseUom =
                    inventory.variantInfo(line.line().variantId()).orElseThrow().baseUomId();
            lines.add(new SalesCommands.InvoiceLine(
                    line.id(),
                    null,
                    null,
                    null,
                    null,
                    whole ? line.line().quantity() : quantity,
                    whole ? line.line().uomId() : baseUom,
                    null,
                    null,
                    null,
                    null,
                    null));
        }
        if (lines.isEmpty()) {
            throw new ApiException(PlatformErrorCode.INVALID_STATE, "Nothing on this order is left to invoice.");
        }
        SalesCommands.Invoice command = new SalesCommands.Invoice(
                INVOICE, order.customerId(), null, null, null, order.id(), null, null, null, lines);
        Draft draft = draft(command, null, deliveryLineOf);
        UUID actor = context.actor();
        UUID id = invoices.insert(companyId, draft.header(), actor);
        invoices.insertLines(companyId, id, draft.lines(), actor);
        invoices.replaceTaxes(companyId, id, draft.taxes());
        audit.record(AuditEvent.builder("CREATE", "sales")
                .entity("invoice", id, null)
                .detail("documentType", INVOICE)
                .detail("salesOrderId", order.id())
                .detail("deliveryIds", deliveryIds == null ? null : deliveryIds.toString())
                .detail("total", draft.header().total().toPlainString())
                .detail("lines", draft.lines().size())
                .build());
        return get(id);
    }

    /** Edits a draft; {@code lines} replaces all lines and the document is repriced. */
    @Transactional
    public SalesViews.InvoiceDetail patch(UUID id, @Nullable String ifMatch, JsonNode document) {
        UUID companyId = CurrentContext.requireCompany();
        SalesViews.Invoice current = lock(companyId, id, ifMatch, DocumentStatus.Action.EDIT);
        List<SalesViews.InvoiceLine> oldLines = invoices.lines(companyId, id);
        MergePatch patch = MergePatch.of(document, PATCHABLE);
        var invoiceDate = patch.date("invoiceDate", true);
        var accountingDate = patch.date("accountingDate", true);
        var dueDate = patch.date("dueDate", true);
        var notes = patch.text("notes", false, 2000);
        patch.throwIfInvalid();
        List<SalesCommands.InvoiceLine> lines = document.has("lines")
                ? readLines(document.get("lines"))
                : oldLines.stream().map(InvoiceService::asCommand).toList();
        LocalDate date = invoiceDate.orElse(current.invoiceDate());
        SalesCommands.Invoice next = new SalesCommands.Invoice(
                current.documentType(),
                current.customerId(),
                date,
                accountingDate.present()
                        ? accountingDate.value()
                        : invoiceDate.present() ? date : current.accountingDate(),
                dueDate.present() ? dueDate.value() : invoiceDate.present() ? null : current.dueDate(),
                current.salesOrderId(),
                current.originalInvoiceId(),
                current.salesReturnId(),
                notes.orElse(current.notes()),
                lines);
        Draft draft = draft(next, current);
        UUID actor = context.actor();
        invoices.deleteLines(companyId, id);
        invoices.insertLines(companyId, id, draft.lines(), actor);
        invoices.replaceTaxes(companyId, id, draft.taxes());
        if (!invoices.updateDraft(companyId, id, current.version(), actor, draft.header())) {
            throw new ApiException(PlatformErrorCode.VERSION_CONFLICT, "The invoice was modified concurrently.");
        }
        SalesViews.Invoice after = invoices.find(companyId, id).orElseThrow();
        audit.record(AuditEvent.builder("UPDATE", "sales")
                .entity("invoice", id, null)
                .change("invoiceDate", current.invoiceDate(), after.invoiceDate())
                .change("accountingDate", current.accountingDate(), after.accountingDate())
                .change("dueDate", current.dueDate(), after.dueDate())
                .change("total", current.total().toPlainString(), after.total().toPlainString())
                .change("notes", current.notes(), after.notes())
                .detail("linesReplaced", document.has("lines"))
                .build());
        return get(id);
    }

    @Transactional
    public void delete(UUID id, @Nullable String ifMatch) {
        UUID companyId = CurrentContext.requireCompany();
        SalesViews.Invoice current = lock(companyId, id, ifMatch, DocumentStatus.Action.DELETE);
        if (!invoices.delete(companyId, id, current.version())) {
            throw new ApiException(PlatformErrorCode.VERSION_CONFLICT, "The invoice was modified concurrently.");
        }
        audit.record(AuditEvent.builder("DELETE", "sales")
                .entity("invoice", id, null)
                .build());
    }

    @Transactional
    public SalesViews.InvoiceDetail cancel(UUID id, @Nullable String ifMatch) {
        UUID companyId = CurrentContext.requireCompany();
        SalesViews.Invoice current = lock(companyId, id, ifMatch, DocumentStatus.Action.CANCEL);
        if (!invoices.markCancelled(companyId, id, current.version(), context.actor())) {
            throw new ApiException(PlatformErrorCode.VERSION_CONFLICT, "The invoice was modified concurrently.");
        }
        audit.record(AuditEvent.builder("STATE_CHANGE", "sales")
                .entity("invoice", id, null)
                .transition("DRAFT", "CANCELLED")
                .build());
        return get(id);
    }

    /**
     * Posts the invoice or credit note: checks the quantities under the order (and original invoice)
     * locks, updates the invoiced / credited counters and the order's invoice status, numbers the
     * document and publishes the event Accounting books from.
     */
    @Transactional
    public SalesViews.InvoiceDetail post(UUID id, @Nullable String ifMatch) {
        UUID companyId = CurrentContext.requireCompany();
        SalesViews.Invoice invoice = lock(companyId, id, ifMatch, DocumentStatus.Action.POST);
        List<SalesViews.InvoiceLine> lines = invoices.lines(companyId, id);
        boolean creditNote = CREDIT_NOTE.equals(invoice.documentType());
        SalesViews.SalesOrder order = invoice.salesOrderId() == null
                ? null
                : orderService.lockForFulfilment(companyId, invoice.salesOrderId());
        if (creditNote) {
            postCredit(invoice, lines, order);
        } else if (order != null) {
            postAgainstOrder(order, lines);
        }
        if (order != null) {
            orderService.invoicingProgress(companyId, order.id());
        }

        CompanyProfile profile = context.profile();
        String number = numbering.next(
                companyId,
                creditNote ? SalesConfiguration.CREDIT_NOTE : SalesConfiguration.INVOICE,
                FiscalYears.label(invoice.invoiceDate(), profile.fiscalYearStartMonth()));
        if (!invoices.markPosted(companyId, id, invoice.version(), context.actor(), number)) {
            throw new ApiException(PlatformErrorCode.VERSION_CONFLICT, "The invoice was modified concurrently.");
        }
        audit.record(AuditEvent.builder("POST", "sales")
                .entity("invoice", id, number)
                .transition("DRAFT", "POSTED")
                .detail("documentType", invoice.documentType())
                .detail("customerId", invoice.customerId())
                .detail("salesOrderId", invoice.salesOrderId())
                .detail("originalInvoiceId", invoice.originalInvoiceId())
                .detail("total", invoice.total().toPlainString())
                .detail("totalBase", invoice.totalBase().toPlainString())
                .detail("exchangeRate", invoice.exchangeRate().toPlainString())
                .build());
        publish(invoice, number, lines, invoices.taxes(companyId, id));
        return get(id);
    }

    // ------------------------------------------------------------------------------ posting

    /** SAL-5 under the order lock: per order line, this invoice ≤ invoiceable. */
    private void postAgainstOrder(SalesViews.SalesOrder order, List<SalesViews.InvoiceLine> lines) {
        UUID companyId = order.companyId();
        requireInvoiceable(order);
        Map<UUID, SalesViews.SalesOrderLine> orderLines = orders.lines(companyId, order.id()).stream()
                .collect(Collectors.toMap(SalesViews.SalesOrderLine::id, Function.identity()));
        Map<UUID, BigDecimal> requested = new LinkedHashMap<>();
        Map<UUID, Integer> firstIndex = new HashMap<>();
        for (int i = 0; i < lines.size(); i++) {
            SalesViews.InvoiceLine line = lines.get(i);
            requested.merge(Objects.requireNonNull(line.salesOrderLineId()), line.quantityBase(), BigDecimal::add);
            firstIndex.putIfAbsent(line.salesOrderLineId(), i);
        }
        List<FieldViolation> violations = new ArrayList<>();
        requested.forEach((lineId, quantity) -> {
            SalesViews.SalesOrderLine orderLine = orderLines.get(lineId);
            BigDecimal open = SalesOrderService.invoiceable(order, orderLine);
            if (quantity.compareTo(open) > 0) {
                violations.add(exceeds(
                        firstIndex.get(lineId),
                        "Invoicing " + plain(quantity) + " of order line "
                                + orderLine.line().lineNo() + ", invoiceable " + plain(open),
                        "salesOrderLineId",
                        lineId,
                        quantity,
                        open));
            }
        });
        throwIfExceeded(violations, "The invoice exceeds the quantities that may still be invoiced on the order.");
        requested.forEach((lineId, quantity) -> {
            SalesViews.SalesOrderLine l = orderLines.get(lineId);
            orders.updateLine(
                    companyId,
                    lineId,
                    l.reservationId(),
                    l.reservedQuantityBase(),
                    l.deliveredQuantityBase(),
                    l.returnedQuantityBase(),
                    l.invoicedQuantityBase().add(quantity));
        });
    }

    /**
     * SAL-6 under the original invoice's lock: per credited line, ≤ invoiced − already credited; per
     * return line, ≤ returned − already credited. Credits for returned goods give the order line back
     * its invoiceable quantity.
     */
    private void postCredit(
            SalesViews.Invoice creditNote, List<SalesViews.InvoiceLine> lines, SalesViews.@Nullable SalesOrder order) {
        UUID companyId = creditNote.companyId();
        SalesViews.Invoice original = invoices.lock(companyId, Objects.requireNonNull(creditNote.originalInvoiceId()))
                .orElseThrow();
        if (!"POSTED".equals(original.status())) {
            throw new ApiException(PlatformErrorCode.INVALID_STATE, "Only a posted invoice can be credited.");
        }
        Map<UUID, SalesViews.InvoiceLine> originalLines = invoices.lines(companyId, original.id()).stream()
                .collect(Collectors.toMap(SalesViews.InvoiceLine::id, Function.identity()));
        Set<UUID> returnLineIds = new HashSet<>();
        lines.stream()
                .map(SalesViews.InvoiceLine::salesReturnLineId)
                .filter(Objects::nonNull)
                .forEach(returnLineIds::add);
        Map<UUID, SalesReturnRepository.LineOfReturn> returnLines = returns.linesById(companyId, returnLineIds);
        Map<UUID, BigDecimal> perOriginal = new LinkedHashMap<>();
        Map<UUID, BigDecimal> perReturn = new LinkedHashMap<>();
        Map<UUID, Integer> firstIndex = new HashMap<>();
        for (int i = 0; i < lines.size(); i++) {
            SalesViews.InvoiceLine line = lines.get(i);
            UUID originalLine = Objects.requireNonNull(line.originalInvoiceLineId());
            perOriginal.merge(originalLine, line.quantityBase(), BigDecimal::add);
            firstIndex.putIfAbsent(originalLine, i);
            if (line.salesReturnLineId() != null) {
                perReturn.merge(line.salesReturnLineId(), line.quantityBase(), BigDecimal::add);
                firstIndex.putIfAbsent(line.salesReturnLineId(), i);
            }
        }
        List<FieldViolation> violations = new ArrayList<>();
        perOriginal.forEach((lineId, quantity) -> {
            SalesViews.InvoiceLine o = originalLines.get(lineId);
            BigDecimal open = o.quantityBase().subtract(o.creditedQuantityBase());
            if (quantity.compareTo(open) > 0) {
                violations.add(exceeds(
                        firstIndex.get(lineId),
                        "Crediting " + plain(quantity) + " of invoice line " + o.lineNo() + ", creditable "
                                + plain(open),
                        "originalInvoiceLineId",
                        lineId,
                        quantity,
                        open));
            }
        });
        perReturn.forEach((lineId, quantity) -> {
            SalesViews.SalesReturnLine r = returnLines.get(lineId).line();
            BigDecimal open = r.quantityBase().subtract(r.creditedQuantityBase());
            if (quantity.compareTo(open) > 0) {
                violations.add(exceeds(
                        firstIndex.get(lineId),
                        "Crediting " + plain(quantity) + " of return line " + r.lineNo() + ", creditable "
                                + plain(open),
                        "salesReturnLineId",
                        lineId,
                        quantity,
                        open));
            }
        });
        throwIfExceeded(violations, "The credit note exceeds what may still be credited.");
        perOriginal.forEach((lineId, quantity) -> invoices.updateCredited(
                companyId,
                lineId,
                originalLines.get(lineId).creditedQuantityBase().add(quantity)));
        perReturn.forEach((lineId, quantity) -> returns.updateCredited(
                companyId,
                lineId,
                returnLines.get(lineId).line().creditedQuantityBase().add(quantity)));
        if (order != null) {
            Map<UUID, BigDecimal> backToOrder = new LinkedHashMap<>();
            for (SalesViews.InvoiceLine line : lines) {
                SalesViews.InvoiceLine o = originalLines.get(line.originalInvoiceLineId());
                if (line.salesReturnLineId() != null && o.salesOrderLineId() != null) {
                    backToOrder.merge(o.salesOrderLineId(), line.quantityBase(), BigDecimal::add);
                }
            }
            if (!backToOrder.isEmpty()) {
                Map<UUID, SalesViews.SalesOrderLine> orderLines = orders.lines(companyId, order.id()).stream()
                        .collect(Collectors.toMap(SalesViews.SalesOrderLine::id, Function.identity()));
                backToOrder.forEach((lineId, quantity) -> {
                    SalesViews.SalesOrderLine l = orderLines.get(lineId);
                    orders.updateLine(
                            companyId,
                            lineId,
                            l.reservationId(),
                            l.reservedQuantityBase(),
                            l.deliveredQuantityBase(),
                            l.returnedQuantityBase(),
                            l.invoicedQuantityBase().subtract(quantity).max(BigDecimal.ZERO));
                });
            }
        }
    }

    private void publish(
            SalesViews.Invoice invoice,
            String number,
            List<SalesViews.InvoiceLine> lines,
            List<SalesViews.InvoiceTax> taxLines) {
        boolean creditNote = CREDIT_NOTE.equals(invoice.documentType());
        CustomerInfo customer = partners.customer(invoice.customerId()).orElseThrow();
        events.publishEvent(new InvoicePosted(
                DomainEvents.metadata(
                        creditNote ? InvoicePosted.CREDIT_NOTE_TYPE : InvoicePosted.INVOICE_TYPE,
                        InvoicePosted.SCHEMA_VERSION,
                        invoice.companyId(),
                        clock),
                invoice.id(),
                invoice.documentType(),
                number,
                invoice.customerId(),
                customer.customerGroupId(),
                invoice.salesOrderId(),
                invoice.originalInvoiceId(),
                invoice.salesReturnId(),
                invoice.invoiceDate(),
                invoice.accountingDate(),
                invoice.dueDate(),
                invoice.currencyCode(),
                invoice.exchangeRate(),
                new InvoicePosted.Totals(
                        invoice.subtotal(),
                        invoice.taxTotal(),
                        invoice.total(),
                        invoice.subtotalBase(),
                        invoice.taxTotalBase(),
                        invoice.totalBase()),
                lines.stream()
                        .map(l -> new InvoicePosted.Line(
                                l.id(),
                                l.variantId(),
                                inventory
                                        .variantInfo(l.variantId())
                                        .orElseThrow()
                                        .categoryId(),
                                l.salesOrderLineId(),
                                l.quantityBase(),
                                l.netAmount(),
                                l.netAmountBase(),
                                l.taxCodeId(),
                                l.branchId(),
                                l.departmentId()))
                        .toList(),
                taxLines.stream()
                        .map(t -> new InvoicePosted.TaxLine(
                                t.taxCodeId(),
                                t.ratePercent(),
                                t.taxableAmount(),
                                t.taxAmount(),
                                t.taxableAmountBase(),
                                t.taxAmountBase()))
                        .toList()));
    }

    // -------------------------------------------------------------------------------- drafts

    private record Draft(
            InvoiceRepository.Header header,
            List<InvoiceRepository.NewLine> lines,
            List<SalesViews.InvoiceTax> taxes) {}

    /** A line resolved against its order line, original invoice line or product. */
    private record Resolved(
            @Nullable UUID salesOrderLineId,
            @Nullable UUID deliveryLineId,
            @Nullable UUID originalInvoiceLineId,
            @Nullable UUID salesReturnLineId,
            UUID variantId,
            String description,
            BigDecimal quantity,
            UUID uomId,
            BigDecimal quantityBase,
            BigDecimal unitPrice,
            BigDecimal discountPercent,
            @Nullable UUID taxCodeId,
            @Nullable BigDecimal ratePercent,
            @Nullable UUID branchId,
            @Nullable UUID departmentId) {}

    /** Header values every kind of document needs. */
    private record HeaderTerms(
            String currencyCode,
            boolean pricesIncludeTax,
            @Nullable UUID paymentTermsId,
            Map<String, Object> billingAddress,
            @Nullable UUID salesOrderId) {}

    private Draft draft(SalesCommands.Invoice command, SalesViews.@Nullable Invoice current) {
        return draft(command, current, Map.of());
    }

    private Draft draft(
            SalesCommands.Invoice command, SalesViews.@Nullable Invoice current, Map<UUID, UUID> deliveryLineOf) {
        CompanyProfile profile = context.profile();
        LocalDate date = command.invoiceDate() != null ? command.invoiceDate() : context.today(profile);
        LocalDate accountingDate = command.accountingDate() != null ? command.accountingDate() : date;
        List<FieldViolation> violations = new ArrayList<>();
        if (!Set.of(INVOICE, CREDIT_NOTE).contains(command.documentType())) {
            throw ApiException.validationFailed(
                    "The document type is invalid.",
                    List.of(FieldViolation.atPointer(
                            "/documentType", "INVALID_VALUE", "must be INVOICE or CREDIT_NOTE")));
        }
        // Completing an open order or crediting an invoice works for any customer (PRODUCT_SPEC.md §5);
        // only a new direct invoice needs an active one.
        CustomerInfo customer = current != null
                        || CREDIT_NOTE.equals(command.documentType())
                        || command.salesOrderId() != null
                ? partners.customerForUse(command.customerId())
                        .orElseThrow(() -> ApiException.validationFailed(
                                "The customer is unknown.",
                                List.of(FieldViolation.atPointer(
                                        "/customerId", "UNKNOWN_CUSTOMER", "is not a customer of the company"))))
                : drafts.usableCustomer(command.customerId());
        if (command.lines().isEmpty() || command.lines().size() > SalesPricing.MAX_LINES) {
            violations.add(FieldViolation.atPointer("/lines", "SIZE", "must contain between 1 and 500 lines"));
        }
        HeaderTerms terms;
        List<Resolved> resolved;
        if (CREDIT_NOTE.equals(command.documentType())) {
            SalesViews.Invoice original = original(command, violations);
            terms = new HeaderTerms(
                    original.currencyCode(),
                    original.pricesIncludeTax(),
                    original.paymentTermsId(),
                    original.billingAddress(),
                    original.salesOrderId());
            resolved = creditLines(command, original, violations);
        } else if (command.salesOrderId() != null) {
            if (command.originalInvoiceId() != null || command.salesReturnId() != null) {
                violations.add(FieldViolation.atPointer(
                        "/originalInvoiceId", "NOT_ALLOWED", "only credit notes refer to an invoice or return"));
            }
            SalesViews.SalesOrder order = orders.find(CurrentContext.requireCompany(), command.salesOrderId())
                    .filter(o -> context.canSeeBranch(o.branchId()))
                    .orElseThrow(() -> ApiException.validationFailed(
                            "The sales order is unknown.",
                            List.of(FieldViolation.atPointer(
                                    "/salesOrderId", "UNKNOWN_SALES_ORDER", "is not a sales order you can use"))));
            if (!order.customerId().equals(command.customerId())) {
                violations.add(FieldViolation.atPointer(
                        "/customerId", "CUSTOMER_MISMATCH", "must be the customer of the sales order"));
            }
            requireInvoiceable(order);
            terms = new HeaderTerms(
                    order.currencyCode(),
                    order.pricesIncludeTax(),
                    order.paymentTermsId(),
                    order.billingAddress(),
                    order.id());
            resolved = orderLines(command, order, date, deliveryLineOf, violations);
        } else {
            if (command.originalInvoiceId() != null || command.salesReturnId() != null) {
                violations.add(FieldViolation.atPointer(
                        "/originalInvoiceId", "NOT_ALLOWED", "only credit notes refer to an invoice or return"));
            }
            context.require(SalesPermissions.INVOICE_CREATE_DIRECT, "An invoice without a sales order");
            terms = new HeaderTerms(
                    customer.currencyCode(),
                    false,
                    customer.paymentTermsId(),
                    drafts.address(customer.partnerId(), "BILLING"),
                    null);
            DirectLines direct = directLines(command, customer, date, profile, violations, current);
            terms = new HeaderTerms(
                    terms.currencyCode(),
                    direct.pricesIncludeTax(),
                    terms.paymentTermsId(),
                    terms.billingAddress(),
                    null);
            resolved = direct.lines();
        }
        if (!context.currencyUsable(terms.currencyCode())) {
            violations.add(FieldViolation.atPointer("/currencyCode", "UNKNOWN_CURRENCY", "is not an active currency"));
        }
        LocalDate dueDate = command.dueDate() != null
                ? command.dueDate()
                : CREDIT_NOTE.equals(command.documentType()) || terms.paymentTermsId() == null
                        ? date
                        : org.paymentTerms(CurrentContext.requireCompany(), terms.paymentTermsId())
                                .map(t -> t.dueDate(date))
                                .orElse(date);
        if (dueDate.isBefore(date)) {
            violations.add(
                    FieldViolation.atPointer("/dueDate", "BEFORE_INVOICE_DATE", "must not be before the invoice date"));
        }
        if (!violations.isEmpty()) {
            throw ApiException.validationFailed("The invoice is invalid.", violations);
        }
        BigDecimal rate = context.exchangeRate(terms.currencyCode(), date);
        return priced(command, customer, terms, resolved, date, accountingDate, dueDate, rate, profile);
    }

    private Draft priced(
            SalesCommands.Invoice command,
            CustomerInfo customer,
            HeaderTerms terms,
            List<Resolved> resolved,
            LocalDate date,
            LocalDate accountingDate,
            LocalDate dueDate,
            BigDecimal rate,
            CompanyProfile profile) {
        RoundingPolicy docRounding = context.rounding(terms.currencyCode(), profile);
        RoundingPolicy baseRounding = context.baseRounding(profile);
        TaxCalculator.Result result = taxes.calculate(new TaxCalculator.Request(
                resolved.stream()
                        .map(r -> new TaxCalculator.Line(
                                r.quantity(),
                                r.unitPrice(),
                                r.discountPercent(),
                                r.taxCodeId() == null
                                        ? null
                                        : new TaxCalculator.Rate(
                                                r.taxCodeId(), Objects.requireNonNull(r.ratePercent()))))
                        .toList(),
                terms.pricesIncludeTax(),
                docRounding,
                TaxCalculator.TaxRounding.valueOf(profile.taxRounding())));
        List<InvoiceRepository.NewLine> lines = new ArrayList<>();
        BigDecimal subtotalBase = BigDecimal.ZERO;
        BigDecimal taxBase = BigDecimal.ZERO;
        Map<UUID, BigDecimal[]> baseByCode = new LinkedHashMap<>();
        for (int i = 0; i < resolved.size(); i++) {
            Resolved r = resolved.get(i);
            TaxCalculator.LineResult amounts = result.lines().get(i);
            BigDecimal netBase = baseRounding.round(amounts.net().multiply(rate));
            BigDecimal lineTaxBase = baseRounding.round(amounts.tax().multiply(rate));
            subtotalBase = subtotalBase.add(netBase);
            taxBase = taxBase.add(lineTaxBase);
            if (r.taxCodeId() != null) {
                BigDecimal[] sums = baseByCode.computeIfAbsent(
                        r.taxCodeId(), k -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
                sums[0] = sums[0].add(netBase);
                sums[1] = sums[1].add(lineTaxBase);
            }
            lines.add(new InvoiceRepository.NewLine(
                    i + 1,
                    r.salesOrderLineId(),
                    r.deliveryLineId(),
                    r.originalInvoiceLineId(),
                    r.salesReturnLineId(),
                    r.variantId(),
                    r.description(),
                    r.quantity(),
                    r.uomId(),
                    r.quantityBase(),
                    r.unitPrice(),
                    r.discountPercent(),
                    r.taxCodeId(),
                    amounts.net(),
                    amounts.tax(),
                    amounts.total(),
                    netBase,
                    lineTaxBase,
                    r.branchId(),
                    r.departmentId()));
        }
        List<SalesViews.InvoiceTax> taxLines = new ArrayList<>();
        for (TaxCalculator.TaxTotal t : result.taxes()) {
            BigDecimal[] sums = baseByCode.get(t.taxCodeId());
            taxLines.add(
                    new SalesViews.InvoiceTax(t.taxCodeId(), t.ratePercent(), t.taxable(), t.tax(), sums[0], sums[1]));
        }
        return new Draft(
                new InvoiceRepository.Header(
                        command.documentType(),
                        command.customerId(),
                        terms.salesOrderId(),
                        command.originalInvoiceId(),
                        command.salesReturnId(),
                        date,
                        accountingDate,
                        dueDate,
                        terms.currencyCode(),
                        rate,
                        terms.pricesIncludeTax(),
                        terms.paymentTermsId(),
                        terms.billingAddress(),
                        customer.taxRegistrationNo(),
                        result.subtotal(),
                        result.taxTotal(),
                        result.total(),
                        subtotalBase,
                        taxBase,
                        subtotalBase.add(taxBase),
                        command.notes()),
                lines,
                taxLines);
    }

    /** Lines against order lines: defaults from the order line; quantities ≤ invoiceable. */
    private List<Resolved> orderLines(
            SalesCommands.Invoice command,
            SalesViews.SalesOrder order,
            LocalDate date,
            Map<UUID, UUID> deliveryLineOf,
            List<FieldViolation> violations) {
        Map<UUID, SalesViews.SalesOrderLine> byId = orders.lines(order.companyId(), order.id()).stream()
                .collect(Collectors.toMap(SalesViews.SalesOrderLine::id, Function.identity()));
        List<Resolved> resolved = new ArrayList<>();
        Map<UUID, BigDecimal> requested = new HashMap<>();
        boolean overridden = false;
        for (int i = 0; i < command.lines().size(); i++) {
            SalesCommands.InvoiceLine line = command.lines().get(i);
            String at = "/lines/" + i;
            SalesViews.SalesOrderLine orderLine =
                    line.salesOrderLineId() == null ? null : byId.get(line.salesOrderLineId());
            if (orderLine == null) {
                violations.add(FieldViolation.atPointer(
                        at + "/salesOrderLineId", "UNKNOWN_LINE", "must be a line of the sales order"));
                continue;
            }
            if (line.originalInvoiceLineId() != null || line.salesReturnLineId() != null) {
                violations.add(FieldViolation.atPointer(
                        at + "/originalInvoiceLineId", "NOT_ALLOWED", "only credit note lines refer to an invoice"));
            }
            UUID uom = line.uomId() != null ? line.uomId() : orderLine.line().uomId();
            BigDecimal base = pricing.quantityBase(at, orderLine.line().variantId(), line.quantity(), uom, violations);
            if (base == null) {
                continue;
            }
            BigDecimal orderPrice = scaledPrice(orderLine.line(), line.quantity(), base);
            BigDecimal price = line.unitPrice() != null ? line.unitPrice() : orderPrice;
            BigDecimal discount = line.discountPercent() != null
                    ? line.discountPercent()
                    : orderLine.line().discountPercent();
            if (price.compareTo(orderPrice) != 0
                    || discount.compareTo(orderLine.line().discountPercent()) != 0) {
                overridden = true;
            }
            checkPriceAndDiscount(at, price, discount, violations);
            UUID taxCode = line.taxCodeId() != null
                    ? line.taxCodeId()
                    : orderLine.line().taxCodeId();
            BigDecimal rate = taxCode == null ? null : pricing.taxRate(at + "/taxCodeId", taxCode, date, violations);
            requested.merge(orderLine.id(), base, BigDecimal::add);
            resolved.add(new Resolved(
                    orderLine.id(),
                    deliveryLineOf.get(orderLine.id()),
                    null,
                    null,
                    orderLine.line().variantId(),
                    line.description() != null && !line.description().isBlank()
                            ? line.description()
                            : orderLine.line().description(),
                    line.quantity(),
                    uom,
                    base,
                    price,
                    discount,
                    taxCode,
                    rate,
                    order.branchId(),
                    null));
        }
        requested.forEach((lineId, quantity) -> {
            BigDecimal open = SalesOrderService.invoiceable(order, byId.get(lineId));
            if (quantity.compareTo(open) > 0) {
                violations.add(new FieldViolation(
                        "/lines",
                        null,
                        SalesErrorCode.QUANTITY_EXCEEDS_REMAINING.code(),
                        "Order line " + byId.get(lineId).line().lineNo() + " can be invoiced for " + plain(open)
                                + " more",
                        Map.of(
                                "salesOrderLineId",
                                lineId.toString(),
                                "requested",
                                plain(quantity),
                                "open",
                                plain(open))));
            }
        });
        if (overridden && violations.isEmpty()) {
            context.require(
                    SalesPermissions.ORDER_OVERRIDE_PRICE, "Invoicing at a price or discount other than the order's");
        }
        return resolved;
    }

    /** Credit note lines: defaults from the credited invoice line; never above its price. */
    private List<Resolved> creditLines(
            SalesCommands.Invoice command, SalesViews.Invoice original, List<FieldViolation> violations) {
        UUID companyId = original.companyId();
        Map<UUID, SalesViews.InvoiceLine> originalLines = invoices.lines(companyId, original.id()).stream()
                .collect(Collectors.toMap(SalesViews.InvoiceLine::id, Function.identity()));
        Set<UUID> returnLineIds = new HashSet<>();
        command.lines().stream()
                .map(SalesCommands.InvoiceLine::salesReturnLineId)
                .filter(Objects::nonNull)
                .forEach(returnLineIds::add);
        Map<UUID, SalesReturnRepository.LineOfReturn> returnLines = returns.linesById(companyId, returnLineIds);
        Map<UUID, DeliveryRepository.LineOfDelivery> deliveryLines = deliveries.linesById(
                companyId,
                returnLines.values().stream()
                        .map(r -> r.line().deliveryLineId())
                        .toList());
        List<Resolved> resolved = new ArrayList<>();
        for (int i = 0; i < command.lines().size(); i++) {
            SalesCommands.InvoiceLine line = command.lines().get(i);
            String at = "/lines/" + i;
            SalesViews.InvoiceLine o =
                    line.originalInvoiceLineId() == null ? null : originalLines.get(line.originalInvoiceLineId());
            if (o == null) {
                violations.add(FieldViolation.atPointer(
                        at + "/originalInvoiceLineId", "UNKNOWN_LINE", "must be a line of the credited invoice"));
                continue;
            }
            if (line.salesOrderLineId() != null || line.variantId() != null) {
                violations.add(FieldViolation.atPointer(
                        at + "/salesOrderLineId", "NOT_ALLOWED", "credit note lines refer to the invoice line only"));
            }
            if (line.salesReturnLineId() != null) {
                SalesReturnRepository.LineOfReturn r = returnLines.get(line.salesReturnLineId());
                DeliveryRepository.LineOfDelivery d =
                        r == null ? null : deliveryLines.get(r.line().deliveryLineId());
                if (r == null
                        || !"RECEIVED".equals(r.salesReturn().status())
                        || !r.salesReturn().customerId().equals(original.customerId())
                        || (command.salesReturnId() != null
                                && !r.salesReturn().id().equals(command.salesReturnId()))
                        || d == null
                        || !Objects.equals(d.line().salesOrderLineId(), o.salesOrderLineId())) {
                    violations.add(FieldViolation.atPointer(
                            at + "/salesReturnLineId",
                            "INVALID_VALUE",
                            "must be a line of a received return of the credited order line"));
                    continue;
                }
            }
            UUID uom = line.uomId() != null ? line.uomId() : o.uomId();
            BigDecimal base = pricing.quantityBase(at, o.variantId(), line.quantity(), uom, violations);
            if (base == null) {
                continue;
            }
            BigDecimal originalPrice = o.unitPrice()
                    .multiply(o.quantity())
                    .multiply(base)
                    .divide(
                            o.quantityBase().multiply(line.quantity()),
                            SalesPricing.UNIT_PRICE_SCALE,
                            RoundingMode.HALF_UP);
            if (uom.equals(o.uomId())) {
                originalPrice = o.unitPrice();
            }
            BigDecimal price = line.unitPrice() != null ? line.unitPrice() : originalPrice;
            BigDecimal discount = line.discountPercent() != null ? line.discountPercent() : o.discountPercent();
            checkPriceAndDiscount(at, price, discount, violations);
            if (price.compareTo(originalPrice) > 0) {
                violations.add(FieldViolation.atPointer(
                        at + "/unitPrice", "EXCEEDS_ORIGINAL", "must not exceed the credited invoice's unit price"));
            }
            UUID taxCode = line.taxCodeId() != null ? line.taxCodeId() : o.taxCodeId();
            BigDecimal rate = taxCode == null
                    ? null
                    : pricing.taxRate(at + "/taxCodeId", taxCode, original.invoiceDate(), violations);
            resolved.add(new Resolved(
                    o.salesOrderLineId(),
                    null,
                    o.id(),
                    line.salesReturnLineId(),
                    o.variantId(),
                    line.description() != null && !line.description().isBlank() ? line.description() : o.description(),
                    line.quantity(),
                    uom,
                    base,
                    price,
                    discount,
                    taxCode,
                    rate,
                    o.branchId(),
                    o.departmentId()));
        }
        Map<UUID, BigDecimal> perOriginal = new HashMap<>();
        resolved.forEach(r -> perOriginal.merge(r.originalInvoiceLineId(), r.quantityBase(), BigDecimal::add));
        perOriginal.forEach((lineId, quantity) -> {
            SalesViews.InvoiceLine o = originalLines.get(lineId);
            BigDecimal open = o.quantityBase().subtract(o.creditedQuantityBase());
            if (quantity.compareTo(open) > 0) {
                violations.add(new FieldViolation(
                        "/lines",
                        null,
                        SalesErrorCode.QUANTITY_EXCEEDS_REMAINING.code(),
                        "Invoice line " + o.lineNo() + " can be credited for " + plain(open) + " more",
                        Map.of(
                                "originalInvoiceLineId",
                                lineId.toString(),
                                "requested",
                                plain(quantity),
                                "open",
                                plain(open))));
            }
        });
        return resolved;
    }

    private record DirectLines(List<Resolved> lines, boolean pricesIncludeTax) {}

    /** Direct invoice lines (SAL-5): service products priced like an order (SAL-1). */
    private DirectLines directLines(
            SalesCommands.Invoice command,
            CustomerInfo customer,
            LocalDate date,
            CompanyProfile profile,
            List<FieldViolation> violations,
            SalesViews.@Nullable Invoice current) {
        List<SalesCommands.PricedLine> priced = new ArrayList<>();
        for (int i = 0; i < command.lines().size(); i++) {
            SalesCommands.InvoiceLine line = command.lines().get(i);
            String at = "/lines/" + i;
            if (line.variantId() == null || line.uomId() == null) {
                violations.add(FieldViolation.atPointer(
                        at + "/variantId", "REQUIRED", "variantId and uomId are required without a sales order"));
                continue;
            }
            if (line.salesOrderLineId() != null
                    || line.originalInvoiceLineId() != null
                    || line.salesReturnLineId() != null) {
                violations.add(FieldViolation.atPointer(
                        at + "/salesOrderLineId", "NOT_ALLOWED", "a direct invoice has no order or invoice links"));
            }
            var variant = inventory.variantInfo(line.variantId()).orElse(null);
            if (variant != null && !"SERVICE".equals(variant.productType())) {
                violations.add(FieldViolation.atPointer(
                        at + "/variantId", "NOT_SERVICE", "only service products are invoiced without a sales order"));
            }
            if (line.branchId() != null) {
                context.checkBranch(line.branchId(), at + "/branchId", violations);
            }
            if (line.departmentId() != null) {
                context.checkDepartment(line.departmentId(), line.branchId(), at + "/departmentId", violations);
            }
            priced.add(new SalesCommands.PricedLine(
                    line.variantId(),
                    line.description(),
                    line.quantity(),
                    line.uomId(),
                    line.unitPrice(),
                    line.discountPercent() == null ? BigDecimal.ZERO : line.discountPercent(),
                    line.taxCodeId()));
        }
        if (priced.size() != command.lines().size()) {
            throw ApiException.validationFailed("The invoice is invalid.", violations);
        }
        SalesViews.PriceList list =
                pricing.priceList(null, customer.currencyCode(), customer.customerGroupId(), date, violations);
        boolean inclusive = current != null ? current.pricesIncludeTax() : list != null && list.pricesIncludeTax();
        List<SalesViews.Line> approved = current == null
                ? List.of()
                : invoices.lines(current.companyId(), current.id()).stream()
                        .map(l -> new SalesViews.Line(
                                l.id(),
                                l.lineNo(),
                                l.variantId(),
                                l.description(),
                                l.quantity(),
                                l.uomId(),
                                l.quantityBase(),
                                l.unitPrice(),
                                l.discountPercent(),
                                l.taxCodeId(),
                                l.netAmount(),
                                l.taxAmount(),
                                l.totalAmount()))
                        .toList();
        SalesPricing.Priced result = pricing.price(
                priced,
                new SalesPricing.Terms(customer.currencyCode(), list, inclusive, date, customer.defaultTaxCodeId()),
                profile,
                context.rounding(customer.currencyCode(), profile),
                violations,
                approved);
        List<Resolved> resolved = new ArrayList<>();
        for (SalesPricing.Line p : result.lines()) {
            SalesCommands.InvoiceLine line = command.lines().get(p.index());
            resolved.add(new Resolved(
                    null,
                    null,
                    null,
                    null,
                    p.variant().variantId(),
                    p.description(),
                    p.input().quantity(),
                    p.input().uomId(),
                    p.quantityBase(),
                    p.unitPrice(),
                    p.input().discountPercent(),
                    p.taxCodeId(),
                    p.ratePercent(),
                    line.branchId(),
                    line.departmentId()));
        }
        return new DirectLines(resolved, inclusive);
    }

    private SalesViews.Invoice original(SalesCommands.Invoice command, List<FieldViolation> violations) {
        UUID companyId = CurrentContext.requireCompany();
        SalesViews.Invoice original = command.originalInvoiceId() == null
                ? null
                : invoices.find(companyId, command.originalInvoiceId()).orElse(null);
        if (original == null
                || !INVOICE.equals(original.documentType())
                || !"POSTED".equals(original.status())
                || !original.customerId().equals(command.customerId())) {
            throw ApiException.validationFailed(
                    "The credited invoice is invalid.",
                    List.of(FieldViolation.atPointer(
                            "/originalInvoiceId", "INVALID_VALUE", "must be a posted invoice of the customer")));
        }
        if (command.salesOrderId() != null && !command.salesOrderId().equals(original.salesOrderId())) {
            violations.add(FieldViolation.atPointer(
                    "/salesOrderId", "INVALID_VALUE", "must be the credited invoice's sales order"));
        }
        if (command.salesReturnId() != null) {
            SalesViews.SalesReturn found =
                    returns.find(companyId, command.salesReturnId()).orElse(null);
            if (found == null
                    || !"RECEIVED".equals(found.status())
                    || !found.customerId().equals(original.customerId())
                    || !Objects.equals(found.salesOrderId(), original.salesOrderId())) {
                violations.add(FieldViolation.atPointer(
                        "/salesReturnId",
                        "INVALID_VALUE",
                        "must be a received return of the credited invoice's order"));
            }
        }
        return original;
    }

    // ------------------------------------------------------------------------------ helpers

    /** The order line's unit price for a line in another unit (same price per base unit). */
    private static BigDecimal scaledPrice(SalesViews.Line orderLine, BigDecimal quantity, BigDecimal quantityBase) {
        if (orderLine.quantity().compareTo(orderLine.quantityBase()) == 0 && quantity.compareTo(quantityBase) == 0) {
            return orderLine.unitPrice();
        }
        BigDecimal perBase = orderLine.unitPrice().multiply(orderLine.quantity());
        BigDecimal scaled = perBase.multiply(quantityBase)
                .divide(
                        orderLine.quantityBase().multiply(quantity),
                        SalesPricing.UNIT_PRICE_SCALE,
                        RoundingMode.HALF_UP);
        return scaled.compareTo(orderLine.unitPrice()) == 0 ? orderLine.unitPrice() : scaled;
    }

    private static void checkPriceAndDiscount(
            String at, BigDecimal price, BigDecimal discount, List<FieldViolation> violations) {
        if (price.signum() < 0 || price.stripTrailingZeros().scale() > SalesPricing.UNIT_PRICE_SCALE) {
            violations.add(FieldViolation.atPointer(
                    at + "/unitPrice", "INVALID_VALUE", "must be ≥ 0 with at most 6 decimal places"));
        }
        if (discount.signum() < 0
                || discount.compareTo(BigDecimal.valueOf(100)) > 0
                || discount.stripTrailingZeros().scale() > 4) {
            violations.add(FieldViolation.atPointer(
                    at + "/discountPercent", "INVALID_VALUE", "must be between 0 and 100 with at most 4 decimals"));
        }
    }

    private static FieldViolation exceeds(
            int index, String message, String key, UUID id, BigDecimal requested, BigDecimal open) {
        return new FieldViolation(
                "/lines/" + index + "/quantity",
                null,
                SalesErrorCode.QUANTITY_EXCEEDS_REMAINING.code(),
                message,
                Map.of(key, id.toString(), "requested", plain(requested), "open", plain(open)));
    }

    private static void throwIfExceeded(List<FieldViolation> violations, String message) {
        if (!violations.isEmpty()) {
            throw new ApiException(SalesErrorCode.QUANTITY_EXCEEDS_REMAINING, message, violations);
        }
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static void requireInvoiceable(SalesViews.SalesOrder order) {
        if (!SalesOrderStatus.valueOf(order.status()).allows(SalesOrderStatus.Action.INVOICE)) {
            throw new ApiException(
                    PlatformErrorCode.INVALID_STATE,
                    "A " + order.status() + " sales order cannot be invoiced; it must be confirmed.");
        }
    }

    private SalesViews.Invoice lock(UUID companyId, UUID id, @Nullable String ifMatch, DocumentStatus.Action action) {
        SalesViews.Invoice current = invoices.lock(companyId, id).orElseThrow(ApiException::notFound);
        EntityTags.requireMatch(ifMatch, current.version());
        if (!DocumentStatus.valueOf(current.status()).allows(action)) {
            throw new ApiException(
                    PlatformErrorCode.INVALID_STATE,
                    "A " + current.status() + " "
                            + current.documentType().toLowerCase(Locale.ROOT).replace('_', ' ') + " does not allow "
                            + action.name().toLowerCase(Locale.ROOT) + ".");
        }
        return current;
    }

    static List<SalesCommands.InvoiceLine> readLines(JsonNode array) {
        return MergePatchLines.read(array, LINE_MEMBERS).stream()
                .map(v -> new SalesCommands.InvoiceLine(
                        v.uuid("salesOrderLineId"),
                        v.uuid("originalInvoiceLineId"),
                        v.uuid("salesReturnLineId"),
                        v.uuid("variantId"),
                        v.text("description"),
                        v.decimal("quantity"),
                        v.uuid("uomId"),
                        v.decimal("unitPrice"),
                        v.decimal("discountPercent"),
                        v.uuid("taxCodeId"),
                        v.uuid("branchId"),
                        v.uuid("departmentId")))
                .toList();
    }

    private static SalesCommands.InvoiceLine asCommand(SalesViews.InvoiceLine l) {
        boolean linked = l.salesOrderLineId() != null || l.originalInvoiceLineId() != null;
        return new SalesCommands.InvoiceLine(
                l.originalInvoiceLineId() == null ? l.salesOrderLineId() : null,
                l.originalInvoiceLineId(),
                l.salesReturnLineId(),
                linked ? null : l.variantId(),
                l.description(),
                l.quantity(),
                l.uomId(),
                l.unitPrice(),
                l.discountPercent(),
                l.taxCodeId(),
                linked ? null : l.branchId(),
                linked ? null : l.departmentId());
    }
}
