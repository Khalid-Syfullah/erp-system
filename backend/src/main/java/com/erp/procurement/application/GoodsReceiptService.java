package com.erp.procurement.application;

import com.erp.inventory.api.InventoryFacade;
import com.erp.inventory.api.InventoryFacade.InLine;
import com.erp.inventory.api.InventoryFacade.PostedLine;
import com.erp.inventory.api.InventoryFacade.PostedMovement;
import com.erp.inventory.api.InventoryFacade.SourceRef;
import com.erp.inventory.api.InventoryFacade.StockInRequest;
import com.erp.org.api.CompanyProfile;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditPort;
import com.erp.platform.context.CurrentContext;
import com.erp.platform.events.DomainEvents;
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
import com.erp.procurement.domain.DocumentStatus;
import com.erp.procurement.domain.PurchaseOrderStatus;
import com.erp.procurement.domain.ThreeWayMatch;
import com.erp.procurement.events.GoodsReceiptPosted;
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
import java.util.List;
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
 * Goods receipts (PRODUCT_SPEC.md §7): drafts are made from an approved order; posting puts the
 * stock in through {@link InventoryFacade#receive} at the order's net unit price converted at the
 * receipt date's exchange rate (PRC-2), never more than the open quantity plus the over-receipt
 * tolerance (PRC-1). Posting locks the receipt, then the order (which serializes all receipts of an
 * order); a receipt is posted once (its state, Inventory's one-movement-per-source rule and the
 * {@code Idempotency-Key}). A posted receipt is corrected by a purchase return.
 */
@Service
public class GoodsReceiptService {

    static final String SOURCE_MODULE = "procurement";
    static final String SOURCE_TYPE = "GOODS_RECEIPT";
    static final Set<String> PATCHABLE = Set.of("receiptDate", "supplierDeliveryNote", "notes", "lines");
    static final List<MergePatchLines.Member> LINE_MEMBERS = List.of(
            MergePatchLines.Member.uuid("purchaseOrderLineId", true),
            MergePatchLines.Member.decimal("quantity", true),
            MergePatchLines.Member.uuid("uomId", false),
            MergePatchLines.Member.uuid("locationId", false));

    private final ReceiptRepository receipts;
    private final PurchaseOrderRepository orders;
    private final PurchaseOrderService orderService;
    private final DocumentPricing pricing;
    private final InventoryFacade inventory;
    private final ProcurementContext context;
    private final DocumentNumberService numbering;
    private final ApplicationEventPublisher events;
    private final AuditPort audit;
    private final Clock clock;

    GoodsReceiptService(
            ReceiptRepository receipts,
            PurchaseOrderRepository orders,
            PurchaseOrderService orderService,
            DocumentPricing pricing,
            InventoryFacade inventory,
            ProcurementContext context,
            DocumentNumberService numbering,
            ApplicationEventPublisher events,
            AuditPort audit,
            Clock clock) {
        this.receipts = receipts;
        this.orders = orders;
        this.orderService = orderService;
        this.pricing = pricing;
        this.inventory = inventory;
        this.context = context;
        this.numbering = numbering;
        this.events = events;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PageResponse<ProcurementViews.GoodsReceipt> list(ListQuery query) {
        return receipts.list(
                CurrentContext.requireCompany(), CurrentContext.require().branchScope(), query);
    }

    @Transactional(readOnly = true)
    public ProcurementViews.GoodsReceiptDetail get(UUID id) {
        UUID companyId = CurrentContext.requireCompany();
        ProcurementViews.GoodsReceipt receipt =
                visible(receipts.find(companyId, id).orElseThrow(ApiException::notFound));
        return new ProcurementViews.GoodsReceiptDetail(receipt, receipts.lines(companyId, id));
    }

    /** A draft receipt of an approved order; without lines, everything still open on its stockable lines. */
    @Transactional
    public ProcurementViews.GoodsReceiptDetail create(ProcurementCommands.Receipt command) {
        UUID companyId = CurrentContext.requireCompany();
        ProcurementViews.PurchaseOrder order = orders.find(companyId, command.purchaseOrderId())
                .filter(o -> context.canSeeBranch(o.branchId()))
                .orElseThrow(() -> ApiException.validationFailed(
                        "The purchase order is unknown.",
                        List.of(FieldViolation.atPointer(
                                "/purchaseOrderId", "UNKNOWN_PURCHASE_ORDER", "is not a purchase order you can use"))));
        requireReceivable(order);
        CompanyProfile profile = context.profile();
        LocalDate date = command.receiptDate() != null ? command.receiptDate() : context.today(profile);
        List<ReceiptRepository.NewLine> lines = lines(order, command.lines());
        UUID actor = context.actor();
        UUID id = receipts.insert(companyId, order, date, command.supplierDeliveryNote(), command.notes(), actor);
        receipts.insertLines(companyId, id, lines, actor);
        audit.record(AuditEvent.builder("CREATE", "procurement")
                .entity("goods_receipt", id, null)
                .detail("purchaseOrderId", order.id())
                .detail("lines", lines.size())
                .build());
        return get(id);
    }

    @Transactional
    public ProcurementViews.GoodsReceiptDetail patch(UUID id, @Nullable String ifMatch, JsonNode document) {
        UUID companyId = CurrentContext.requireCompany();
        ProcurementViews.GoodsReceipt current = lock(companyId, id, ifMatch, DocumentStatus.Action.EDIT);
        MergePatch patch = MergePatch.of(document, PATCHABLE);
        var date = patch.date("receiptDate", true);
        var note = patch.text("supplierDeliveryNote", false, 100);
        var notes = patch.text("notes", false, 2000);
        patch.throwIfInvalid();
        UUID actor = context.actor();
        if (document.has("lines")) {
            ProcurementViews.PurchaseOrder order =
                    orders.find(companyId, current.purchaseOrderId()).orElseThrow();
            List<ReceiptRepository.NewLine> lines = lines(order, readLines(document.get("lines")));
            receipts.deleteLines(companyId, id);
            receipts.insertLines(companyId, id, lines, actor);
        }
        if (!receipts.updateDraft(
                companyId,
                id,
                current.version(),
                actor,
                date.orElse(current.receiptDate()),
                note.orElse(current.supplierDeliveryNote()),
                notes.orElse(current.notes()))) {
            throw new ApiException(PlatformErrorCode.VERSION_CONFLICT, "The receipt was modified concurrently.");
        }
        audit.record(AuditEvent.builder("UPDATE", "procurement")
                .entity("goods_receipt", id, null)
                .change("receiptDate", current.receiptDate(), date.orElse(current.receiptDate()))
                .change(
                        "supplierDeliveryNote",
                        current.supplierDeliveryNote(),
                        note.orElse(current.supplierDeliveryNote()))
                .change("notes", current.notes(), notes.orElse(current.notes()))
                .detail("linesReplaced", document.has("lines"))
                .build());
        return get(id);
    }

    @Transactional
    public void delete(UUID id, @Nullable String ifMatch) {
        UUID companyId = CurrentContext.requireCompany();
        ProcurementViews.GoodsReceipt current = lock(companyId, id, ifMatch, DocumentStatus.Action.DELETE);
        if (!receipts.delete(companyId, id, current.version())) {
            throw new ApiException(PlatformErrorCode.VERSION_CONFLICT, "The receipt was modified concurrently.");
        }
        audit.record(AuditEvent.builder("DELETE", "procurement")
                .entity("goods_receipt", id, null)
                .build());
    }

    @Transactional
    public ProcurementViews.GoodsReceiptDetail cancel(UUID id, @Nullable String ifMatch) {
        UUID companyId = CurrentContext.requireCompany();
        ProcurementViews.GoodsReceipt current = lock(companyId, id, ifMatch, DocumentStatus.Action.CANCEL);
        if (!receipts.markCancelled(companyId, id, current.version(), context.actor())) {
            throw new ApiException(PlatformErrorCode.VERSION_CONFLICT, "The receipt was modified concurrently.");
        }
        audit.record(AuditEvent.builder("STATE_CHANGE", "procurement")
                .entity("goods_receipt", id, null)
                .transition("DRAFT", "CANCELLED")
                .build());
        return get(id);
    }

    /**
     * Posts the receipt: checks the open quantities under the order lock, receives the stock in
     * Inventory, updates the order's received quantities and status, numbers the receipt.
     */
    @Transactional
    public ProcurementViews.GoodsReceiptDetail post(UUID id, @Nullable String ifMatch) {
        UUID companyId = CurrentContext.requireCompany();
        ProcurementViews.GoodsReceipt receipt = lock(companyId, id, ifMatch, DocumentStatus.Action.POST);
        ProcurementViews.PurchaseOrder order = orderService.lockForFulfilment(companyId, receipt.purchaseOrderId());
        requireReceivable(order);
        List<ProcurementViews.GoodsReceiptLine> lines = receipts.lines(companyId, id);
        Map<UUID, ProcurementViews.PurchaseOrderLine> orderLines = orders.lines(companyId, order.id()).stream()
                .collect(Collectors.toMap(ProcurementViews.PurchaseOrderLine::id, Function.identity()));
        checkOpenQuantities(lines, orderLines);

        BigDecimal rate = context.exchangeRate(order.currencyCode(), receipt.receiptDate());
        Map<UUID, BigDecimal> costBase = new HashMap<>();
        List<InLine> inLines = new ArrayList<>();
        for (ProcurementViews.GoodsReceiptLine line : lines) {
            BigDecimal unitCostBase = line.unitCostDoc().multiply(rate).setScale(6, RoundingMode.HALF_UP);
            costBase.put(line.id(), unitCostBase);
            UUID baseUom = inventory.variantInfo(line.variantId()).orElseThrow().baseUomId();
            inLines.add(new InLine(
                    line.variantId(), line.quantityBase(), baseUom, line.locationId(), unitCostBase, line.id()));
        }
        PostedMovement movement = inventory.receive(new StockInRequest(
                new SourceRef(SOURCE_MODULE, SOURCE_TYPE, id, null),
                receipt.receiptDate(),
                receipt.warehouseId(),
                receipt.supplierId(),
                inLines,
                "Goods receipt for " + order.number()));

        Map<UUID, PostedLine> posted = new HashMap<>();
        movement.lines().forEach(l -> posted.put(Objects.requireNonNull(l.sourceLineId()), l));
        Map<UUID, BigDecimal> receivedNow = new LinkedHashMap<>();
        List<GoodsReceiptPosted.Line> eventLines = new ArrayList<>();
        for (ProcurementViews.GoodsReceiptLine line : lines) {
            PostedLine stock = posted.get(line.id());
            receipts.setLinePosting(
                    companyId, line.id(), stock.locationId(), costBase.get(line.id()), stock.valueBase());
            receivedNow.merge(line.purchaseOrderLineId(), line.quantityBase(), BigDecimal::add);
            eventLines.add(new GoodsReceiptPosted.Line(
                    line.id(),
                    line.purchaseOrderLineId(),
                    line.variantId(),
                    line.quantityBase(),
                    line.unitCostDoc(),
                    stock.valueBase()));
        }
        receivedNow.forEach((lineId, quantity) -> {
            ProcurementViews.PurchaseOrderLine orderLine = orderLines.get(lineId);
            orders.updateLineCounters(
                    companyId,
                    lineId,
                    orderLine.receivedQuantityBase().add(quantity),
                    orderLine.returnedQuantityBase(),
                    orderLine.billedQuantityBase());
        });
        orderService.receiptProgress(companyId, order.id());

        CompanyProfile profile = context.profile();
        String number = numbering.next(
                companyId,
                ProcurementConfiguration.GOODS_RECEIPT,
                FiscalYears.label(receipt.receiptDate(), profile.fiscalYearStartMonth()));
        if (!receipts.markPosted(
                companyId, id, receipt.version(), context.actor(), number, rate, movement.movementId())) {
            throw new ApiException(PlatformErrorCode.VERSION_CONFLICT, "The receipt was modified concurrently.");
        }
        audit.record(AuditEvent.builder("POST", "procurement")
                .entity("goods_receipt", id, number)
                .transition("DRAFT", "POSTED")
                .detail("purchaseOrderId", order.id())
                .detail("stockMovementId", movement.movementId())
                .detail("stockMovementNumber", movement.number())
                .detail("exchangeRate", rate.toPlainString())
                .build());
        events.publishEvent(new GoodsReceiptPosted(
                DomainEvents.metadata(GoodsReceiptPosted.TYPE, GoodsReceiptPosted.SCHEMA_VERSION, companyId, clock),
                id,
                number,
                order.id(),
                receipt.supplierId(),
                movement.movementId(),
                receipt.receiptDate(),
                order.currencyCode(),
                rate,
                eventLines));
        return get(id);
    }

    // ------------------------------------------------------------------------------ helpers

    /** PRC-1: per order line, this receipt ≤ (ordered − received + returned) × (1 + tolerance). */
    private void checkOpenQuantities(
            List<ProcurementViews.GoodsReceiptLine> lines, Map<UUID, ProcurementViews.PurchaseOrderLine> orderLines) {
        BigDecimal tolerance = inventory.overReceiptTolerancePercent();
        Map<UUID, BigDecimal> requested = new LinkedHashMap<>();
        Map<UUID, Integer> firstIndex = new HashMap<>();
        for (int i = 0; i < lines.size(); i++) {
            requested.merge(lines.get(i).purchaseOrderLineId(), lines.get(i).quantityBase(), BigDecimal::add);
            firstIndex.putIfAbsent(lines.get(i).purchaseOrderLineId(), i);
        }
        List<FieldViolation> violations = new ArrayList<>();
        requested.forEach((lineId, quantity) -> {
            ProcurementViews.PurchaseOrderLine orderLine = orderLines.get(lineId);
            BigDecimal open = orderLine
                    .quantityBase()
                    .subtract(orderLine.netReceivedBase())
                    .max(BigDecimal.ZERO);
            BigDecimal allowed = ThreeWayMatch.withTolerance(open, tolerance);
            if (quantity.compareTo(allowed) > 0) {
                violations.add(new FieldViolation(
                        "/lines/" + firstIndex.get(lineId) + "/quantity",
                        null,
                        ProcurementErrorCode.QUANTITY_EXCEEDS_REMAINING.code(),
                        "Receiving " + quantity.stripTrailingZeros().toPlainString() + " of order line "
                                + orderLine.lineNo() + ", open "
                                + open.stripTrailingZeros().toPlainString(),
                        Map.of(
                                "purchaseOrderLineId", lineId.toString(),
                                "requested", quantity.stripTrailingZeros().toPlainString(),
                                "open", open.stripTrailingZeros().toPlainString(),
                                "allowed", allowed.stripTrailingZeros().toPlainString())));
            }
        });
        if (!violations.isEmpty()) {
            throw new ApiException(
                    ProcurementErrorCode.QUANTITY_EXCEEDS_REMAINING,
                    "The receipt exceeds the quantities still open on the order.",
                    violations);
        }
    }

    private List<ReceiptRepository.NewLine> lines(
            ProcurementViews.PurchaseOrder order, @Nullable List<ProcurementCommands.ReceiptLine> requested) {
        UUID companyId = order.companyId();
        List<ProcurementViews.PurchaseOrderLine> orderLines = orders.lines(companyId, order.id());
        Map<UUID, ProcurementViews.PurchaseOrderLine> byId = orderLines.stream()
                .collect(Collectors.toMap(ProcurementViews.PurchaseOrderLine::id, Function.identity()));
        List<ProcurementCommands.ReceiptLine> wanted = requested;
        if (wanted == null) {
            wanted = new ArrayList<>();
            for (ProcurementViews.PurchaseOrderLine line : orderLines) {
                BigDecimal open = line.quantityBase().subtract(line.netReceivedBase());
                if (line.stockable() && open.signum() > 0) {
                    boolean whole = line.netReceivedBase().signum() == 0;
                    wanted.add(new ProcurementCommands.ReceiptLine(
                            line.id(),
                            whole ? line.quantity() : open,
                            whole
                                    ? line.uomId()
                                    : inventory
                                            .variantInfo(line.variantId())
                                            .orElseThrow()
                                            .baseUomId(),
                            null));
                }
            }
            if (wanted.isEmpty()) {
                throw new ApiException(PlatformErrorCode.INVALID_STATE, "Nothing is left to receive on this order.");
            }
        }
        List<FieldViolation> violations = new ArrayList<>();
        if (wanted.isEmpty() || wanted.size() > DocumentPricing.MAX_LINES) {
            violations.add(FieldViolation.atPointer("/lines", "SIZE", "must contain between 1 and 500 lines"));
        }
        Set<UUID> seen = new HashSet<>();
        List<ReceiptRepository.NewLine> lines = new ArrayList<>();
        for (int i = 0; i < wanted.size(); i++) {
            ProcurementCommands.ReceiptLine line = wanted.get(i);
            String at = "/lines/" + i;
            ProcurementViews.PurchaseOrderLine orderLine = byId.get(line.purchaseOrderLineId());
            if (orderLine == null) {
                violations.add(FieldViolation.atPointer(
                        at + "/purchaseOrderLineId", "UNKNOWN_LINE", "is not a line of the purchase order"));
                continue;
            }
            if (!orderLine.stockable()) {
                violations.add(FieldViolation.atPointer(
                        at + "/purchaseOrderLineId", "NOT_STOCKABLE", "is not a stockable product; bill it directly"));
                continue;
            }
            if (!seen.add(orderLine.id())) {
                violations.add(FieldViolation.atPointer(
                        at + "/purchaseOrderLineId", "DUPLICATE_LINE", "appears twice in the receipt"));
                continue;
            }
            UUID uom = line.uomId() != null ? line.uomId() : orderLine.uomId();
            BigDecimal base = pricing.quantityBase(i, orderLine.variantId(), line.quantity(), uom, violations);
            if (base != null) {
                lines.add(new ReceiptRepository.NewLine(
                        i + 1,
                        orderLine.id(),
                        orderLine.variantId(),
                        line.locationId(),
                        line.quantity(),
                        uom,
                        base,
                        DocumentPricing.netUnitPrice(orderLine.netAmount(), orderLine.quantityBase())));
            }
        }
        if (!violations.isEmpty()) {
            throw ApiException.validationFailed("The receipt is invalid.", violations);
        }
        return lines;
    }

    private static void requireReceivable(ProcurementViews.PurchaseOrder order) {
        if (!PurchaseOrderStatus.valueOf(order.status()).allows(PurchaseOrderStatus.Action.RECEIVE)) {
            throw new ApiException(
                    PlatformErrorCode.INVALID_STATE,
                    "A " + order.status() + " purchase order cannot receive goods; it must be approved and open.");
        }
    }

    private ProcurementViews.GoodsReceipt visible(ProcurementViews.GoodsReceipt receipt) {
        if (!context.canSeeBranch(receipt.branchId())) {
            throw ApiException.notFound();
        }
        return receipt;
    }

    private ProcurementViews.GoodsReceipt lock(
            UUID companyId, UUID id, @Nullable String ifMatch, DocumentStatus.Action action) {
        ProcurementViews.GoodsReceipt current =
                visible(receipts.lock(companyId, id).orElseThrow(ApiException::notFound));
        EntityTags.requireMatch(ifMatch, current.version());
        if (!DocumentStatus.valueOf(current.status()).allows(action)) {
            throw new ApiException(
                    PlatformErrorCode.INVALID_STATE,
                    "A " + current.status() + " receipt does not allow "
                            + action.name().toLowerCase(java.util.Locale.ROOT) + ".");
        }
        return current;
    }

    static List<ProcurementCommands.ReceiptLine> readLines(JsonNode array) {
        return MergePatchLines.read(array, LINE_MEMBERS).stream()
                .map(v -> new ProcurementCommands.ReceiptLine(
                        v.uuid("purchaseOrderLineId"), v.decimal("quantity"), v.uuid("uomId"), v.uuid("locationId")))
                .toList();
    }
}
