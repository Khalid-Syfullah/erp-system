package com.erp.procurement.application;

import com.erp.inventory.api.InventoryFacade;
import com.erp.inventory.api.InventoryFacade.OutLine;
import com.erp.inventory.api.InventoryFacade.PostedMovement;
import com.erp.inventory.api.InventoryFacade.SourceRef;
import com.erp.inventory.api.InventoryFacade.StockOutRequest;
import com.erp.org.api.CompanyProfile;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditPort;
import com.erp.platform.context.CurrentContext;
import com.erp.platform.money.RoundingPolicy;
import com.erp.platform.numbering.DocumentNumberService;
import com.erp.platform.numbering.FiscalYears;
import com.erp.platform.web.ApiException;
import com.erp.platform.web.EntityTags;
import com.erp.platform.web.FieldViolation;
import com.erp.platform.web.PlatformErrorCode;
import com.erp.platform.web.paging.ListQuery;
import com.erp.platform.web.paging.PageResponse;
import com.erp.procurement.domain.DocumentStatus;
import com.erp.procurement.persistence.PurchaseOrderRepository;
import com.erp.procurement.persistence.ReceiptRepository;
import com.erp.procurement.persistence.ReturnRepository;
import java.math.BigDecimal;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Purchase returns (PRODUCT_SPEC.md §7): goods of a posted receipt go back to the supplier through
 * {@link InventoryFacade#returnToSupplier}, out at the moving average with the receipt's unit cost
 * as the reference value that clears GRNI (§8.6). At most what was received and not yet returned can
 * go back. The order's received quantity is reduced, so the goods can be received again.
 */
@Service
public class PurchaseReturnService {

    static final String SOURCE_TYPE = "PURCHASE_RETURN";

    private final ReturnRepository returns;
    private final ReceiptRepository receipts;
    private final PurchaseOrderRepository orders;
    private final PurchaseOrderService orderService;
    private final DocumentPricing pricing;
    private final InventoryFacade inventory;
    private final ProcurementContext context;
    private final DocumentNumberService numbering;
    private final AuditPort audit;

    PurchaseReturnService(
            ReturnRepository returns,
            ReceiptRepository receipts,
            PurchaseOrderRepository orders,
            PurchaseOrderService orderService,
            DocumentPricing pricing,
            InventoryFacade inventory,
            ProcurementContext context,
            DocumentNumberService numbering,
            AuditPort audit) {
        this.returns = returns;
        this.receipts = receipts;
        this.orders = orders;
        this.orderService = orderService;
        this.pricing = pricing;
        this.inventory = inventory;
        this.context = context;
        this.numbering = numbering;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public PageResponse<ProcurementViews.PurchaseReturn> list(ListQuery query) {
        return returns.list(
                CurrentContext.requireCompany(), CurrentContext.require().branchScope(), query);
    }

    @Transactional(readOnly = true)
    public ProcurementViews.PurchaseReturnDetail get(UUID id) {
        UUID companyId = CurrentContext.requireCompany();
        ProcurementViews.PurchaseReturn purchaseReturn =
                visible(returns.find(companyId, id).orElseThrow(ApiException::notFound));
        return new ProcurementViews.PurchaseReturnDetail(purchaseReturn, returns.lines(companyId, id));
    }

    @Transactional
    public ProcurementViews.PurchaseReturnDetail create(ProcurementCommands.PurchaseReturn command) {
        UUID companyId = CurrentContext.requireCompany();
        ProcurementViews.GoodsReceipt receipt = receipts.find(companyId, command.goodsReceiptId())
                .filter(r -> context.canSeeBranch(r.branchId()))
                .orElseThrow(() -> ApiException.validationFailed(
                        "The goods receipt is unknown.",
                        List.of(FieldViolation.atPointer(
                                "/goodsReceiptId", "UNKNOWN_GOODS_RECEIPT", "is not a goods receipt you can use"))));
        if (!DocumentStatus.POSTED.name().equals(receipt.status())) {
            throw new ApiException(PlatformErrorCode.INVALID_STATE, "Only posted receipts can be returned.");
        }
        Map<UUID, ProcurementViews.GoodsReceiptLine> receiptLines = receipts.lines(companyId, receipt.id()).stream()
                .collect(Collectors.toMap(ProcurementViews.GoodsReceiptLine::id, Function.identity()));
        List<FieldViolation> violations = new ArrayList<>();
        if (command.lines().isEmpty() || command.lines().size() > DocumentPricing.MAX_LINES) {
            violations.add(FieldViolation.atPointer("/lines", "SIZE", "must contain between 1 and 500 lines"));
        }
        Set<UUID> seen = new HashSet<>();
        List<ReturnRepository.NewLine> lines = new ArrayList<>();
        for (int i = 0; i < command.lines().size(); i++) {
            ProcurementCommands.ReturnLine line = command.lines().get(i);
            ProcurementViews.GoodsReceiptLine receiptLine = receiptLines.get(line.goodsReceiptLineId());
            if (receiptLine == null) {
                violations.add(FieldViolation.atPointer(
                        "/lines/" + i + "/goodsReceiptLineId", "UNKNOWN_LINE", "is not a line of the receipt"));
                continue;
            }
            if (!seen.add(receiptLine.id())) {
                violations.add(FieldViolation.atPointer(
                        "/lines/" + i + "/goodsReceiptLineId", "DUPLICATE_LINE", "appears twice in the return"));
                continue;
            }
            UUID uom = line.uomId() != null ? line.uomId() : receiptLine.uomId();
            BigDecimal base = pricing.quantityBase(i, receiptLine.variantId(), line.quantity(), uom, violations);
            if (base == null) {
                continue;
            }
            BigDecimal returnable = receiptLine.quantityBase().subtract(receiptLine.returnedQuantityBase());
            if (base.compareTo(returnable) > 0) {
                violations.add(exceeds(i, base, returnable));
                continue;
            }
            lines.add(new ReturnRepository.NewLine(
                    i + 1,
                    receiptLine.id(),
                    receiptLine.variantId(),
                    receiptLine.locationId(),
                    line.quantity(),
                    uom,
                    base,
                    Objects.requireNonNull(receiptLine.unitCostBase())));
        }
        if (!violations.isEmpty()) {
            throw ApiException.validationFailed("The return is invalid.", violations);
        }
        CompanyProfile profile = context.profile();
        LocalDate date = command.returnDate() != null ? command.returnDate() : context.today(profile);
        UUID actor = context.actor();
        UUID id = returns.insert(companyId, receipt, date, command.reason(), actor);
        returns.insertLines(companyId, id, lines, actor);
        audit.record(AuditEvent.builder("CREATE", "procurement")
                .entity("purchase_return", id, null)
                .detail("goodsReceiptId", receipt.id())
                .detail("reason", command.reason())
                .detail("lines", lines.size())
                .build());
        return get(id);
    }

    @Transactional
    public ProcurementViews.PurchaseReturnDetail cancel(UUID id, @Nullable String ifMatch) {
        UUID companyId = CurrentContext.requireCompany();
        ProcurementViews.PurchaseReturn current = lock(companyId, id, ifMatch, DocumentStatus.Action.CANCEL);
        if (!returns.markCancelled(companyId, id, current.version(), context.actor())) {
            throw new ApiException(PlatformErrorCode.VERSION_CONFLICT, "The return was modified concurrently.");
        }
        audit.record(AuditEvent.builder("STATE_CHANGE", "procurement")
                .entity("purchase_return", id, null)
                .transition("DRAFT", "CANCELLED")
                .build());
        return get(id);
    }

    /** Posts the return: re-checks the returnable quantities under the order lock and ships the goods out. */
    @Transactional
    public ProcurementViews.PurchaseReturnDetail post(UUID id, @Nullable String ifMatch) {
        UUID companyId = CurrentContext.requireCompany();
        ProcurementViews.PurchaseReturn current = lock(companyId, id, ifMatch, DocumentStatus.Action.POST);
        ProcurementViews.PurchaseOrder order = orderService.lockForFulfilment(companyId, current.purchaseOrderId());
        List<ProcurementViews.PurchaseReturnLine> lines = returns.lines(companyId, id);
        Map<UUID, ProcurementViews.GoodsReceiptLine> receiptLines =
                receipts.lines(companyId, current.goodsReceiptId()).stream()
                        .collect(Collectors.toMap(ProcurementViews.GoodsReceiptLine::id, Function.identity()));
        List<FieldViolation> violations = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            ProcurementViews.GoodsReceiptLine receiptLine =
                    receiptLines.get(lines.get(i).goodsReceiptLineId());
            BigDecimal returnable = receiptLine.quantityBase().subtract(receiptLine.returnedQuantityBase());
            if (lines.get(i).quantityBase().compareTo(returnable) > 0) {
                violations.add(exceeds(i, lines.get(i).quantityBase(), returnable));
            }
        }
        if (!violations.isEmpty()) {
            throw new ApiException(
                    ProcurementErrorCode.QUANTITY_EXCEEDS_REMAINING,
                    "The return exceeds what was received and not yet returned.",
                    violations);
        }
        List<OutLine> outLines = new ArrayList<>();
        for (ProcurementViews.PurchaseReturnLine line : lines) {
            UUID baseUom = inventory.variantInfo(line.variantId()).orElseThrow().baseUomId();
            UUID location = line.locationId() != null
                    ? line.locationId()
                    : receiptLines.get(line.goodsReceiptLineId()).locationId();
            outLines.add(new OutLine(
                    line.variantId(), line.quantityBase(), baseUom, location, null, line.unitCostBase(), line.id()));
        }
        PostedMovement movement = inventory.returnToSupplier(new StockOutRequest(
                new SourceRef(GoodsReceiptService.SOURCE_MODULE, SOURCE_TYPE, id, null),
                current.returnDate(),
                current.warehouseId(),
                current.supplierId(),
                outLines,
                current.reason()));

        CompanyProfile profile = context.profile();
        RoundingPolicy rounding = context.baseRounding(profile);
        Map<UUID, UUID> locations = new HashMap<>();
        movement.lines().forEach(l -> locations.put(Objects.requireNonNull(l.sourceLineId()), l.locationId()));
        Map<UUID, BigDecimal> returnedPerOrderLine = new LinkedHashMap<>();
        for (ProcurementViews.PurchaseReturnLine line : lines) {
            // The value at the receipt's cost: what the stock movement's reference value carries.
            BigDecimal value = rounding.round(line.quantityBase().multiply(line.unitCostBase()));
            returns.setLinePosting(companyId, line.id(), locations.get(line.id()), value);
            ProcurementViews.GoodsReceiptLine receiptLine = receiptLines.get(line.goodsReceiptLineId());
            receipts.updateCounters(
                    companyId,
                    receiptLine.id(),
                    receiptLine.billedQuantityBase(),
                    receiptLine.billedValueBase(),
                    receiptLine.returnedQuantityBase().add(line.quantityBase()),
                    receiptLine.returnedValueBase().add(value),
                    receiptLine.creditedQuantityBase(),
                    receiptLine.creditedValueBase());
            returnedPerOrderLine.merge(receiptLine.purchaseOrderLineId(), line.quantityBase(), BigDecimal::add);
        }
        Map<UUID, ProcurementViews.PurchaseOrderLine> orderLines = orders.lines(companyId, order.id()).stream()
                .collect(Collectors.toMap(ProcurementViews.PurchaseOrderLine::id, Function.identity()));
        returnedPerOrderLine.forEach((lineId, quantity) -> {
            ProcurementViews.PurchaseOrderLine orderLine = orderLines.get(lineId);
            orders.updateLineCounters(
                    companyId,
                    lineId,
                    orderLine.receivedQuantityBase(),
                    orderLine.returnedQuantityBase().add(quantity),
                    orderLine.billedQuantityBase());
        });
        orderService.receiptProgress(companyId, order.id());

        String number = numbering.next(
                companyId,
                ProcurementConfiguration.PURCHASE_RETURN,
                FiscalYears.label(current.returnDate(), profile.fiscalYearStartMonth()));
        if (!returns.markPosted(companyId, id, current.version(), context.actor(), number, movement.movementId())) {
            throw new ApiException(PlatformErrorCode.VERSION_CONFLICT, "The return was modified concurrently.");
        }
        audit.record(AuditEvent.builder("POST", "procurement")
                .entity("purchase_return", id, number)
                .transition("DRAFT", "POSTED")
                .detail("goodsReceiptId", current.goodsReceiptId())
                .detail("stockMovementId", movement.movementId())
                .detail("stockMovementNumber", movement.number())
                .build());
        return get(id);
    }

    private static FieldViolation exceeds(int index, BigDecimal requested, BigDecimal returnable) {
        return new FieldViolation(
                "/lines/" + index + "/quantity",
                null,
                ProcurementErrorCode.QUANTITY_EXCEEDS_REMAINING.code(),
                "Returning " + requested.stripTrailingZeros().toPlainString() + ", returnable "
                        + returnable.stripTrailingZeros().toPlainString(),
                Map.of(
                        "requested", requested.stripTrailingZeros().toPlainString(),
                        "returnable", returnable.stripTrailingZeros().toPlainString()));
    }

    private ProcurementViews.PurchaseReturn visible(ProcurementViews.PurchaseReturn purchaseReturn) {
        if (!context.canSeeBranch(purchaseReturn.branchId())) {
            throw ApiException.notFound();
        }
        return purchaseReturn;
    }

    private ProcurementViews.PurchaseReturn lock(
            UUID companyId, UUID id, @Nullable String ifMatch, DocumentStatus.Action action) {
        ProcurementViews.PurchaseReturn current =
                visible(returns.lock(companyId, id).orElseThrow(ApiException::notFound));
        EntityTags.requireMatch(ifMatch, current.version());
        if (!DocumentStatus.valueOf(current.status()).allows(action)) {
            throw new ApiException(
                    PlatformErrorCode.INVALID_STATE,
                    "A " + current.status() + " return does not allow "
                            + action.name().toLowerCase(java.util.Locale.ROOT) + ".");
        }
        return current;
    }
}
