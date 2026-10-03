package com.erp.procurement.application;

import com.erp.inventory.api.InventoryFacade;
import com.erp.org.api.CompanyProfile;
import com.erp.org.api.OrgFacade;
import com.erp.org.api.TaxCalculator;
import com.erp.partners.api.PartnersFacade;
import com.erp.partners.api.PartnersFacade.SupplierInfo;
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
import com.erp.platform.web.PlatformErrorCode;
import com.erp.platform.web.paging.ListQuery;
import com.erp.platform.web.paging.PageResponse;
import com.erp.procurement.ProcurementPermissions;
import com.erp.procurement.api.BillSettlementPort;
import com.erp.procurement.domain.BillLineKind;
import com.erp.procurement.domain.DocumentStatus;
import com.erp.procurement.domain.PurchaseOrderStatus;
import com.erp.procurement.domain.ThreeWayMatch;
import com.erp.procurement.events.SupplierBillPosted;
import com.erp.procurement.persistence.BillRepository;
import com.erp.procurement.persistence.PurchaseOrderRepository;
import com.erp.procurement.persistence.ReceiptRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * Supplier bills and debit notes (PRODUCT_SPEC.md §7, ADR-021). A bill line invoices a receipt line
 * (received stock), an order line of a service or consumable (PRC-4), or — for a direct bill, with
 * {@code create_direct} — a service or consumable without an order (PRC-6). Amounts are computed by
 * the server in the document currency and per line in base currency (G-14). Posting needs a passed
 * or overridden three-way match (PRC-3), clears GRNI at the receipt value, updates the order's
 * billed quantities and publishes the event Accounting books from. A debit note credits a posted bill
 * of the same supplier, never more than is left of it.
 */
@Service
public class SupplierBillService {

    static final Set<String> PATCHABLE = Set.of(
            "supplierInvoiceNumber", "billDate", "accountingDate", "dueDate", "pricesIncludeTax", "notes", "lines");
    static final List<LinePatchReader.Member> LINE_MEMBERS = List.of(
            LinePatchReader.Member.uuid("goodsReceiptLineId", false),
            LinePatchReader.Member.uuid("purchaseOrderLineId", false),
            LinePatchReader.Member.uuid("variantId", false),
            LinePatchReader.Member.text("description", 300),
            LinePatchReader.Member.decimal("quantity", true),
            LinePatchReader.Member.uuid("uomId", false),
            LinePatchReader.Member.decimal("unitPrice", true),
            LinePatchReader.Member.decimal("discountPercent", false),
            LinePatchReader.Member.uuid("taxCodeId", false),
            LinePatchReader.Member.uuid("branchId", false),
            LinePatchReader.Member.uuid("departmentId", false));
    private static final int PRICE_SCALE = 10;

    private final BillRepository bills;
    private final ReceiptRepository receipts;
    private final PurchaseOrderRepository orders;
    private final PurchaseOrderService orderService;
    private final DocumentPricing pricing;
    private final PartnersFacade partners;
    private final InventoryFacade inventory;
    private final OrgFacade org;
    private final ProcurementSettingsService settings;
    private final ProcurementContext context;
    private final ProcurementConfiguration.BillSettlements settlements;
    private final DocumentNumberService numbering;
    private final ApplicationEventPublisher events;
    private final AuditPort audit;
    private final Clock clock;

