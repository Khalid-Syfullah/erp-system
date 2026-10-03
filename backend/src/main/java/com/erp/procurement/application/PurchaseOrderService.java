package com.erp.procurement.application;

import com.erp.inventory.api.InventoryFacade;
import com.erp.org.api.CompanyProfile;
import com.erp.org.api.OrgFacade;
import com.erp.partners.api.PartnersFacade;
import com.erp.partners.api.PartnersFacade.SupplierInfo;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditPort;
import com.erp.platform.context.CurrentContext;
import com.erp.platform.events.DomainEvents;
import com.erp.platform.numbering.DocumentNumberService;
import com.erp.platform.numbering.FiscalYears;
import com.erp.platform.security.SegregationOfDuties;
import com.erp.platform.web.ApiException;
import com.erp.platform.web.EntityTags;
import com.erp.platform.web.FieldViolation;
import com.erp.platform.web.MergePatch;
import com.erp.platform.web.PlatformErrorCode;
import com.erp.platform.web.paging.ListQuery;
import com.erp.platform.web.paging.PageResponse;
import com.erp.procurement.ProcurementPermissions;
import com.erp.procurement.domain.PurchaseOrderStatus;
import com.erp.procurement.domain.RequisitionStatus;
import com.erp.procurement.events.PurchaseOrderApproved;
import com.erp.procurement.persistence.PurchaseOrderRepository;
import com.erp.procurement.persistence.ReceiptRepository;
import com.erp.procurement.persistence.RequisitionRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
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
 * Purchase orders (PRODUCT_SPEC.md §7): priced by the server (G-7, G-14), submitted (numbered),
 * approved by someone other than the creator and submitter (G-17) with {@code approve_high} above
 * the company threshold, then received, billed and closed. Approved orders are never edited. The
 * order header row is the lock that serializes every fulfilment of the order (DATABASE.md §9).
 */
@Service
public class PurchaseOrderService {

    static final Set<String> PATCHABLE = Set.of(
            "supplierId",
            "warehouseId",
            "departmentId",
            "orderDate",
            "expectedDate",
            "currencyCode",
            "paymentTermsId",
            "pricesIncludeTax",
            "notes",
            "lines");
    static final List<LinePatchReader.Member> LINE_MEMBERS = List.of(
            LinePatchReader.Member.uuid("variantId", true),
            LinePatchReader.Member.text("description", 300),
            LinePatchReader.Member.decimal("quantity", true),
            LinePatchReader.Member.uuid("uomId", true),
            LinePatchReader.Member.decimal("unitPrice", true),
            LinePatchReader.Member.decimal("discountPercent", false),
            LinePatchReader.Member.uuid("taxCodeId", false),
            LinePatchReader.Member.uuid("requisitionLineId", false));

    private final PurchaseOrderRepository orders;
    private final ReceiptRepository receipts;
    private final RequisitionRepository requisitions;
    private final RequisitionOrdering ordering;
    private final DocumentPricing pricing;
    private final PartnersFacade partners;
    private final InventoryFacade inventory;
    private final OrgFacade org;
    private final ProcurementSettingsService settings;
    private final ProcurementContext context;
    private final DocumentNumberService numbering;
    private final ApplicationEventPublisher events;
    private final AuditPort audit;
    private final Clock clock;

