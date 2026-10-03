package com.erp.sales.application;

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
import com.erp.platform.numbering.DocumentNumberService;
import com.erp.platform.numbering.FiscalYears;
import com.erp.platform.web.ApiException;
import com.erp.platform.web.EntityTags;
import com.erp.platform.web.FieldViolation;
import com.erp.platform.web.PlatformErrorCode;
import com.erp.platform.web.paging.ListQuery;
import com.erp.platform.web.paging.PageResponse;
import com.erp.sales.domain.ReturnStatus;
import com.erp.sales.persistence.DeliveryRepository;
import com.erp.sales.persistence.SalesOrderRepository;
import com.erp.sales.persistence.SalesReturnRepository;
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

/**
 * Sales returns (PRODUCT_SPEC.md §9, SAL-7): goods of a posted delivery coming back. Receiving puts
 * the stock back through {@link InventoryFacade#returnFromCustomer} ({@code SALES_RETURN}) at the
 * delivery's unit cost, never more than delivered − already returned per delivery line. Receiving
 * locks the return, then the order (which serializes all fulfilment of the order). The credit for
 * the returned goods is a credit note referencing the return.
 */
@Service
public class SalesReturnService {

    static final String SOURCE_TYPE = "SALES_RETURN";

    private final SalesReturnRepository returns;
    private final DeliveryRepository deliveries;
    private final SalesOrderRepository orders;
    private final SalesOrderService orderService;
    private final SalesPricing pricing;
    private final InventoryFacade inventory;
    private final SalesContext context;
    private final DocumentNumberService numbering;
    private final AuditPort audit;