    SupplierBillService(
            BillRepository bills,
            ReceiptRepository receipts,
            PurchaseOrderRepository orders,
            PurchaseOrderService orderService,
            DocumentPricing pricing,
            PartnersFacade partners,
            InventoryFacade inventory,
            OrgFacade org,
            ProcurementSettingsService settings,
            ProcurementContext context,
            ProcurementConfiguration.BillSettlements settlements,
            DocumentNumberService numbering,
            ApplicationEventPublisher events,
            AuditPort audit,
            Clock clock) {
        this.bills = bills;
        this.receipts = receipts;
        this.orders = orders;
        this.orderService = orderService;
        this.pricing = pricing;
        this.partners = partners;
        this.inventory = inventory;
        this.org = org;
        this.settings = settings;
        this.context = context;
        this.settlements = settlements;
        this.numbering = numbering;
        this.events = events;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PageResponse<ProcurementViews.SupplierBill> list(ListQuery query) {
        return bills.list(CurrentContext.requireCompany(), query);
    }

    @Transactional(readOnly = true)
    public ProcurementViews.SupplierBillDetail get(UUID id) {
        UUID companyId = CurrentContext.requireCompany();
        ProcurementViews.SupplierBill bill = bills.find(companyId, id).orElseThrow(ApiException::notFound);
        return new ProcurementViews.SupplierBillDetail(bill, bills.lines(companyId, id), bills.taxes(companyId, id));
    }

    @Transactional
    public ProcurementViews.SupplierBillDetail create(ProcurementCommands.SupplierBill command) {
        UUID companyId = CurrentContext.requireCompany();
        Draft draft = draft(command, null);
        UUID actor = context.actor();
        UUID id = bills.insert(companyId, draft.header(), actor);
        bills.insertLines(companyId, id, draft.lines(), actor);
        bills.replaceTaxes(companyId, id, draft.taxes());
        audit.record(AuditEvent.builder("CREATE", "procurement")
                .entity("supplier_bill", id, command.supplierInvoiceNumber())
                .detail("documentType", command.documentType())
                .detail("supplierId", command.supplierId())
                .detail("currencyCode", draft.header().currencyCode())
                .detail("total", draft.header().total().toPlainString())
                .detail("lines", draft.lines().size())
                .build());
        return get(id);
    }

    /** Prefills a draft bill with what is still unbilled on the receipts, at the order prices. */
    @Transactional
    public ProcurementViews.SupplierBillDetail fromReceipts(
            List<UUID> receiptIds, String supplierInvoiceNumber, @Nullable LocalDate billDate) {
        UUID companyId = CurrentContext.requireCompany();
        List<ReceiptRepository.LineOfReceipt> open = receipts.unbilledLines(companyId, receiptIds);
        if (open.isEmpty()) {
            throw ApiException.validationFailed(
                    "Nothing to bill.",
                    List.of(FieldViolation.atPointer(
                            "/goodsReceiptIds", "NOTHING_TO_BILL", "have no posted, unbilled receipt lines")));
        }
        Set<UUID> suppliers = new HashSet<>();
        open.forEach(l -> suppliers.add(l.receipt().supplierId()));
        if (suppliers.size() > 1) {
            throw ApiException.validationFailed(
                    "The receipts are from different suppliers.",
                    List.of(FieldViolation.atPointer(
                            "/goodsReceiptIds", "MIXED_SUPPLIERS", "must be from one supplier")));
        }
        Map<UUID, PurchaseOrderRepository.LineOfOrder> orderLines = orders.linesById(
                companyId,
                open.stream().map(l -> l.line().purchaseOrderLineId()).toList());
        List<ProcurementCommands.BillLine> lines = new ArrayList<>();
        Set<UUID> orderIds = new LinkedHashSet<>();
        for (ReceiptRepository.LineOfReceipt l : open) {
            ProcurementViews.PurchaseOrderLine orderLine =
                    orderLines.get(l.line().purchaseOrderLineId()).line();
            orderIds.add(orderLines.get(l.line().purchaseOrderLineId()).purchaseOrderId());
            BigDecimal quantity = l.line().openToBillBase();
            boolean whole = quantity.compareTo(orderLine.quantityBase()) == 0;
            lines.add(new ProcurementCommands.BillLine(
                    l.line().id(),
                    null,
                    null,
                    orderLine.description(),
                    whole ? orderLine.quantity() : quantity,
                    whole
                            ? orderLine.uomId()
                            : inventory
                                    .variantInfo(orderLine.variantId())
                                    .orElseThrow()
                                    .baseUomId(),
                    whole
                            ? orderLine.unitPrice()
                            : orderLine
                                    .unitPrice()
                                    .multiply(orderLine.quantity())
                                    .divide(
                                            orderLine.quantityBase(),
                                            DocumentPricing.UNIT_PRICE_SCALE,
                                            RoundingMode.HALF_UP),
                    orderLine.discountPercent(),
                    orderLine.taxCodeId(),
                    null,
                    null));
        }
        UUID orderId = orderIds.size() == 1 ? orderIds.iterator().next() : null;
        boolean inclusive = orderId != null
                && orders.find(companyId, orderId)
                        .map(ProcurementViews.PurchaseOrder::pricesIncludeTax)
                        .orElse(false);
        return create(new ProcurementCommands.SupplierBill(
                "BILL",
                suppliers.iterator().next(),
                supplierInvoiceNumber,
                billDate != null ? billDate : context.today(context.profile()),
                null,
                null,
                orderId,
                null,
                inclusive,
                null,
                lines));
    }

    /** Edits a draft; {@code lines} replaces all lines; the match must be checked again. */
    @Transactional
    public ProcurementViews.SupplierBillDetail patch(UUID id, @Nullable String ifMatch, JsonNode document) {
        UUID companyId = CurrentContext.requireCompany();
        ProcurementViews.SupplierBill current = lock(companyId, id, ifMatch, DocumentStatus.Action.EDIT);
        MergePatch patch = MergePatch.of(document, PATCHABLE);
        var invoice = patch.text("supplierInvoiceNumber", true, 50);
        var billDate = patch.date("billDate", true);
        var accountingDate = patch.date("accountingDate", false);
        var dueDate = patch.date("dueDate", false);
        var inclusive = patch.bool("pricesIncludeTax");
        var notes = patch.text("notes", false, 2000);
        patch.throwIfInvalid();
        List<ProcurementCommands.BillLine> lines = document.has("lines")
                ? readLines(document.get("lines"))
                : bills.lines(companyId, id).stream()
                        .map(SupplierBillService::asCommand)
                        .toList();
        ProcurementCommands.SupplierBill next = new ProcurementCommands.SupplierBill(
                current.documentType(),
                current.supplierId(),
                invoice.orElse(current.supplierInvoiceNumber()),
                billDate.orElse(current.billDate()),
                accountingDate.orElse(accountingDate.present() ? null : current.accountingDate()),
                dueDate.orElse(dueDate.present() ? null : current.dueDate()),
                current.purchaseOrderId(),
                current.originalBillId(),
                Boolean.TRUE.equals(inclusive.orElse(current.pricesIncludeTax())),
                notes.orElse(current.notes()),
                lines);
        Draft draft = draft(next, current);
        UUID actor = context.actor();
        bills.deleteLines(companyId, id);
        bills.insertLines(companyId, id, draft.lines(), actor);
        bills.replaceTaxes(companyId, id, draft.taxes());
        if (!bills.updateDraft(companyId, id, current.version(), actor, draft.header())) {
            throw new ApiException(PlatformErrorCode.VERSION_CONFLICT, "The bill was modified concurrently.");
        }
        ProcurementViews.SupplierBill after = bills.find(companyId, id).orElseThrow();
        audit.record(AuditEvent.builder("UPDATE", "procurement")
                .entity("supplier_bill", id, after.supplierInvoiceNumber())
                .change("supplierInvoiceNumber", current.supplierInvoiceNumber(), after.supplierInvoiceNumber())
                .change("billDate", current.billDate(), after.billDate())
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
        ProcurementViews.SupplierBill current = lock(companyId, id, ifMatch, DocumentStatus.Action.DELETE);
        if (!bills.delete(companyId, id, current.version())) {
            throw new ApiException(PlatformErrorCode.VERSION_CONFLICT, "The bill was modified concurrently.");
        }
        audit.record(AuditEvent.builder("DELETE", "procurement")
                .entity("supplier_bill", id, current.supplierInvoiceNumber())
                .build());
    }

    @Transactional
    public ProcurementViews.SupplierBillDetail cancel(UUID id, @Nullable String ifMatch) {
        UUID companyId = CurrentContext.requireCompany();
        ProcurementViews.SupplierBill current = lock(companyId, id, ifMatch, DocumentStatus.Action.CANCEL);
        if (!bills.markCancelled(companyId, id, current.version(), context.actor())) {
            throw new ApiException(PlatformErrorCode.VERSION_CONFLICT, "The bill was modified concurrently.");
        }
        audit.record(AuditEvent.builder("STATE_CHANGE", "procurement")
                .entity("supplier_bill", id, current.supplierInvoiceNumber())
                .transition("DRAFT", "CANCELLED")
                .build());
        return get(id);
    }

    /** Runs the three-way match and records MATCHED or EXCEPTION (PRC-3). */
    @Transactional
    public ProcurementViews.MatchResult checkMatch(UUID id, @Nullable String ifMatch) {
        UUID companyId = CurrentContext.requireCompany();
        ProcurementViews.SupplierBill current = lock(companyId, id, ifMatch, DocumentStatus.Action.EDIT);
        List<ProcurementViews.MatchIssue> issues = match(current, bills.lines(companyId, id));
        String status = issues.isEmpty() ? "MATCHED" : "EXCEPTION";
        if (!bills.setMatch(companyId, id, current.version(), context.actor(), status, null, null)) {
            throw new ApiException(PlatformErrorCode.VERSION_CONFLICT, "The bill was modified concurrently.");
        }
        audit.record(AuditEvent.builder("MATCH_CHECK", "procurement")
                .entity("supplier_bill", id, current.supplierInvoiceNumber())
                .change("matchStatus", current.matchStatus(), status)
                .detail("issues", issues.size())
                .build());
        return new ProcurementViews.MatchResult(status, issues);
    }

    /** Accepts a failed match with a reason (PRC-3); needs {@code override_match}. */
    @Transactional
    public ProcurementViews.SupplierBillDetail overrideMatch(UUID id, @Nullable String ifMatch, String reason) {
        UUID companyId = CurrentContext.requireCompany();
        ProcurementViews.SupplierBill current = lock(companyId, id, ifMatch, DocumentStatus.Action.EDIT);
        if (!"EXCEPTION".equals(current.matchStatus())) {
            throw new ApiException(
                    PlatformErrorCode.INVALID_STATE,
                    "Only a bill whose match failed can be overridden; check it first.");
        }
        if (!bills.setMatch(companyId, id, current.version(), context.actor(), "OVERRIDDEN", context.actor(), reason)) {
            throw new ApiException(PlatformErrorCode.VERSION_CONFLICT, "The bill was modified concurrently.");
        }
        audit.record(AuditEvent.builder("MATCH_OVERRIDE", "procurement")
                .entity("supplier_bill", id, current.supplierInvoiceNumber())
                .change("matchStatus", current.matchStatus(), "OVERRIDDEN")
                .detail("reason", reason)
                .build());
        return get(id);
    }

    /**
     * Posts the bill or debit note under the locks of its orders: re-runs the match, clears the
     * receipt values, updates billed quantities and billing status, numbers the document and
     * publishes {@code procurement.supplier_bill.posted} / {@code debit_note.posted}.
     */
    @Transactional
    public ProcurementViews.SupplierBillDetail post(UUID id, @Nullable String ifMatch) {
        UUID companyId = CurrentContext.requireCompany();
        ProcurementViews.SupplierBill bill = lock(companyId, id, ifMatch, DocumentStatus.Action.POST);
        List<ProcurementViews.SupplierBillLine> lines = bills.lines(companyId, id);
        Set<UUID> orderIds = new LinkedHashSet<>();
        Map<UUID, PurchaseOrderRepository.LineOfOrder> orderLines = orders.linesById(
                companyId,
                lines.stream()
                        .map(ProcurementViews.SupplierBillLine::purchaseOrderLineId)
                        .filter(Objects::nonNull)
                        .toList());
        orderLines.values().forEach(l -> orderIds.add(l.purchaseOrderId()));
        Map<UUID, ProcurementViews.PurchaseOrder> lockedOrders = orders.lockAll(companyId, orderIds);
        for (ProcurementViews.PurchaseOrder order : lockedOrders.values()) {
            if (!PurchaseOrderStatus.valueOf(order.status()).allows(PurchaseOrderStatus.Action.BILL)) {
                throw new ApiException(
                        PlatformErrorCode.INVALID_STATE,
                        "Purchase order " + order.number() + " is " + order.status() + ".");
            }
        }
        boolean debitNote = "DEBIT_NOTE".equals(bill.documentType());
        if (debitNote) {
            checkDebitNote(bill, lines);
        } else {
            List<ProcurementViews.MatchIssue> issues = match(bill, lines);
            if (!issues.isEmpty() && !"OVERRIDDEN".equals(bill.matchStatus())) {
                throw new ApiException(
                        ProcurementErrorCode.MATCH_EXCEPTION,
                        "The bill does not match its orders and receipts; check the match or have it overridden.",
                        issues.stream()
                                .map(i -> new FieldViolation(
                                        "/lines/" + (i.lineNo() - 1) + "/quantity",
                                        null,
                                        "MATCH_" + i.problem(),
                                        "expected "
                                                + i.expected()
                                                        .stripTrailingZeros()
                                                        .toPlainString() + ", billed "
                                                + i.actual()
                                                        .stripTrailingZeros()
                                                        .toPlainString(),
                                        Map.of(
                                                "expected",
                                                        i.expected()
                                                                .stripTrailingZeros()
                                                                .toPlainString(),
                                                "actual",
                                                        i.actual()
                                                                .stripTrailingZeros()
                                                                .toPlainString())))
                                .toList());
            }
        }
        if (!"OVERRIDDEN".equals(bill.matchStatus()) && !"MATCHED".equals(bill.matchStatus())) {
            if (!bills.setMatch(companyId, id, bill.version(), context.actor(), "MATCHED", null, null)) {
                throw new ApiException(PlatformErrorCode.VERSION_CONFLICT, "The bill was modified concurrently.");
            }
            bill = bills.find(companyId, id).orElseThrow();
        }

        CompanyProfile profile = context.profile();
        RoundingPolicy rounding = context.baseRounding(profile);
        Map<UUID, ReceiptRepository.LineOfReceipt> receiptLines = receipts.linesById(
                companyId,
                lines.stream()
                        .map(ProcurementViews.SupplierBillLine::goodsReceiptLineId)
                        .filter(Objects::nonNull)
                        .toList());
        Map<UUID, ProcurementViews.GoodsReceiptLine> receiptState = new LinkedHashMap<>();
        receiptLines.forEach((k, v) -> receiptState.put(k, v.line()));
        Map<UUID, BigDecimal> billedDelta = new LinkedHashMap<>();
        Map<UUID, BigDecimal> receiptValues = new HashMap<>();
        for (ProcurementViews.SupplierBillLine line : lines) {
            if (line.goodsReceiptLineId() != null) {
                ProcurementViews.GoodsReceiptLine r = receiptState.get(line.goodsReceiptLineId());
                BigDecimal unitCost = Objects.requireNonNull(r.unitCostBase());
                BigDecimal value;
                if (debitNote) {
                    value = rounding.round(line.quantityBase().multiply(unitCost));
                    r = withCounters(
                            r,
                            r.billedQuantityBase(),
                            r.billedValueBase(),
                            r.creditedQuantityBase().add(line.quantityBase()),
                            r.creditedValueBase().add(value));
                } else {
                    BigDecimal open = r.openToBillBase().max(BigDecimal.ZERO);
                    BigDecimal valued = line.quantityBase().min(open);
                    value = valued.signum() == 0
                            ? BigDecimal.ZERO
                            : valued.compareTo(open) == 0
                                    ? r.openValueBase().max(BigDecimal.ZERO)
                                    : rounding.round(valued.multiply(unitCost));
                    r = withCounters(
                            r,
                            r.billedQuantityBase().add(line.quantityBase()),
                            r.billedValueBase().add(value),
                            r.creditedQuantityBase(),
                            r.creditedValueBase());
                }
                receiptState.put(r.id(), r);
                receiptValues.put(line.id(), value);
                bills.setReceiptValue(companyId, line.id(), value);
            }
            if (line.purchaseOrderLineId() != null) {
                billedDelta.merge(
                        line.purchaseOrderLineId(),
                        debitNote ? line.quantityBase().negate() : line.quantityBase(),
                        BigDecimal::add);
            }
        }
        receiptState
                .values()
                .forEach(r -> receipts.updateCounters(
                        companyId,
                        r.id(),
                        r.billedQuantityBase(),
                        r.billedValueBase(),
                        r.returnedQuantityBase(),
                        r.returnedValueBase(),
                        r.creditedQuantityBase(),
                        r.creditedValueBase()));
        billedDelta.forEach((lineId, delta) -> {
            ProcurementViews.PurchaseOrderLine orderLine =
                    orders.linesById(companyId, List.of(lineId)).get(lineId).line();
            orders.updateLineCounters(
                    companyId,
                    lineId,
                    orderLine.receivedQuantityBase(),
                    orderLine.returnedQuantityBase(),
                    orderLine.billedQuantityBase().add(delta));
        });
        lockedOrders.keySet().forEach(orderId -> orderService.billingProgress(companyId, orderId));

        String number = numbering.next(
                companyId,
                debitNote ? ProcurementConfiguration.DEBIT_NOTE : ProcurementConfiguration.SUPPLIER_BILL,
                FiscalYears.label(bill.accountingDate(), profile.fiscalYearStartMonth()));
        if (!bills.markPosted(companyId, id, bill.version(), context.actor(), number)) {
            throw new ApiException(PlatformErrorCode.VERSION_CONFLICT, "The bill was modified concurrently.");
        }
        audit.record(AuditEvent.builder("POST", "procurement")
                .entity("supplier_bill", id, number)
                .transition("DRAFT", "POSTED")
                .detail("documentType", bill.documentType())
                .detail("supplierInvoiceNumber", bill.supplierInvoiceNumber())
                .detail("totalBase", bill.totalBase().toPlainString())
                .detail("matchStatus", bill.matchStatus())
                .build());
        events.publishEvent(event(bill, number, bills.lines(companyId, id), bills.taxes(companyId, id)));
        return get(id);
    }

    /** What Accounting knows about the open amount (UNKNOWN until Phase 8). */
    @Transactional(readOnly = true)
    public BillSettlementPort.Settlement settlement(UUID id) {
        UUID companyId = CurrentContext.requireCompany();
        bills.find(companyId, id).orElseThrow(ApiException::notFound);
        return settlements.settlement(companyId, id);
    }

    // -------------------------------------------------------------------------- the match

    /**
     * PRC-3 for the bill's order-linked lines: billed quantity within the quantity tolerance of what
     * is open (received − returned − billed for receipt lines; ordered − billed otherwise) and net
     * unit price within the price tolerance of the order's. Earlier lines of the same bill count.
     */
    private List<ProcurementViews.MatchIssue> match(
            ProcurementViews.SupplierBill bill, List<ProcurementViews.SupplierBillLine> lines) {
        UUID companyId = bill.companyId();
        ProcurementViews.Settings tolerances = settings.current(companyId);
        Map<UUID, ReceiptRepository.LineOfReceipt> receiptLines = receipts.linesById(
                companyId,
                lines.stream()
                        .map(ProcurementViews.SupplierBillLine::goodsReceiptLineId)
                        .filter(Objects::nonNull)
                        .toList());
        Map<UUID, PurchaseOrderRepository.LineOfOrder> orderLines = orders.linesById(
                companyId,
                lines.stream()
                        .map(ProcurementViews.SupplierBillLine::purchaseOrderLineId)
                        .filter(Objects::nonNull)
                        .toList());
        Map<UUID, ProcurementViews.PurchaseOrder> headers = new HashMap<>();
        Map<UUID, BigDecimal> used = new HashMap<>();
        List<ThreeWayMatch.Line> checks = new ArrayList<>();
        for (ProcurementViews.SupplierBillLine line : lines) {
            if (line.purchaseOrderLineId() == null) {
                continue;
            }
            PurchaseOrderRepository.LineOfOrder orderLine = orderLines.get(line.purchaseOrderLineId());
            ProcurementViews.PurchaseOrder order = headers.computeIfAbsent(
                    orderLine.purchaseOrderId(), o -> orders.find(companyId, o).orElseThrow());
            BigDecimal open;
            UUID key;
            if (line.goodsReceiptLineId() != null) {
                open = receiptLines.get(line.goodsReceiptLineId()).line().openToBillBase();
                key = line.goodsReceiptLineId();
            } else {
                open = orderLine.line().quantityBase().subtract(orderLine.line().billedQuantityBase());
                key = line.purchaseOrderLineId();
            }
            open = open.subtract(used.getOrDefault(key, BigDecimal.ZERO)).max(BigDecimal.ZERO);
            used.merge(key, line.quantityBase(), BigDecimal::add);
            boolean comparable = order.pricesIncludeTax() == bill.pricesIncludeTax();
            BigDecimal orderPrice = comparable
                    ? unitPricePerBase(
                            orderLine.line().unitPrice(),
                            orderLine.line().quantity(),
                            orderLine.line().quantityBase(),
                            orderLine.line().discountPercent())
                    : orderLine
                            .line()
                            .netAmount()
                            .divide(orderLine.line().quantityBase(), PRICE_SCALE, RoundingMode.HALF_UP);
            BigDecimal billPrice = comparable
                    ? unitPricePerBase(line.unitPrice(), line.quantity(), line.quantityBase(), line.discountPercent())
                    : line.netAmount().divide(line.quantityBase(), PRICE_SCALE, RoundingMode.HALF_UP);
            checks.add(new ThreeWayMatch.Line(line.lineNo(), line.quantityBase(), open, orderPrice, billPrice));
        }
        return ThreeWayMatch.check(
                        checks, tolerances.qtyMatchTolerancePercent(), tolerances.priceMatchTolerancePercent())
                .stream()
                .map(i -> new ProcurementViews.MatchIssue(i.index(), i.problem().name(), i.expected(), i.actual()))
                .toList();
    }

    /** Price per base unit after discount, before rounding: {@code p × q × (1 − d) ÷ q_base}. */
    private static BigDecimal unitPricePerBase(
            BigDecimal unitPrice, BigDecimal quantity, BigDecimal quantityBase, BigDecimal discountPercent) {
        return unitPrice
                .multiply(quantity)
                .multiply(BigDecimal.ONE.subtract(discountPercent.divide(BigDecimal.valueOf(100))))
                .divide(quantityBase, PRICE_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * A debit note credits returned goods (never more than was returned and not yet credited) and
     * never more than is left of the original bill.
     */
    private void checkDebitNote(ProcurementViews.SupplierBill note, List<ProcurementViews.SupplierBillLine> lines) {
        UUID companyId = note.companyId();
        ProcurementViews.SupplierBill original = bills.lock(companyId, Objects.requireNonNull(note.originalBillId()))
                .orElseThrow();
        BigDecimal left = original.total().subtract(bills.creditedTotal(companyId, original.id()));
        if (note.total().compareTo(left) > 0) {
            throw new ApiException(
                    ProcurementErrorCode.DEBIT_NOTE_EXCEEDS_BILL,
                    "The debit note (" + note.total().toPlainString() + ") exceeds what is left of bill "
                            + original.number() + " (" + left.toPlainString() + ").");
        }
        Map<UUID, ReceiptRepository.LineOfReceipt> receiptLines = receipts.linesById(
                companyId,
                lines.stream()
                        .map(ProcurementViews.SupplierBillLine::goodsReceiptLineId)
                        .filter(Objects::nonNull)
                        .toList());
        Map<UUID, BigDecimal> used = new HashMap<>();
        List<FieldViolation> violations = new ArrayList<>();
        for (ProcurementViews.SupplierBillLine line : lines) {
            if (line.goodsReceiptLineId() == null) {
                continue;
            }
            ProcurementViews.GoodsReceiptLine r =
                    receiptLines.get(line.goodsReceiptLineId()).line();
            BigDecimal creditable = r.returnedQuantityBase()
                    .min(r.billedQuantityBase())
                    .subtract(r.creditedQuantityBase())
                    .subtract(used.getOrDefault(r.id(), BigDecimal.ZERO));
            used.merge(r.id(), line.quantityBase(), BigDecimal::add);
            if (line.quantityBase().compareTo(creditable) > 0) {
                violations.add(FieldViolation.atPointer(
                        "/lines/" + (line.lineNo() - 1) + "/quantity",
                        ProcurementErrorCode.QUANTITY_EXCEEDS_REMAINING.code(),
                        "credits " + line.quantityBase().stripTrailingZeros().toPlainString()
                                + " but only "
                                + creditable
                                        .max(BigDecimal.ZERO)
                                        .stripTrailingZeros()
                                        .toPlainString()
                                + " were returned after billing and not yet credited"));
            }
        }
        if (!violations.isEmpty()) {
            throw new ApiException(
                    ProcurementErrorCode.QUANTITY_EXCEEDS_REMAINING,
                    "The debit note credits more than was returned.",
                    violations);
        }
    }

    // --------------------------------------------------------------------------- drafting

    private record Draft(
            BillRepository.Header header,
            List<BillRepository.NewLine> lines,
            List<ProcurementViews.SupplierBillTax> taxes) {}

    /** A resolved line: what it is linked to, its product and unit. */
    private record Resolved(
            BillLineKind kind,
            @Nullable UUID purchaseOrderLineId,
            @Nullable UUID goodsReceiptLineId,
            UUID purchaseOrderId,
            UUID variantId,
            UUID uomId,
            @Nullable UUID branchId,
            @Nullable UUID departmentId) {}

    private Draft draft(ProcurementCommands.SupplierBill command, ProcurementViews.@Nullable SupplierBill current) {
        UUID companyId = CurrentContext.requireCompany();
        CompanyProfile profile = context.profile();
        List<FieldViolation> violations = new ArrayList<>();
        boolean debitNote = "DEBIT_NOTE".equals(command.documentType());
        if (!debitNote && !"BILL".equals(command.documentType())) {
            throw ApiException.validationFailed(
                    "The document type is invalid.",
                    List.of(FieldViolation.atPointer("/documentType", "INVALID_VALUE", "must be BILL or DEBIT_NOTE")));
        }
        SupplierInfo supplier = partners.supplierForUse(command.supplierId())
                .orElseThrow(() -> ApiException.validationFailed(
                        "The supplier is unknown.",
                        List.of(FieldViolation.atPointer(
                                "/supplierId", "UNKNOWN_SUPPLIER", "is not a supplier of the company"))));
        ProcurementViews.SupplierBill original = null;
        if (debitNote) {
            original = command.originalBillId() == null
                    ? null
                    : bills.find(companyId, command.originalBillId())
                            .filter(b -> "BILL".equals(b.documentType())
                                    && "POSTED".equals(b.status())
                                    && b.supplierId().equals(command.supplierId()))
                            .orElse(null);
            if (original == null) {
                throw ApiException.validationFailed(
                        "The original bill is invalid.",
                        List.of(FieldViolation.atPointer(
                                "/originalBillId", "INVALID_VALUE", "must be a posted bill of the same supplier")));
            }
        } else if (command.originalBillId() != null) {
            violations.add(FieldViolation.atPointer("/originalBillId", "NOT_ALLOWED", "only debit notes have one"));
        }

        List<Resolved> resolved = resolve(command, debitNote, violations);
        Set<UUID> orderIds = new LinkedHashSet<>();
        resolved.stream()
                .filter(Objects::nonNull)
                .map(Resolved::purchaseOrderId)
                .filter(Objects::nonNull)
                .forEach(orderIds::add);
        if (command.purchaseOrderId() != null) {
            orderIds.add(command.purchaseOrderId());
        }
        Map<UUID, ProcurementViews.PurchaseOrder> orderHeaders = new LinkedHashMap<>();
        for (UUID orderId : orderIds) {
            var order = orders.find(companyId, orderId).orElse(null);
            if (order == null || !order.supplierId().equals(command.supplierId())) {
                violations.add(FieldViolation.atPointer(
                        "/purchaseOrderId", "INVALID_VALUE", "every order must be one of this supplier's orders"));
            } else {
                orderHeaders.put(orderId, order);
            }
        }
        String currency = original != null
                ? original.currencyCode()
                : !orderHeaders.isEmpty()
                        ? orderHeaders.values().iterator().next().currencyCode()
                        : supplier.currencyCode();
        if (orderHeaders.values().stream().anyMatch(o -> !o.currencyCode().equals(currency))) {
            violations.add(FieldViolation.atPointer(
                    "/lines", "CURRENCY_MISMATCH", "all orders of a bill must be in the bill's currency " + currency));
        }
        boolean direct = !debitNote && orderHeaders.isEmpty();
        if (direct) {
            context.require(ProcurementPermissions.BILL_CREATE_DIRECT, "Billing without a purchase order");
            if (!supplier.usable()) {
                throw new ApiException(
                        ProcurementErrorCode.PARTNER_BLOCKED,
                        "Supplier " + supplier.code() + " is " + supplier.status()
                                + " and cannot be used on new documents.",
                        List.of(FieldViolation.atPointer(
                                "/supplierId", supplier.status(), "is not an active supplier")));
            }
        }
        if (bills.invoiceNumberTaken(
                companyId,
                command.supplierId(),
                command.documentType(),
                command.supplierInvoiceNumber(),
                current == null ? null : current.id())) {
            throw new ApiException(
                    ProcurementErrorCode.DUPLICATE_SUPPLIER_INVOICE,
                    "The supplier's document " + command.supplierInvoiceNumber() + " is already recorded.",
                    List.of(FieldViolation.atPointer(
                            "/supplierInvoiceNumber", "DUPLICATE", "is already used for this supplier")));
        }

        LocalDate billDate = command.billDate();
        LocalDate accountingDate = command.accountingDate() != null ? command.accountingDate() : billDate;
        UUID terms = original != null
                ? original.paymentTermsId()
                : orderHeaders.isEmpty()
                        ? supplier.paymentTermsId()
                        : orderHeaders.values().iterator().next().paymentTermsId();
        LocalDate dueDate = command.dueDate();
        if (dueDate == null) {
            dueDate = terms == null
                    ? billDate
                    : org.paymentTerms(companyId, terms)
                            .map(t -> t.dueDate(billDate))
                            .orElse(billDate);
        }
        if (dueDate.isBefore(billDate)) {
            violations.add(
                    FieldViolation.atPointer("/dueDate", "BEFORE_BILL_DATE", "must not be before the bill date"));
        }
        if (!context.currencyUsable(currency)) {
            violations.add(FieldViolation.atPointer("/currencyCode", "UNKNOWN_CURRENCY", "is not an active currency"));
        }

        List<DocumentPricing.Input> inputs = new ArrayList<>();
        for (int i = 0; i < command.lines().size(); i++) {
            ProcurementCommands.BillLine l = command.lines().get(i);
            Resolved r = i < resolved.size() ? resolved.get(i) : null;
            if (r == null) {
                continue;
            }
            inputs.add(new DocumentPricing.Input(
                    i,
                    r.variantId(),
                    l.description(),
                    l.quantity(),
                    r.uomId(),
                    l.unitPrice(),
                    l.discountPercent(),
                    l.taxCodeId()));
        }
        RoundingPolicy docRounding = context.rounding(currency, profile);
        DocumentPricing.Priced priced =
                pricing.price(inputs, command.pricesIncludeTax(), currency, billDate, profile, docRounding, violations);
        BigDecimal rate = context.exchangeRate(currency, billDate);
        RoundingPolicy baseRounding = context.baseRounding(profile);

        List<BillRepository.NewLine> lines = new ArrayList<>();
        BigDecimal subtotalBase = BigDecimal.ZERO;
        BigDecimal taxBase = BigDecimal.ZERO;
        Map<UUID, BigDecimal[]> taxTotals = new LinkedHashMap<>();
        for (int i = 0; i < priced.lines().size(); i++) {
            DocumentPricing.Line p = priced.lines().get(i);
            Resolved r = resolved.get(p.input().index());
            BigDecimal netBase = baseRounding.round(p.net().multiply(rate));
            BigDecimal lineTaxBase = baseRounding.round(p.tax().multiply(rate));
            subtotalBase = subtotalBase.add(netBase);
            taxBase = taxBase.add(lineTaxBase);
            if (p.input().taxCodeId() != null) {
                BigDecimal[] sums = taxTotals.computeIfAbsent(
                        p.input().taxCodeId(), k -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
                sums[0] = sums[0].add(netBase);
                sums[1] = sums[1].add(lineTaxBase);
            }
            lines.add(new BillRepository.NewLine(
                    i + 1,
                    r.kind().name(),
                    r.purchaseOrderLineId(),
                    r.goodsReceiptLineId(),
                    r.variantId(),
                    p.description(),
                    p.input().quantity(),
                    r.uomId(),
                    p.quantityBase(),
                    p.input().unitPrice(),
                    p.input().discountPercent(),
                    p.input().taxCodeId(),
                    p.net(),
                    p.tax(),
                    p.total(),
                    netBase,
                    lineTaxBase,
                    r.branchId(),
                    r.departmentId()));
        }
        List<ProcurementViews.SupplierBillTax> taxes = new ArrayList<>();
        for (TaxCalculator.TaxTotal t : priced.result().taxes()) {
            BigDecimal[] sums = taxTotals.get(t.taxCodeId());
            taxes.add(new ProcurementViews.SupplierBillTax(
                    t.taxCodeId(), t.ratePercent(), t.taxable(), t.tax(), sums[0], sums[1]));
        }
        UUID headerOrder = command.purchaseOrderId() != null
                ? command.purchaseOrderId()
                : orderHeaders.size() == 1 ? orderHeaders.keySet().iterator().next() : null;
        return new Draft(
                new BillRepository.Header(
                        command.documentType(),
                        command.supplierInvoiceNumber(),
                        command.supplierId(),
                        headerOrder,
                        command.originalBillId(),
                        billDate,
                        accountingDate,
                        dueDate,
                        currency,
                        rate,
                        command.pricesIncludeTax(),
                        terms,
                        priced.result().subtotal(),
                        priced.result().taxTotal(),
                        priced.result().total(),
                        subtotalBase,
                        taxBase,
                        subtotalBase.add(taxBase),
                        command.notes()),
                lines,
                taxes);
    }

    /** Resolves each line's kind and links; problems go to {@code violations} (the list keeps positions). */
    private List<Resolved> resolve(
            ProcurementCommands.SupplierBill command, boolean debitNote, List<FieldViolation> violations) {
        UUID companyId = CurrentContext.requireCompany();
        List<ProcurementCommands.BillLine> lines = command.lines();
        Map<UUID, ReceiptRepository.LineOfReceipt> receiptLines = receipts.linesById(
                companyId,
                lines.stream()
                        .map(ProcurementCommands.BillLine::goodsReceiptLineId)
                        .filter(Objects::nonNull)
                        .toList());
        Map<UUID, PurchaseOrderRepository.LineOfOrder> orderLines = new HashMap<>(orders.linesById(
                companyId,
                lines.stream()
                        .map(ProcurementCommands.BillLine::purchaseOrderLineId)
                        .filter(Objects::nonNull)
                        .toList()));
        orderLines.putAll(orders.linesById(
                companyId,
                receiptLines.values().stream()
                        .map(l -> l.line().purchaseOrderLineId())
                        .toList()));
        List<Resolved> result = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            ProcurementCommands.BillLine l = lines.get(i);
            String at = "/lines/" + i;
            Resolved r = null;
            if (l.goodsReceiptLineId() != null) {
                ReceiptRepository.LineOfReceipt receiptLine = receiptLines.get(l.goodsReceiptLineId());
                if (receiptLine == null
                        || !"POSTED".equals(receiptLine.receipt().status())
                        || !receiptLine.receipt().supplierId().equals(command.supplierId())) {
                    violations.add(FieldViolation.atPointer(
                            at + "/goodsReceiptLineId",
                            "INVALID_VALUE",
                            "must be a posted receipt line of this supplier"));
                } else {
                    var orderLine = orderLines.get(receiptLine.line().purchaseOrderLineId());
                    r = new Resolved(
                            BillLineKind.RECEIVED_STOCK,
                            orderLine.line().id(),
                            receiptLine.line().id(),
                            orderLine.purchaseOrderId(),
                            receiptLine.line().variantId(),
                            l.uomId() != null ? l.uomId() : orderLine.line().uomId(),
                            l.branchId() != null
                                    ? l.branchId()
                                    : receiptLine.receipt().branchId(),
                            l.departmentId());
                }
            } else if (l.purchaseOrderLineId() != null) {
                var orderLine = orderLines.get(l.purchaseOrderLineId());
                if (debitNote) {
                    violations.add(FieldViolation.atPointer(
                            at + "/purchaseOrderLineId",
                            "NOT_ALLOWED",
                            "debit notes credit receipt lines or products"));
                } else if (orderLine == null) {
                    violations.add(FieldViolation.atPointer(
                            at + "/purchaseOrderLineId", "UNKNOWN_LINE", "is not a purchase order line"));
                } else if (orderLine.line().stockable()) {
                    // PRC-4: stockable goods are billed from their receipt (receipt before bill).
                    violations.add(FieldViolation.atPointer(
                            at + "/purchaseOrderLineId",
                            "RECEIPT_REQUIRED",
                            "is a stockable product; bill it from its goods receipt line"));
                } else {
                    var variant =
                            inventory.variantInfo(orderLine.line().variantId()).orElseThrow();
                    r = new Resolved(
                            BillLineKind.ofProductType(variant.productType()),
                            orderLine.line().id(),
                            null,
                            orderLine.purchaseOrderId(),
                            orderLine.line().variantId(),
                            l.uomId() != null ? l.uomId() : orderLine.line().uomId(),
                            l.branchId(),
                            l.departmentId());
                }
            } else if (l.variantId() == null) {
                violations.add(FieldViolation.atPointer(
                        at, "LINK_REQUIRED", "needs a goodsReceiptLineId, purchaseOrderLineId or variantId"));
            } else {
                var variant = inventory.variantInfo(l.variantId()).orElse(null);
                if (variant == null) {
                    violations.add(FieldViolation.atPointer(
                            at + "/variantId", "UNKNOWN_VARIANT", "is not a product variant of the company"));
                } else if ("STOCKABLE".equals(variant.productType())) {
                    violations.add(FieldViolation.atPointer(
                            at + "/variantId",
                            "RECEIPT_REQUIRED",
                            "is a stockable product; it is billed from a goods receipt"));
                } else {
                    r = new Resolved(
                            BillLineKind.ofProductType(variant.productType()),
                            null,
                            null,
                            null,
                            l.variantId(),
                            l.uomId() != null ? l.uomId() : variant.baseUomId(),
                            l.branchId(),
                            l.departmentId());
                }
            }
            if (r != null) {
                checkDimensions(i, r, violations);
            }
            result.add(r);
        }
        return result;
    }

    private void checkDimensions(int index, Resolved r, List<FieldViolation> violations) {
        UUID companyId = CurrentContext.requireCompany();
        if (r.branchId() != null
                && !org.branches(companyId, List.of(r.branchId())).containsKey(r.branchId())) {
            violations.add(FieldViolation.atPointer(
                    "/lines/" + index + "/branchId", "UNKNOWN_BRANCH", "is not a branch of the company"));
        }
        if (r.departmentId() != null
                && org.departmentForUse(companyId, r.departmentId()).isEmpty()) {
            violations.add(FieldViolation.atPointer(
                    "/lines/" + index + "/departmentId", "UNKNOWN_DEPARTMENT", "is not a department of the company"));
        }
    }

    private SupplierBillPosted event(
            ProcurementViews.SupplierBill bill,
            String number,
            List<ProcurementViews.SupplierBillLine> lines,
            List<ProcurementViews.SupplierBillTax> taxes) {
        UUID companyId = bill.companyId();
        boolean debitNote = "DEBIT_NOTE".equals(bill.documentType());
        UUID group = partners.supplier(bill.supplierId())
                .map(SupplierInfo::supplierGroupId)
                .orElse(null);
        return new SupplierBillPosted(
                DomainEvents.metadata(
                        debitNote ? SupplierBillPosted.DEBIT_NOTE_TYPE : SupplierBillPosted.BILL_TYPE,
                        SupplierBillPosted.SCHEMA_VERSION,
                        companyId,
                        clock),
                bill.id(),
                bill.documentType(),
                number,
                bill.supplierInvoiceNumber(),
                bill.supplierId(),
                group,
                bill.originalBillId(),
                bill.purchaseOrderId(),
                bill.billDate(),
                bill.accountingDate(),
                bill.dueDate(),
                bill.currencyCode(),
                bill.exchangeRate(),
                new SupplierBillPosted.Totals(
                        bill.subtotal(),
                        bill.taxTotal(),
                        bill.total(),
                        bill.subtotalBase(),
                        bill.taxTotalBase(),
                        bill.totalBase()),
                lines.stream()
                        .map(l -> new SupplierBillPosted.Line(
                                l.id(),
                                BillLineKind.valueOf(l.lineKind()).eventType(),
                                l.variantId(),
                                inventory
                                        .variantInfo(l.variantId())
                                        .orElseThrow()
                                        .categoryId(),
                                l.purchaseOrderLineId(),
                                l.goodsReceiptLineId(),
                                l.quantityBase(),
                                l.netAmount(),
                                l.netAmountBase(),
                                l.receiptValueBase(),
                                l.taxCodeId(),
                                l.branchId(),
                                l.departmentId()))
                        .toList(),
                taxes.stream()
                        .map(t -> new SupplierBillPosted.TaxLine(
                                t.taxCodeId(),
                                t.ratePercent(),
                                t.taxableAmount(),
                                t.taxAmount(),
                                t.taxableAmountBase(),
                                t.taxAmountBase()))
                        .toList());
    }

    private ProcurementViews.SupplierBill lock(
            UUID companyId, UUID id, @Nullable String ifMatch, DocumentStatus.Action action) {
        ProcurementViews.SupplierBill current = bills.lock(companyId, id).orElseThrow(ApiException::notFound);
        EntityTags.requireMatch(ifMatch, current.version());
        if (!DocumentStatus.valueOf(current.status()).allows(action)) {
            throw new ApiException(
                    PlatformErrorCode.INVALID_STATE,
                    "A " + current.status() + " bill does not allow "
                            + action.name().toLowerCase(java.util.Locale.ROOT) + ".");
        }
        return current;
    }

    private static ProcurementViews.GoodsReceiptLine withCounters(
            ProcurementViews.GoodsReceiptLine r,
            BigDecimal billedQty,
            BigDecimal billedValue,
            BigDecimal creditedQty,
            BigDecimal creditedValue) {
        return new ProcurementViews.GoodsReceiptLine(
                r.id(),
                r.goodsReceiptId(),
                r.lineNo(),
                r.purchaseOrderLineId(),
                r.variantId(),
                r.locationId(),
                r.quantity(),
                r.uomId(),
                r.quantityBase(),
                r.unitCostDoc(),
                r.unitCostBase(),
                r.valueBase(),
                billedQty,
                billedValue,
                r.returnedQuantityBase(),
                r.returnedValueBase(),
                creditedQty,
                creditedValue);
    }

    static List<ProcurementCommands.BillLine> readLines(JsonNode array) {
        return LinePatchReader.read(array, LINE_MEMBERS).stream()
                .map(v -> new ProcurementCommands.BillLine(
                        v.uuid("goodsReceiptLineId"),
                        v.uuid("purchaseOrderLineId"),
                        v.uuid("variantId"),
                        v.text("description"),
                        v.decimal("quantity"),
                        v.uuid("uomId"),
                        v.decimal("unitPrice"),
                        v.decimal("discountPercent") == null ? BigDecimal.ZERO : v.decimal("discountPercent"),
                        v.uuid("taxCodeId"),
                        v.uuid("branchId"),
                        v.uuid("departmentId")))
                .toList();
    }

    private static ProcurementCommands.BillLine asCommand(ProcurementViews.SupplierBillLine l) {
        return new ProcurementCommands.BillLine(
                l.goodsReceiptLineId(),
                l.goodsReceiptLineId() == null ? l.purchaseOrderLineId() : null,
                l.purchaseOrderLineId() == null ? l.variantId() : null,
                l.description(),
                l.quantity(),
                l.uomId(),
                l.unitPrice(),
                l.discountPercent(),
                l.taxCodeId(),
                l.branchId(),
                l.departmentId());
    }
}