    PurchaseOrderService(
            PurchaseOrderRepository orders,
            ReceiptRepository receipts,
            RequisitionRepository requisitions,
            RequisitionOrdering ordering,
            DocumentPricing pricing,
            PartnersFacade partners,
            InventoryFacade inventory,
            OrgFacade org,
            ProcurementSettingsService settings,
            ProcurementContext context,
            DocumentNumberService numbering,
            ApplicationEventPublisher events,
            AuditPort audit,
            Clock clock) {
        this.orders = orders;
        this.receipts = receipts;
        this.requisitions = requisitions;
        this.ordering = ordering;
        this.pricing = pricing;
        this.partners = partners;
        this.inventory = inventory;
        this.org = org;
        this.settings = settings;
        this.context = context;
        this.numbering = numbering;
        this.events = events;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PageResponse<ProcurementViews.PurchaseOrder> list(ListQuery query) {
        return orders.list(
                CurrentContext.requireCompany(), CurrentContext.require().branchScope(), query);
    }

    @Transactional(readOnly = true)
    public ProcurementViews.PurchaseOrderDetail get(UUID id) {
        UUID companyId = CurrentContext.requireCompany();
        ProcurementViews.PurchaseOrder order =
                visible(orders.find(companyId, id).orElseThrow(ApiException::notFound));
        return new ProcurementViews.PurchaseOrderDetail(order, orders.lines(companyId, id));
    }

    @Transactional
    public ProcurementViews.PurchaseOrderDetail create(ProcurementCommands.PurchaseOrder command) {
        UUID companyId = CurrentContext.requireCompany();
        Draft draft = draft(command, null);
        UUID actor = context.actor();
        UUID id = orders.insert(companyId, draft.header(), actor);
        orders.insertLines(companyId, id, draft.lines(), actor);
        ordering.refresh(companyId, requisitionLines(draft.lines()));
        audit.record(AuditEvent.builder("CREATE", "procurement")
                .entity("purchase_order", id, null)
                .detail("supplierId", command.supplierId())
                .detail("warehouseId", command.warehouseId())
                .detail("currencyCode", draft.header().currencyCode())
                .detail("total", draft.header().total().toPlainString())
                .detail("lines", draft.lines().size())
                .build());
        return get(id);
    }

    /** Edits a draft; {@code lines} replaces all lines and the order is repriced. */
    @Transactional
    public ProcurementViews.PurchaseOrderDetail patch(UUID id, @Nullable String ifMatch, JsonNode document) {
        UUID companyId = CurrentContext.requireCompany();
        ProcurementViews.PurchaseOrder current = lock(companyId, id, ifMatch, PurchaseOrderStatus.Action.EDIT);
        List<ProcurementViews.PurchaseOrderLine> oldLines = orders.lines(companyId, id);
        MergePatch patch = MergePatch.of(document, PATCHABLE);
        var supplier = patch.uuid("supplierId", true);
        var warehouse = patch.uuid("warehouseId", true);
        var department = patch.uuid("departmentId", false);
        var orderDate = patch.date("orderDate", true);
        var expectedDate = patch.date("expectedDate", false);
        var currency = patch.text("currencyCode", true, 3, v -> v.matches("^[A-Z]{3}$") ? null : "must be ISO 4217");
        var terms = patch.uuid("paymentTermsId", false);
        var inclusive = patch.bool("pricesIncludeTax");
        var notes = patch.text("notes", false, 2000);
        patch.throwIfInvalid();
        List<ProcurementCommands.OrderLine> lines = document.has("lines")
                ? readLines(document.get("lines"))
                : oldLines.stream().map(PurchaseOrderService::asCommand).toList();
        ProcurementCommands.PurchaseOrder next = new ProcurementCommands.PurchaseOrder(
                supplier.orElse(current.supplierId()),
                warehouse.orElse(current.warehouseId()),
                department.orElse(current.departmentId()),
                orderDate.orElse(current.orderDate()),
                expectedDate.orElse(current.expectedDate()),
                currency.orElse(current.currencyCode()),
                terms.orElse(current.paymentTermsId()),
                Boolean.TRUE.equals(inclusive.orElse(current.pricesIncludeTax())),
                notes.orElse(current.notes()),
                lines);
        Draft draft = draft(next, current);
        UUID actor = context.actor();
        orders.deleteLines(companyId, id);
        orders.insertLines(companyId, id, draft.lines(), actor);
        if (!orders.updateDraft(companyId, id, current.version(), actor, draft.header())) {
            throw new ApiException(PlatformErrorCode.VERSION_CONFLICT, "The purchase order was modified concurrently.");
        }
        Set<UUID> touched = new HashSet<>(requisitionLines(draft.lines()));
        oldLines.stream()
                .map(ProcurementViews.PurchaseOrderLine::requisitionLineId)
                .filter(Objects::nonNull)
                .forEach(touched::add);
        ordering.refresh(companyId, touched);
        ProcurementViews.PurchaseOrder after = orders.find(companyId, id).orElseThrow();
        audit.record(AuditEvent.builder("UPDATE", "procurement")
                .entity("purchase_order", id, current.number())
                .change("supplierId", current.supplierId(), after.supplierId())
                .change("warehouseId", current.warehouseId(), after.warehouseId())
                .change("departmentId", current.departmentId(), after.departmentId())
                .change("orderDate", current.orderDate(), after.orderDate())
                .change("expectedDate", current.expectedDate(), after.expectedDate())
                .change("currencyCode", current.currencyCode(), after.currencyCode())
                .change("paymentTermsId", current.paymentTermsId(), after.paymentTermsId())
                .change("pricesIncludeTax", current.pricesIncludeTax(), after.pricesIncludeTax())
                .change("total", current.total().toPlainString(), after.total().toPlainString())
                .change("notes", current.notes(), after.notes())
                .detail("linesReplaced", document.has("lines"))
                .build());
        return get(id);
    }

    @Transactional
    public void delete(UUID id, @Nullable String ifMatch) {
        UUID companyId = CurrentContext.requireCompany();
        ProcurementViews.PurchaseOrder current = lock(companyId, id, ifMatch, PurchaseOrderStatus.Action.DELETE);
        List<UUID> linked = requisitionLinesOf(orders.lines(companyId, id));
        if (!orders.delete(companyId, id, current.version())) {
            throw new ApiException(PlatformErrorCode.VERSION_CONFLICT, "The purchase order was modified concurrently.");
        }
        ordering.refresh(companyId, linked);
        audit.record(AuditEvent.builder("DELETE", "procurement")
                .entity("purchase_order", id, current.number())
                .build());
    }

    /** DRAFT → PENDING_APPROVAL; numbered on the first submission (G-6). */
    @Transactional
    public ProcurementViews.PurchaseOrderDetail submit(UUID id, @Nullable String ifMatch) {
        UUID companyId = CurrentContext.requireCompany();
        ProcurementViews.PurchaseOrder current = lock(companyId, id, ifMatch, PurchaseOrderStatus.Action.SUBMIT);
        usableSupplier(current.supplierId());
        CompanyProfile profile = context.profile();
        String number = current.number() != null
                ? current.number()
                : numbering.next(
                        companyId,
                        ProcurementConfiguration.PURCHASE_ORDER,
                        FiscalYears.label(current.orderDate(), profile.fiscalYearStartMonth()));
        transition(
                current,
                PurchaseOrderStatus.Action.SUBMIT,
                new PurchaseOrderRepository.Transition(number, true, false, null, null, null, null),
                null);
        return get(id);
    }

    /**
     * PENDING_APPROVAL → APPROVED by someone other than the creator and the submitter (G-17); above
     * the company threshold (in base currency at the order date) the approver also needs
     * {@code approve_high} (G-16).
     */
    @Transactional
    public ProcurementViews.PurchaseOrderDetail approve(UUID id, @Nullable String ifMatch) {
        UUID companyId = CurrentContext.requireCompany();
        ProcurementViews.PurchaseOrder current = lock(companyId, id, ifMatch, PurchaseOrderStatus.Action.APPROVE);
        UUID actor = context.actor();
        SegregationOfDuties.requireDifferentUsers(actor, current.createdBy(), "approve a purchase order you created");
        SegregationOfDuties.requireDifferentUsers(
                actor, current.submittedBy(), "approve a purchase order you submitted");
        BigDecimal threshold = settings.current(companyId).poApprovalThresholdBase();
        BigDecimal totalBase = null;
        if (threshold != null) {
            CompanyProfile profile = context.profile();
            totalBase = context.baseRounding(profile)
                    .round(current.total().multiply(context.exchangeRate(current.currencyCode(), current.orderDate())));
            if (totalBase.compareTo(threshold) > 0) {
                context.require(
                        ProcurementPermissions.PO_APPROVE_HIGH,
                        "Approving a purchase order of " + totalBase.toPlainString() + " (above "
                                + threshold.toPlainString() + ")");
            }
        }
        transition(
                current,
                PurchaseOrderStatus.Action.APPROVE,
                new PurchaseOrderRepository.Transition(null, false, true, null, null, null, null),
                totalBase == null ? null : "totalBase=" + totalBase.toPlainString());
        events.publishEvent(new PurchaseOrderApproved(
                DomainEvents.metadata(
                        PurchaseOrderApproved.TYPE, PurchaseOrderApproved.SCHEMA_VERSION, companyId, clock),
                id,
                Objects.requireNonNull(current.number()),
                current.supplierId(),
                current.currencyCode(),
                current.subtotal(),
                current.taxTotal(),
                current.total()));
        return get(id);
    }

    /** PENDING_APPROVAL → DRAFT with the reason; the order keeps its number. */
    @Transactional
    public ProcurementViews.PurchaseOrderDetail reject(UUID id, @Nullable String ifMatch, String reason) {
        UUID companyId = CurrentContext.requireCompany();
        ProcurementViews.PurchaseOrder current = lock(companyId, id, ifMatch, PurchaseOrderStatus.Action.REJECT);
        transition(
                current,
                PurchaseOrderStatus.Action.REJECT,
                new PurchaseOrderRepository.Transition(null, false, false, reason, null, null, null),
                reason);
        return get(id);
    }

    /**
     * Cancels a draft, pending or approved order; an approved one only while nothing was received or
     * billed. Its draft receipts are cancelled and its requisition quantities become free again.
     */
    @Transactional
    public ProcurementViews.PurchaseOrderDetail cancel(UUID id, @Nullable String ifMatch, @Nullable String reason) {
        UUID companyId = CurrentContext.requireCompany();
        ProcurementViews.PurchaseOrder current = lock(companyId, id, ifMatch, PurchaseOrderStatus.Action.CANCEL);
        List<ProcurementViews.PurchaseOrderLine> lines = orders.lines(companyId, id);
        if (lines.stream()
                .anyMatch(l -> l.receivedQuantityBase().signum() > 0
                        || l.billedQuantityBase().signum() > 0)) {
            throw new ApiException(
                    PlatformErrorCode.INVALID_STATE,
                    "Goods were received or billed on this order; close it instead of cancelling it.");
        }
        cancelDraftReceipts(companyId, id);
        transition(
                current,
                PurchaseOrderStatus.Action.CANCEL,
                new PurchaseOrderRepository.Transition(null, false, false, null, blankToEmpty(reason), null, null),
                reason);
        ordering.refresh(companyId, requisitionLinesOf(lines));
        return get(id);
    }

    /**
     * Closes an order short (PARTIALLY_RECEIVED or RECEIVED; APPROVED only when it has nothing to
     * receive): no more receipts, its draft receipts are cancelled. Bills can still be posted.
     */
    @Transactional
    public ProcurementViews.PurchaseOrderDetail close(UUID id, @Nullable String ifMatch, @Nullable String reason) {
        UUID companyId = CurrentContext.requireCompany();
        ProcurementViews.PurchaseOrder current = lock(companyId, id, ifMatch, PurchaseOrderStatus.Action.CLOSE);
        List<ProcurementViews.PurchaseOrderLine> lines = orders.lines(companyId, id);
        if ("APPROVED".equals(current.status())
                && lines.stream().anyMatch(ProcurementViews.PurchaseOrderLine::stockable)) {
            throw new ApiException(
                    PlatformErrorCode.INVALID_STATE,
                    "Nothing has been received on this order yet; cancel it instead of closing it.");
        }
        cancelDraftReceipts(companyId, id);
        transition(
                current,
                PurchaseOrderStatus.Action.CLOSE,
                new PurchaseOrderRepository.Transition(null, false, false, null, null, blankToEmpty(reason), null),
                reason);
        return get(id);
    }

    /**
     * Converts lines of an approved requisition into a new draft order for one supplier (whole
     * remaining quantities; prices from the estimates, taxes from the product or supplier defaults).
     */
    @Transactional
    public ProcurementViews.PurchaseOrderDetail convert(
            UUID requisitionId, @Nullable String ifMatch, UUID supplierId, UUID warehouseId, List<UUID> lineIds) {
        UUID companyId = CurrentContext.requireCompany();
        ProcurementViews.Requisition requisition =
                requisitions.lock(companyId, requisitionId).orElseThrow(ApiException::notFound);
        if (!context.canSeeBranch(requisition.branchId())) {
            throw ApiException.notFound();
        }
        EntityTags.requireMatch(ifMatch, requisition.version());
        RequisitionStatus status = RequisitionStatus.valueOf(requisition.status());
        if (status != RequisitionStatus.APPROVED && status != RequisitionStatus.PARTIALLY_ORDERED) {
            throw new ApiException(
                    PlatformErrorCode.INVALID_STATE,
                    "A " + requisition.status() + " requisition cannot be converted into orders.");
        }
        SupplierInfo supplier = usableSupplier(supplierId);
        CompanyProfile profile = context.profile();
        LocalDate orderDate = context.today(profile);
        List<ProcurementViews.RequisitionLine> all = requisitions.lines(companyId, requisitionId);
        List<FieldViolation> violations = new ArrayList<>();
        List<ProcurementViews.RequisitionLine> chosen = new ArrayList<>();
        if (lineIds.isEmpty()) {
            all.stream().filter(l -> remaining(l).signum() > 0).forEach(chosen::add);
        } else {
            for (int i = 0; i < lineIds.size(); i++) {
                UUID lineId = lineIds.get(i);
                var line = all.stream().filter(l -> l.id().equals(lineId)).findFirst();
                if (line.isEmpty()) {
                    violations.add(FieldViolation.atPointer(
                            "/lineIds/" + i, "UNKNOWN_LINE", "is not a line of the requisition"));
                } else if (remaining(line.get()).signum() <= 0) {
                    violations.add(
                            FieldViolation.atPointer("/lineIds/" + i, "NOTHING_TO_ORDER", "is fully ordered already"));
                } else {
                    chosen.add(line.get());
                }
            }
        }
        if (chosen.isEmpty() && violations.isEmpty()) {
            violations.add(FieldViolation.atPointer("/lineIds", "NOTHING_TO_ORDER", "no line has quantity left"));
        }
        if (!violations.isEmpty()) {
            throw ApiException.validationFailed("The conversion is invalid.", violations);
        }
        List<ProcurementCommands.OrderLine> lines = new ArrayList<>();
        for (ProcurementViews.RequisitionLine line : chosen) {
            var variant = inventory.variantInfo(line.variantId()).orElseThrow();
            BigDecimal left = remaining(line);
            boolean whole = left.compareTo(line.quantityBase()) == 0;
            BigDecimal quantity = whole ? line.quantity() : left;
            UUID uom = whole ? line.uomId() : variant.baseUomId();
            BigDecimal price = line.estimatedUnitPrice() == null
                    ? BigDecimal.ZERO
                    : whole
                            ? line.estimatedUnitPrice()
                            : line.estimatedUnitPrice()
                                    .multiply(line.quantity())
                                    .divide(
                                            line.quantityBase(),
                                            DocumentPricing.UNIT_PRICE_SCALE,
                                            RoundingMode.HALF_UP);
            UUID taxCode = defaultTaxCode(variant.purchaseTaxCodeId(), supplier.defaultTaxCodeId(), orderDate);
            lines.add(new ProcurementCommands.OrderLine(
                    line.variantId(), line.description(), quantity, uom, price, BigDecimal.ZERO, taxCode, line.id()));
        }
        ProcurementCommands.PurchaseOrder command = new ProcurementCommands.PurchaseOrder(
                supplierId,
                warehouseId,
                requisition.departmentId(),
                orderDate,
                null,
                null,
                null,
                false,
                "From requisition " + requisition.number(),
                lines);
        Draft draft = draft(command, null);
        UUID actor = context.actor();
        UUID id = orders.insert(companyId, draft.header(), actor);
        orders.insertLines(companyId, id, draft.lines(), actor);
        ordering.refresh(requisition);
        audit.record(AuditEvent.builder("CREATE", "procurement")
                .entity("purchase_order", id, null)
                .detail("requisitionId", requisitionId)
                .detail("supplierId", supplierId)
                .detail("lines", lines.size())
                .build());
        return get(id);
    }

    // ------------------------------------------------- fulfilment (called under the order lock)

    /** The order locked {@code FOR UPDATE} for a receipt, return or bill. */
    ProcurementViews.PurchaseOrder lockForFulfilment(UUID companyId, UUID id) {
        return orders.lock(companyId, id).orElseThrow(ApiException::notFound);
    }

    /** Moves the order along APPROVED / PARTIALLY_RECEIVED / RECEIVED after receipts and returns. */
    void receiptProgress(UUID companyId, UUID orderId) {
        ProcurementViews.PurchaseOrder order = orders.find(companyId, orderId).orElseThrow();
        PurchaseOrderStatus from = PurchaseOrderStatus.valueOf(order.status());
        if (!from.allows(PurchaseOrderStatus.Action.RECEIVE)) {
            return;
        }
        List<ProcurementViews.PurchaseOrderLine> stockable = orders.lines(companyId, orderId).stream()
                .filter(ProcurementViews.PurchaseOrderLine::stockable)
                .toList();
        boolean any = stockable.stream().anyMatch(l -> l.netReceivedBase().signum() > 0);
        boolean all = !stockable.isEmpty()
                && stockable.stream().allMatch(l -> l.netReceivedBase().compareTo(l.quantityBase()) >= 0);
        PurchaseOrderStatus to = from.received(any, all);
        if (to != from) {
            if (!orders.transition(
                    companyId,
                    orderId,
                    order.version(),
                    context.actor(),
                    to.name(),
                    PurchaseOrderRepository.Transition.NONE)) {
                throw new IllegalStateException("Purchase order changed although it is locked");
            }
            audit.record(AuditEvent.builder("STATE_CHANGE", "procurement")
                    .entity("purchase_order", orderId, order.number())
                    .transition(from.name(), to.name())
                    .build());
        }
        billingProgress(companyId, orderId);
    }

    /**
     * Updates the billing status; a fully received (or receipt-free) order that is fully billed
     * closes itself (PRODUCT_SPEC.md §7.2).
     */
    void billingProgress(UUID companyId, UUID orderId) {
        ProcurementViews.PurchaseOrder order = orders.find(companyId, orderId).orElseThrow();
        List<ProcurementViews.PurchaseOrderLine> lines = orders.lines(companyId, orderId);
        PurchaseOrderStatus status = PurchaseOrderStatus.valueOf(order.status());
        boolean receivingDone = status == PurchaseOrderStatus.RECEIVED
                || status == PurchaseOrderStatus.CLOSED
                || (status == PurchaseOrderStatus.APPROVED
                        && lines.stream().noneMatch(ProcurementViews.PurchaseOrderLine::stockable));
        boolean any = lines.stream().anyMatch(l -> l.billedQuantityBase().signum() > 0);
        boolean all = receivingDone
                && lines.stream()
                        .allMatch(l ->
                                l.billedQuantityBase().compareTo(l.stockable() ? l.netReceivedBase() : l.quantityBase())
                                        >= 0);
        String billing = PurchaseOrderStatus.Billing.of(any, all).name();
        boolean close = all
                && (status == PurchaseOrderStatus.RECEIVED || status == PurchaseOrderStatus.APPROVED)
                && status.allows(PurchaseOrderStatus.Action.CLOSE);
        if (billing.equals(order.billingStatus()) && !close) {
            return;
        }
        String to = close ? PurchaseOrderStatus.CLOSED.name() : order.status();
        if (!orders.transition(
                companyId,
                orderId,
                order.version(),
                context.actor(),
                to,
                new PurchaseOrderRepository.Transition(
                        null, false, false, null, null, close ? "Fully received and billed" : null, billing))) {
            throw new IllegalStateException("Purchase order changed although it is locked");
        }
        AuditEvent.Builder event = AuditEvent.builder("STATE_CHANGE", "procurement")
                .entity("purchase_order", orderId, order.number())
                .change("billingStatus", order.billingStatus(), billing);
        if (close) {
            event.transition(order.status(), to);
        }
        audit.record(event.build());
    }

    ProcurementViews.PurchaseOrder visible(ProcurementViews.PurchaseOrder order) {
        if (!context.canSeeBranch(order.branchId())) {
            throw ApiException.notFound();
        }
        return order;
    }

    // ------------------------------------------------------------------------------ helpers

    /** A validated, priced draft: header values and lines. */
    private record Draft(PurchaseOrderRepository.Header header, List<PurchaseOrderRepository.NewLine> lines) {}

    private Draft draft(ProcurementCommands.PurchaseOrder command, ProcurementViews.@Nullable PurchaseOrder current) {
        UUID companyId = CurrentContext.requireCompany();
        CompanyProfile profile = context.profile();
        List<FieldViolation> violations = new ArrayList<>();
        SupplierInfo supplier = current != null && current.supplierId().equals(command.supplierId())
                ? partners.supplierForUse(command.supplierId()).orElse(null)
                : usableSupplier(command.supplierId());
        if (supplier == null) {
            violations.add(
                    FieldViolation.atPointer("/supplierId", "UNKNOWN_SUPPLIER", "is not a supplier of the company"));
        }
        var warehouse = inventory
                .warehouse(command.warehouseId())
                .filter(w -> context.canSeeBranch(w.branchId()))
                .orElse(null);
        UUID branchId = null;
        if (warehouse == null) {
            violations.add(
                    FieldViolation.atPointer("/warehouseId", "UNKNOWN_WAREHOUSE", "is not a warehouse you can use"));
        } else if (!warehouse.active()) {
            violations.add(FieldViolation.atPointer("/warehouseId", "INACTIVE", "must be an active warehouse"));
        } else {
            branchId = warehouse.branchId();
            context.checkBranch(branchId, command.departmentId(), "/warehouseId", violations);
        }
        String currency = command.currencyCode() != null
                ? command.currencyCode()
                : supplier == null ? profile.baseCurrency() : supplier.currencyCode();
        if (!context.currencyUsable(currency)) {
            violations.add(FieldViolation.atPointer("/currencyCode", "UNKNOWN_CURRENCY", "is not an active currency"));
        }
        UUID terms = command.paymentTermsId() != null
                ? command.paymentTermsId()
                : supplier == null ? null : supplier.paymentTermsId();
        if (terms != null
                && !org.paymentTerms(companyId, terms).map(t -> t.active()).orElse(false)) {
            violations.add(FieldViolation.atPointer(
                    "/paymentTermsId", "INVALID_VALUE", "must be active payment terms of the company"));
        }
        LocalDate orderDate = command.orderDate() != null ? command.orderDate() : context.today(profile);
        if (command.expectedDate() != null && command.expectedDate().isBefore(orderDate)) {
            violations.add(FieldViolation.atPointer(
                    "/expectedDate", "BEFORE_ORDER_DATE", "must not be before the order date"));
        }
        checkRequisitionLinks(command.lines(), violations);
        List<DocumentPricing.Input> inputs = new ArrayList<>();
        for (int i = 0; i < command.lines().size(); i++) {
            ProcurementCommands.OrderLine l = command.lines().get(i);
            inputs.add(new DocumentPricing.Input(
                    i,
                    l.variantId(),
                    l.description(),
                    l.quantity(),
                    l.uomId(),
                    l.unitPrice(),
                    l.discountPercent(),
                    l.taxCodeId()));
        }
        DocumentPricing.Priced priced = pricing.price(
                inputs,
                command.pricesIncludeTax(),
                currency,
                orderDate,
                profile,
                context.rounding(currency, profile),
                violations);
        List<PurchaseOrderRepository.NewLine> lines = new ArrayList<>();
        for (int i = 0; i < priced.lines().size(); i++) {
            DocumentPricing.Line p = priced.lines().get(i);
            ProcurementCommands.OrderLine l = command.lines().get(i);
            lines.add(new PurchaseOrderRepository.NewLine(
                    i + 1,
                    l.variantId(),
                    p.description(),
                    "STOCKABLE".equals(p.variant().productType()),
                    l.quantity(),
                    l.uomId(),
                    p.quantityBase(),
                    l.unitPrice(),
                    l.discountPercent(),
                    l.taxCodeId(),
                    p.net(),
                    p.tax(),
                    p.total(),
                    l.requisitionLineId()));
        }
        return new Draft(
                new PurchaseOrderRepository.Header(
                        command.supplierId(),
                        Objects.requireNonNull(branchId),
                        command.warehouseId(),
                        command.departmentId(),
                        orderDate,
                        command.expectedDate(),
                        currency,
                        terms,
                        command.pricesIncludeTax(),
                        priced.result().subtotal(),
                        priced.result().taxTotal(),
                        priced.result().total(),
                        command.notes()),
                lines);
    }

    /** Lines may name an approved requisition's line of the same product. */
    private void checkRequisitionLinks(List<ProcurementCommands.OrderLine> lines, List<FieldViolation> violations) {
        Set<UUID> ids = new HashSet<>();
        lines.stream()
                .map(ProcurementCommands.OrderLine::requisitionLineId)
                .filter(Objects::nonNull)
                .forEach(ids::add);
        Map<UUID, RequisitionRepository.LineOfRequisition> found =
                requisitions.linesById(CurrentContext.requireCompany(), ids);
        for (int i = 0; i < lines.size(); i++) {
            UUID link = lines.get(i).requisitionLineId();
            if (link == null) {
                continue;
            }
            var line = found.get(link);
            if (line == null
                    || !line.line().variantId().equals(lines.get(i).variantId())
                    || !RequisitionStatus.valueOf(line.requisition().status()).allows(RequisitionStatus.Action.ORDER)) {
                violations.add(FieldViolation.atPointer(
                        "/lines/" + i + "/requisitionLineId",
                        "INVALID_VALUE",
                        "must be a line of an approved requisition for the same product"));
            }
        }
    }

    private SupplierInfo usableSupplier(UUID supplierId) {
        SupplierInfo supplier = partners.supplierForUse(supplierId)
                .orElseThrow(() -> ApiException.validationFailed(
                        "The supplier is unknown.",
                        List.of(FieldViolation.atPointer(
                                "/supplierId", "UNKNOWN_SUPPLIER", "is not a supplier of the company"))));
        if (!supplier.usable()) {
            throw new ApiException(
                    ProcurementErrorCode.PARTNER_BLOCKED,
                    "Supplier " + supplier.code() + " is " + supplier.status()
                            + " and cannot be used on new documents.",
                    List.of(FieldViolation.atPointer("/supplierId", supplier.status(), "is not an active supplier")));
        }
        return supplier;
    }

    private @Nullable UUID defaultTaxCode(@Nullable UUID productCode, @Nullable UUID supplierCode, LocalDate date) {
        for (UUID candidate : new UUID[] {productCode, supplierCode}) {
            if (candidate != null
                    && org.taxCode(CurrentContext.requireCompany(), candidate)
                            .map(t -> t.appliesToPurchases() && t.usableOn(date))
                            .orElse(false)) {
                return candidate;
            }
        }
        return null;
    }

    private ProcurementViews.PurchaseOrder lock(
            UUID companyId, UUID id, @Nullable String ifMatch, PurchaseOrderStatus.Action action) {
        ProcurementViews.PurchaseOrder current =
                visible(orders.lock(companyId, id).orElseThrow(ApiException::notFound));
        EntityTags.requireMatch(ifMatch, current.version());
        if (!PurchaseOrderStatus.valueOf(current.status()).allows(action)) {
            throw new ApiException(
                    PlatformErrorCode.INVALID_STATE,
                    "A " + current.status() + " purchase order does not allow "
                            + action.name().toLowerCase(java.util.Locale.ROOT) + ".");
        }
        return current;
    }

    private void transition(
            ProcurementViews.PurchaseOrder current,
            PurchaseOrderStatus.Action action,
            PurchaseOrderRepository.Transition fields,
            @Nullable String detail) {
        PurchaseOrderStatus from = PurchaseOrderStatus.valueOf(current.status());
        PurchaseOrderStatus to = from.apply(action);
        if (!orders.transition(
                current.companyId(), current.id(), current.version(), context.actor(), to.name(), fields)) {
            throw new ApiException(PlatformErrorCode.VERSION_CONFLICT, "The purchase order was modified concurrently.");
        }
        AuditEvent.Builder event = AuditEvent.builder("STATE_CHANGE", "procurement")
                .entity("purchase_order", current.id(), fields.number() != null ? fields.number() : current.number())
                .transition(from.name(), to.name());
        if (detail != null && !detail.isBlank()) {
            event.detail("detail", detail);
        }
        audit.record(event.build());
    }

    private void cancelDraftReceipts(UUID companyId, UUID orderId) {
        for (ProcurementViews.GoodsReceipt draft : receipts.draftsOfOrder(companyId, orderId)) {
            receipts.markCancelled(companyId, draft.id(), draft.version(), context.actor());
            audit.record(AuditEvent.builder("STATE_CHANGE", "procurement")
                    .entity("goods_receipt", draft.id(), null)
                    .transition("DRAFT", "CANCELLED")
                    .detail("reason", "Purchase order no longer receivable")
                    .build());
        }
    }

    static List<ProcurementCommands.OrderLine> readLines(JsonNode array) {
        return LinePatchReader.read(array, LINE_MEMBERS).stream()
                .map(v -> new ProcurementCommands.OrderLine(
                        v.uuid("variantId"),
                        v.text("description"),
                        v.decimal("quantity"),
                        v.uuid("uomId"),
                        v.decimal("unitPrice"),
                        v.decimal("discountPercent") == null ? BigDecimal.ZERO : v.decimal("discountPercent"),
                        v.uuid("taxCodeId"),
                        v.uuid("requisitionLineId")))
                .toList();
    }

    private static ProcurementCommands.OrderLine asCommand(ProcurementViews.PurchaseOrderLine l) {
        return new ProcurementCommands.OrderLine(
                l.variantId(),
                l.description(),
                l.quantity(),
                l.uomId(),
                l.unitPrice(),
                l.discountPercent(),
                l.taxCodeId(),
                l.requisitionLineId());
    }

    private static BigDecimal remaining(ProcurementViews.RequisitionLine line) {
        return line.quantityBase().subtract(line.orderedQuantityBase());
    }

    private static List<UUID> requisitionLines(List<PurchaseOrderRepository.NewLine> lines) {
        return lines.stream()
                .map(PurchaseOrderRepository.NewLine::requisitionLineId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    private static List<UUID> requisitionLinesOf(List<ProcurementViews.PurchaseOrderLine> lines) {
        return lines.stream()
                .map(ProcurementViews.PurchaseOrderLine::requisitionLineId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    private static String blankToEmpty(@Nullable String reason) {
        return reason == null || reason.isBlank() ? "" : reason.strip();
    }
}