    SalesReturnService(
            SalesReturnRepository returns,
            DeliveryRepository deliveries,
            SalesOrderRepository orders,
            SalesOrderService orderService,
            SalesPricing pricing,
            InventoryFacade inventory,
            SalesContext context,
            DocumentNumberService numbering,
            AuditPort audit) {
        this.returns = returns;
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
    public PageResponse<SalesViews.SalesReturn> list(ListQuery query) {
        return returns.list(
                CurrentContext.requireCompany(), CurrentContext.require().branchScope(), query);
    }

    @Transactional(readOnly = true)
    public SalesViews.SalesReturnDetail get(UUID id) {
        UUID companyId = CurrentContext.requireCompany();
        SalesViews.SalesReturn found = visible(returns.find(companyId, id).orElseThrow(ApiException::notFound));
        return new SalesViews.SalesReturnDetail(found, returns.lines(companyId, id));
    }

    /** A draft return of a posted delivery's lines. */
    @Transactional
    public SalesViews.SalesReturnDetail create(SalesCommands.SalesReturn command) {
        UUID companyId = CurrentContext.requireCompany();
        SalesViews.Delivery delivery = deliveries
                .find(companyId, command.deliveryId())
                .filter(d -> context.canSeeBranch(d.branchId()))
                .orElseThrow(() -> ApiException.validationFailed(
                        "The delivery is unknown.",
                        List.of(FieldViolation.atPointer(
                                "/deliveryId", "UNKNOWN_DELIVERY", "is not a delivery you can use"))));
        if (!"POSTED".equals(delivery.status())) {
            throw new ApiException(PlatformErrorCode.INVALID_STATE, "Only goods of a posted delivery can be returned.");
        }
        List<SalesViews.DeliveryLine> deliveryLines = deliveries.lines(companyId, delivery.id());
        Map<UUID, SalesViews.DeliveryLine> byId =
                deliveryLines.stream().collect(Collectors.toMap(SalesViews.DeliveryLine::id, Function.identity()));
        List<FieldViolation> violations = new ArrayList<>();
        if (command.lines().isEmpty() || command.lines().size() > SalesPricing.MAX_LINES) {
            violations.add(FieldViolation.atPointer("/lines", "SIZE", "must contain between 1 and 500 lines"));
        }
        if (command.reason().isBlank()) {
            violations.add(FieldViolation.atPointer("/reason", "NOT_BLANK", "must not be blank"));
        }
        Set<UUID> seen = new HashSet<>();
        List<SalesReturnRepository.NewLine> lines = new ArrayList<>();
        for (int i = 0; i < command.lines().size(); i++) {
            SalesCommands.ReturnLine line = command.lines().get(i);
            String at = "/lines/" + i;
            SalesViews.DeliveryLine deliveryLine = byId.get(line.deliveryLineId());
            if (deliveryLine == null) {
                violations.add(FieldViolation.atPointer(
                        at + "/deliveryLineId", "UNKNOWN_LINE", "is not a line of the delivery"));
                continue;
            }
            if (!seen.add(deliveryLine.id())) {
                violations.add(FieldViolation.atPointer(
                        at + "/deliveryLineId", "DUPLICATE_LINE", "appears twice in the return"));
                continue;
            }
            UUID uom = line.uomId() != null ? line.uomId() : deliveryLine.uomId();
            BigDecimal base = pricing.quantityBase(at, deliveryLine.variantId(), line.quantity(), uom, violations);
            if (base != null) {
                lines.add(new SalesReturnRepository.NewLine(
                        i + 1,
                        deliveryLine.id(),
                        deliveryLine.variantId(),
                        deliveryLine.locationId(),
                        line.quantity(),
                        uom,
                        base,
                        Objects.requireNonNull(deliveryLine.unitCostBase())));
            }
        }
        if (!violations.isEmpty()) {
            throw ApiException.validationFailed("The return is invalid.", violations);
        }
        checkReturnable(lines, byId);
        CompanyProfile profile = context.profile();
        LocalDate date = command.returnDate() != null ? command.returnDate() : context.today(profile);
        UUID actor = context.actor();
        UUID id = returns.insert(companyId, delivery, date, command.reason().strip(), actor);
        returns.insertLines(companyId, id, lines, actor);
        audit.record(AuditEvent.builder("CREATE", "sales")
                .entity("sales_return", id, null)
                .detail("deliveryId", delivery.id())
                .detail("salesOrderId", delivery.salesOrderId())
                .detail("reason", command.reason().strip())
                .detail("lines", lines.size())
                .build());
        return get(id);
    }

    @Transactional
    public SalesViews.SalesReturnDetail cancel(UUID id, @Nullable String ifMatch) {
        UUID companyId = CurrentContext.requireCompany();
        SalesViews.SalesReturn current = lock(companyId, id, ifMatch, ReturnStatus.Action.CANCEL);
        if (!returns.markCancelled(companyId, id, current.version(), context.actor())) {
            throw new ApiException(PlatformErrorCode.VERSION_CONFLICT, "The return was modified concurrently.");
        }
        audit.record(AuditEvent.builder("STATE_CHANGE", "sales")
                .entity("sales_return", id, null)
                .transition("DRAFT", "CANCELLED")
                .build());
        return get(id);
    }

    /**
     * Receives the goods: checks the returnable quantities under the order lock (SAL-7), puts the
     * stock back in Inventory at the delivery's unit cost, updates the delivery's and the order's
     * returned quantities, numbers the return.
     */
    @Transactional
    public SalesViews.SalesReturnDetail receive(UUID id, @Nullable String ifMatch) {
        UUID companyId = CurrentContext.requireCompany();
        SalesViews.SalesReturn found = lock(companyId, id, ifMatch, ReturnStatus.Action.RECEIVE);
        SalesViews.SalesOrder order = orderService.lockForFulfilment(companyId, found.salesOrderId());
        List<SalesViews.SalesReturnLine> lines = returns.lines(companyId, id);
        Map<UUID, SalesViews.DeliveryLine> deliveryLines = deliveries.lines(companyId, found.deliveryId()).stream()
                .collect(Collectors.toMap(SalesViews.DeliveryLine::id, Function.identity()));
        checkReturnable(
                lines.stream()
                        .map(l -> new SalesReturnRepository.NewLine(
                                l.lineNo(),
                                l.deliveryLineId(),
                                l.variantId(),
                                l.locationId(),
                                l.quantity(),
                                l.uomId(),
                                l.quantityBase(),
                                l.unitCostBase()))
                        .toList(),
                deliveryLines);

        List<InLine> inLines = new ArrayList<>();
        for (SalesViews.SalesReturnLine line : lines) {
            UUID baseUom = inventory.variantInfo(line.variantId()).orElseThrow().baseUomId();
            inLines.add(new InLine(
                    line.variantId(), line.quantityBase(), baseUom, line.locationId(), line.unitCostBase(), line.id()));
        }
        PostedMovement movement = inventory.returnFromCustomer(new StockInRequest(
                new SourceRef(SalesOrderService.SOURCE_MODULE, SOURCE_TYPE, id, null),
                found.returnDate(),
                found.warehouseId(),
                found.customerId(),
                inLines,
                "Customer return for " + order.number()));

        Map<UUID, PostedLine> posted = new HashMap<>();
        movement.lines().forEach(l -> posted.put(Objects.requireNonNull(l.sourceLineId()), l));
        Map<UUID, BigDecimal> perOrderLine = new LinkedHashMap<>();
        for (SalesViews.SalesReturnLine line : lines) {
            PostedLine stock = posted.get(line.id());
            returns.setLinePosting(companyId, line.id(), stock.locationId(), stock.valueBase());
            SalesViews.DeliveryLine deliveryLine = deliveryLines.get(line.deliveryLineId());
            deliveries.updateReturned(
                    companyId,
                    deliveryLine.id(),
                    deliveryLine.returnedQuantityBase().add(line.quantityBase()));
            perOrderLine.merge(deliveryLine.salesOrderLineId(), line.quantityBase(), BigDecimal::add);
        }
        Map<UUID, SalesViews.SalesOrderLine> orderLines = orders.lines(companyId, order.id()).stream()
                .collect(Collectors.toMap(SalesViews.SalesOrderLine::id, Function.identity()));
        perOrderLine.forEach((lineId, quantity) -> {
            SalesViews.SalesOrderLine orderLine = orderLines.get(lineId);
            orders.updateLine(
                    companyId,
                    lineId,
                    orderLine.reservationId(),
                    orderLine.reservedQuantityBase(),
                    orderLine.deliveredQuantityBase(),
                    orderLine.returnedQuantityBase().add(quantity),
                    orderLine.invoicedQuantityBase());
        });
        orderService.invoicingProgress(companyId, order.id());

        CompanyProfile profile = context.profile();
        String number = numbering.next(
                companyId,
                SalesConfiguration.SALES_RETURN,
                FiscalYears.label(found.returnDate(), profile.fiscalYearStartMonth()));
        if (!returns.markReceived(companyId, id, found.version(), context.actor(), number, movement.movementId())) {
            throw new ApiException(PlatformErrorCode.VERSION_CONFLICT, "The return was modified concurrently.");
        }
        audit.record(AuditEvent.builder("RECEIVE", "sales")
                .entity("sales_return", id, number)
                .transition("DRAFT", "RECEIVED")
                .detail("deliveryId", found.deliveryId())
                .detail("salesOrderId", order.id())
                .detail("stockMovementId", movement.movementId())
                .detail("stockMovementNumber", movement.number())
                .build());
        return get(id);
    }

    // ------------------------------------------------------------------------------ helpers

    /** SAL-7: per delivery line, this return ≤ delivered − already returned. */
    private static void checkReturnable(
            List<SalesReturnRepository.NewLine> lines, Map<UUID, SalesViews.DeliveryLine> deliveryLines) {
        List<FieldViolation> violations = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            SalesReturnRepository.NewLine line = lines.get(i);
            SalesViews.DeliveryLine deliveryLine = deliveryLines.get(line.deliveryLineId());
            BigDecimal open = deliveryLine.quantityBase().subtract(deliveryLine.returnedQuantityBase());
            if (line.quantityBase().compareTo(open) > 0) {
                violations.add(new FieldViolation(
                        "/lines/" + i + "/quantity",
                        null,
                        SalesErrorCode.QUANTITY_EXCEEDS_REMAINING.code(),
                        "Returning " + line.quantityBase().stripTrailingZeros().toPlainString()
                                + " of delivery line " + deliveryLine.lineNo() + ", returnable "
                                + open.stripTrailingZeros().toPlainString(),
                        Map.of(
                                "deliveryLineId", deliveryLine.id().toString(),
                                "requested",
                                        line.quantityBase().stripTrailingZeros().toPlainString(),
                                "open", open.stripTrailingZeros().toPlainString())));
            }
        }
        if (!violations.isEmpty()) {
            throw new ApiException(
                    SalesErrorCode.QUANTITY_EXCEEDS_REMAINING,
                    "The return exceeds the quantities delivered and not yet returned.",
                    violations);
        }
    }

    SalesViews.SalesReturn visible(SalesViews.SalesReturn found) {
        if (!context.canSeeBranch(found.branchId())) {
            throw ApiException.notFound();
        }
        return found;
    }

    private SalesViews.SalesReturn lock(UUID companyId, UUID id, @Nullable String ifMatch, ReturnStatus.Action action) {
        SalesViews.SalesReturn current = visible(returns.lock(companyId, id).orElseThrow(ApiException::notFound));
        EntityTags.requireMatch(ifMatch, current.version());
        if (!ReturnStatus.valueOf(current.status()).allows(action)) {
            throw new ApiException(
                    PlatformErrorCode.INVALID_STATE,
                    "A " + current.status() + " return does not allow "
                            + action.name().toLowerCase(Locale.ROOT) + ".");
        }
        return current;
    }
}
