package com.erp.sales.application;

import com.erp.inventory.api.InventoryFacade;
import com.erp.inventory.api.InventoryFacade.OutLine;
import com.erp.inventory.api.InventoryFacade.PostedLine;
import com.erp.inventory.api.InventoryFacade.PostedMovement;
import com.erp.inventory.api.InventoryFacade.SourceRef;
import com.erp.inventory.api.InventoryFacade.StockOutRequest;
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
import com.erp.platform.web.MergePatchLines;
import com.erp.platform.web.PlatformErrorCode;
import com.erp.platform.web.paging.ListQuery;
import com.erp.platform.web.paging.PageResponse;
import com.erp.sales.domain.DocumentStatus;
import com.erp.sales.domain.SalesOrderStatus;
import com.erp.sales.persistence.DeliveryRepository;
import com.erp.sales.persistence.SalesOrderRepository;
import java.math.BigDecimal;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * Deliveries (PRODUCT_SPEC.md §9): drafts are made from a confirmed order's stockable lines; posting
 * issues the stock through {@link InventoryFacade#issue} ({@code SALES_ISSUE}), consuming the line's
 * reservation, and records the unit cost of the goods (SAL-4), never more than ordered − delivered.
 * Posting locks the delivery, then the order (which serializes all fulfilment of the order); a
 * delivery is posted once (its state, Inventory's one-movement-per-source rule and the
 * {@code Idempotency-Key}). A posted delivery is corrected by a sales return.
 */
@Service
public class DeliveryService {

    static final String SOURCE_TYPE = "DELIVERY";
    static final Set<String> PATCHABLE = Set.of("deliveryDate", "carrier", "trackingNumber", "notes", "lines");
    static final List<MergePatchLines.Member> LINE_MEMBERS = List.of(
            MergePatchLines.Member.uuid("salesOrderLineId", true),
            MergePatchLines.Member.decimal("quantity", true),
            MergePatchLines.Member.uuid("uomId", false),
            MergePatchLines.Member.uuid("locationId", false));

    private final DeliveryRepository deliveries;
    private final SalesOrderRepository orders;
    private final SalesOrderService orderService;
    private final SalesPricing pricing;
    private final InventoryFacade inventory;
    private final SalesContext context;
    private final DocumentNumberService numbering;
    private final AuditPort audit;

    DeliveryService(
            DeliveryRepository deliveries,
            SalesOrderRepository orders,
            SalesOrderService orderService,
            SalesPricing pricing,
            InventoryFacade inventory,
            SalesContext context,
            DocumentNumberService numbering,
            AuditPort audit) {
        this.deliveries = deliveries;
        this.orders = orders;
        this.orderService = orderService;
        this.pricing = pricing;
        this.inventory = inventory;
        this.context = context;
        this.numbering = numbering;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public PageResponse<SalesViews.Delivery> list(ListQuery query) {
        return deliveries.list(
                CurrentContext.requireCompany(), CurrentContext.require().branchScope(), query);
    }

    @Transactional(readOnly = true)
    public SalesViews.DeliveryDetail get(UUID id) {
        UUID companyId = CurrentContext.requireCompany();
        SalesViews.Delivery delivery = visible(deliveries.find(companyId, id).orElseThrow(ApiException::notFound));
        return new SalesViews.DeliveryDetail(delivery, deliveries.lines(companyId, id));
    }

    /** A draft delivery of a confirmed order; without lines, everything still open on its stockable lines. */
    @Transactional
    public SalesViews.DeliveryDetail create(SalesCommands.Delivery command) {
        UUID companyId = CurrentContext.requireCompany();
        SalesViews.SalesOrder order = orders.find(companyId, command.salesOrderId())
                .filter(o -> context.canSeeBranch(o.branchId()))
                .orElseThrow(() -> ApiException.validationFailed(
                        "The sales order is unknown.",
                        List.of(FieldViolation.atPointer(
                                "/salesOrderId", "UNKNOWN_SALES_ORDER", "is not a sales order you can use"))));
        requireDeliverable(order);
        CompanyProfile profile = context.profile();
        LocalDate date = command.deliveryDate() != null ? command.deliveryDate() : context.today(profile);
        List<DeliveryRepository.NewLine> lines = lines(order, command.lines());
        UUID actor = context.actor();
        UUID id = deliveries.insert(
                companyId,
                order,
                new DeliveryRepository.Header(date, command.carrier(), command.trackingNumber(), command.notes()),
                actor);
        deliveries.insertLines(companyId, id, lines, actor);
        audit.record(AuditEvent.builder("CREATE", "sales")
                .entity("delivery", id, null)
                .detail("salesOrderId", order.id())
                .detail("lines", lines.size())
                .build());
        return get(id);
    }

    @Transactional
    public SalesViews.DeliveryDetail patch(UUID id, @Nullable String ifMatch, JsonNode document) {
        UUID companyId = CurrentContext.requireCompany();
        SalesViews.Delivery current = lock(companyId, id, ifMatch, DocumentStatus.Action.EDIT);
        MergePatch patch = MergePatch.of(document, PATCHABLE);
        var date = patch.date("deliveryDate", true);
        var carrier = patch.text("carrier", false, 100);
        var tracking = patch.text("trackingNumber", false, 100);
        var notes = patch.text("notes", false, 2000);
        patch.throwIfInvalid();
        UUID actor = context.actor();
        if (document.has("lines")) {
            SalesViews.SalesOrder order =
                    orders.find(companyId, current.salesOrderId()).orElseThrow();
            List<DeliveryRepository.NewLine> lines = lines(order, readLines(document.get("lines")));
            deliveries.deleteLines(companyId, id);
            deliveries.insertLines(companyId, id, lines, actor);
        }
        DeliveryRepository.Header next = new DeliveryRepository.Header(
                Objects.requireNonNull(date.orElse(current.deliveryDate())),
                carrier.orElse(current.carrier()),
                tracking.orElse(current.trackingNumber()),
                notes.orElse(current.notes()));
        if (!deliveries.updateDraft(companyId, id, current.version(), actor, next)) {
            throw new ApiException(PlatformErrorCode.VERSION_CONFLICT, "The delivery was modified concurrently.");
        }
        audit.record(AuditEvent.builder("UPDATE", "sales")
                .entity("delivery", id, null)
                .change("deliveryDate", current.deliveryDate(), next.deliveryDate())
                .change("carrier", current.carrier(), next.carrier())
                .change("trackingNumber", current.trackingNumber(), next.trackingNumber())
                .change("notes", current.notes(), next.notes())
                .detail("linesReplaced", document.has("lines"))
                .build());
        return get(id);
    }

    @Transactional
    public void delete(UUID id, @Nullable String ifMatch) {
        UUID companyId = CurrentContext.requireCompany();
        SalesViews.Delivery current = lock(companyId, id, ifMatch, DocumentStatus.Action.DELETE);
        if (!deliveries.delete(companyId, id, current.version())) {
            throw new ApiException(PlatformErrorCode.VERSION_CONFLICT, "The delivery was modified concurrently.");
        }
        audit.record(AuditEvent.builder("DELETE", "sales")
                .entity("delivery", id, null)
                .build());
    }

    @Transactional
    public SalesViews.DeliveryDetail cancel(UUID id, @Nullable String ifMatch) {
        UUID companyId = CurrentContext.requireCompany();
        SalesViews.Delivery current = lock(companyId, id, ifMatch, DocumentStatus.Action.CANCEL);
        if (!deliveries.markCancelled(companyId, id, current.version(), context.actor())) {
            throw new ApiException(PlatformErrorCode.VERSION_CONFLICT, "The delivery was modified concurrently.");
        }
        audit.record(AuditEvent.builder("STATE_CHANGE", "sales")
                .entity("delivery", id, null)
                .transition("DRAFT", "CANCELLED")
                .build());
        return get(id);
    }

    /**
     * Posts the delivery: checks the open quantities under the order lock (SAL-4), issues the stock in
     * Inventory consuming the lines' reservations, records the unit costs, updates the order's
     * delivered and reserved quantities and status, numbers the delivery.
     */
    @Transactional
    public SalesViews.DeliveryDetail post(UUID id, @Nullable String ifMatch) {
        UUID companyId = CurrentContext.requireCompany();
        SalesViews.Delivery delivery = lock(companyId, id, ifMatch, DocumentStatus.Action.POST);
        SalesViews.SalesOrder order = orderService.lockForFulfilment(companyId, delivery.salesOrderId());
        requireDeliverable(order);
        List<SalesViews.DeliveryLine> lines = deliveries.lines(companyId, id);
        Map<UUID, SalesViews.SalesOrderLine> orderLines = orders.lines(companyId, order.id()).stream()
                .collect(Collectors.toMap(SalesViews.SalesOrderLine::id, Function.identity()));
        checkOpenQuantities(lines, orderLines);

        List<OutLine> outLines = new ArrayList<>();
        for (SalesViews.DeliveryLine line : lines) {
            SalesViews.SalesOrderLine orderLine = orderLines.get(line.salesOrderLineId());
            UUID baseUom = inventory.variantInfo(line.variantId()).orElseThrow().baseUomId();
            outLines.add(new OutLine(
                    line.variantId(),
                    line.quantityBase(),
                    baseUom,
                    line.locationId(),
                    orderLine.reservedQuantityBase().signum() > 0 ? orderLine.reservationId() : null,
                    null,
                    line.id()));
        }
        PostedMovement movement = inventory.issue(new StockOutRequest(
                new SourceRef(SalesOrderService.SOURCE_MODULE, SOURCE_TYPE, id, null),
                delivery.deliveryDate(),
                delivery.warehouseId(),
                delivery.customerId(),
                outLines,
                "Delivery for " + order.number()));

        Map<UUID, PostedLine> posted = new HashMap<>();
        movement.lines().forEach(l -> posted.put(Objects.requireNonNull(l.sourceLineId()), l));
        Map<UUID, BigDecimal> deliveredNow = new LinkedHashMap<>();
        BigDecimal value = BigDecimal.ZERO;
        for (SalesViews.DeliveryLine line : lines) {
            PostedLine stock = posted.get(line.id());
            deliveries.setLinePosting(
                    companyId, line.id(), stock.locationId(), stock.unitCostBase(), stock.valueBase());
            deliveredNow.merge(line.salesOrderLineId(), line.quantityBase(), BigDecimal::add);
            value = value.add(stock.valueBase());
        }
        deliveredNow.forEach((lineId, quantity) -> {
            SalesViews.SalesOrderLine orderLine = orderLines.get(lineId);
            BigDecimal consumed = orderLine.reservedQuantityBase().min(quantity);
            orders.updateLine(
                    companyId,
                    lineId,
                    orderLine.reservationId(),
                    orderLine.reservedQuantityBase().subtract(consumed),
                    orderLine.deliveredQuantityBase().add(quantity),
                    orderLine.returnedQuantityBase(),
                    orderLine.invoicedQuantityBase());
        });
        orderService.deliveryProgress(companyId, order.id());

        CompanyProfile profile = context.profile();
        String number = numbering.next(
                companyId,
                SalesConfiguration.DELIVERY,
                FiscalYears.label(delivery.deliveryDate(), profile.fiscalYearStartMonth()));
        if (!deliveries.markPosted(companyId, id, delivery.version(), context.actor(), number, movement.movementId())) {
            throw new ApiException(PlatformErrorCode.VERSION_CONFLICT, "The delivery was modified concurrently.");
        }
        audit.record(AuditEvent.builder("POST", "sales")
                .entity("delivery", id, number)
                .transition("DRAFT", "POSTED")
                .detail("salesOrderId", order.id())
                .detail("stockMovementId", movement.movementId())
                .detail("stockMovementNumber", movement.number())
                .detail("costValueBase", value.toPlainString())
                .build());
        return get(id);
    }

    // ------------------------------------------------------------------------------ helpers

    /** SAL-4: per order line, this delivery ≤ ordered − delivered. */
    private static void checkOpenQuantities(
            List<SalesViews.DeliveryLine> lines, Map<UUID, SalesViews.SalesOrderLine> orderLines) {
        Map<UUID, BigDecimal> requested = new LinkedHashMap<>();
        Map<UUID, Integer> firstIndex = new HashMap<>();
        for (int i = 0; i < lines.size(); i++) {
            requested.merge(lines.get(i).salesOrderLineId(), lines.get(i).quantityBase(), BigDecimal::add);
            firstIndex.putIfAbsent(lines.get(i).salesOrderLineId(), i);
        }
        List<FieldViolation> violations = new ArrayList<>();
        requested.forEach((lineId, quantity) -> {
            SalesViews.SalesOrderLine orderLine = orderLines.get(lineId);
            BigDecimal open = orderLine.openToDeliverBase().max(BigDecimal.ZERO);
            if (quantity.compareTo(open) > 0) {
                violations.add(new FieldViolation(
                        "/lines/" + firstIndex.get(lineId) + "/quantity",
                        null,
                        SalesErrorCode.QUANTITY_EXCEEDS_REMAINING.code(),
                        "Delivering " + quantity.stripTrailingZeros().toPlainString() + " of order line "
                                + orderLine.line().lineNo() + ", open "
                                + open.stripTrailingZeros().toPlainString(),
                        Map.of(
                                "salesOrderLineId", lineId.toString(),
                                "requested", quantity.stripTrailingZeros().toPlainString(),
                                "open", open.stripTrailingZeros().toPlainString())));
            }
        });
        if (!violations.isEmpty()) {
            throw new ApiException(
                    SalesErrorCode.QUANTITY_EXCEEDS_REMAINING,
                    "The delivery exceeds the quantities still open on the order.",
                    violations);
        }
    }

    private List<DeliveryRepository.NewLine> lines(
            SalesViews.SalesOrder order, @Nullable List<SalesCommands.DeliveryLine> requested) {
        List<SalesViews.SalesOrderLine> orderLines = orders.lines(order.companyId(), order.id());
        Map<UUID, SalesViews.SalesOrderLine> byId =
                orderLines.stream().collect(Collectors.toMap(SalesViews.SalesOrderLine::id, Function.identity()));
        List<SalesCommands.DeliveryLine> wanted = requested;
        if (wanted == null) {
            wanted = new ArrayList<>();
            for (SalesViews.SalesOrderLine line : orderLines) {
                BigDecimal open = line.openToDeliverBase();
                if (line.stockable() && open.signum() > 0) {
                    boolean whole = line.deliveredQuantityBase().signum() == 0;
                    wanted.add(new SalesCommands.DeliveryLine(
                            line.id(),
                            whole ? line.line().quantity() : open,
                            whole
                                    ? line.line().uomId()
                                    : inventory
                                            .variantInfo(line.line().variantId())
                                            .orElseThrow()
                                            .baseUomId(),
                            null));
                }
            }
            if (wanted.isEmpty()) {
                throw new ApiException(PlatformErrorCode.INVALID_STATE, "Nothing is left to deliver on this order.");
            }
        }
        List<FieldViolation> violations = new ArrayList<>();
        if (wanted.isEmpty() || wanted.size() > SalesPricing.MAX_LINES) {
            violations.add(FieldViolation.atPointer("/lines", "SIZE", "must contain between 1 and 500 lines"));
        }
        Set<UUID> seen = new HashSet<>();
        List<DeliveryRepository.NewLine> lines = new ArrayList<>();
        for (int i = 0; i < wanted.size(); i++) {
            SalesCommands.DeliveryLine line = wanted.get(i);
            String at = "/lines/" + i;
            SalesViews.SalesOrderLine orderLine = byId.get(line.salesOrderLineId());
            if (orderLine == null) {
                violations.add(FieldViolation.atPointer(
                        at + "/salesOrderLineId", "UNKNOWN_LINE", "is not a line of the sales order"));
                continue;
            }
            if (!orderLine.stockable()) {
                violations.add(FieldViolation.atPointer(
                        at + "/salesOrderLineId", "NOT_STOCKABLE", "is not a stockable product; invoice it directly"));
                continue;
            }
            if (!seen.add(orderLine.id())) {
                violations.add(FieldViolation.atPointer(
                        at + "/salesOrderLineId", "DUPLICATE_LINE", "appears twice in the delivery"));
                continue;
            }
            UUID uom = line.uomId() != null ? line.uomId() : orderLine.line().uomId();
            BigDecimal base = pricing.quantityBase(at, orderLine.line().variantId(), line.quantity(), uom, violations);
            if (base != null) {
                lines.add(new DeliveryRepository.NewLine(
                        i + 1,
                        orderLine.id(),
                        orderLine.line().variantId(),
                        line.locationId(),
                        line.quantity(),
                        uom,
                        base));
            }
        }
        if (!violations.isEmpty()) {
            throw ApiException.validationFailed("The delivery is invalid.", violations);
        }
        return lines;
    }

    private static void requireDeliverable(SalesViews.SalesOrder order) {
        if (!SalesOrderStatus.valueOf(order.status()).allows(SalesOrderStatus.Action.DELIVER)) {
            throw new ApiException(
                    PlatformErrorCode.INVALID_STATE,
                    "A " + order.status() + " sales order cannot be delivered; it must be confirmed and open.");
        }
    }

    SalesViews.Delivery visible(SalesViews.Delivery delivery) {
        if (!context.canSeeBranch(delivery.branchId())) {
            throw ApiException.notFound();
        }
        return delivery;
    }

    private SalesViews.Delivery lock(UUID companyId, UUID id, @Nullable String ifMatch, DocumentStatus.Action action) {
        SalesViews.Delivery current = visible(deliveries.lock(companyId, id).orElseThrow(ApiException::notFound));
        EntityTags.requireMatch(ifMatch, current.version());
        if (!DocumentStatus.valueOf(current.status()).allows(action)) {
            throw new ApiException(
                    PlatformErrorCode.INVALID_STATE,
                    "A " + current.status() + " delivery does not allow "
                            + action.name().toLowerCase(Locale.ROOT) + ".");
        }
        return current;
    }

    static List<SalesCommands.DeliveryLine> readLines(JsonNode array) {
        return MergePatchLines.read(array, LINE_MEMBERS).stream()
                .map(v -> new SalesCommands.DeliveryLine(
                        v.uuid("salesOrderLineId"), v.decimal("quantity"), v.uuid("uomId"), v.uuid("locationId")))
                .toList();
    }
}
