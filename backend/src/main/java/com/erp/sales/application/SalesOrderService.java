package com.erp.sales.application;

import com.erp.inventory.api.InventoryFacade;
import com.erp.inventory.api.InventoryFacade.ReservationRequest;
import com.erp.inventory.api.InventoryFacade.ReservationResult;
import com.erp.inventory.api.InventoryFacade.SourceRef;
import com.erp.org.api.CompanyProfile;
import com.erp.partners.api.PartnersFacade;
import com.erp.partners.api.PartnersFacade.CustomerInfo;
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
import com.erp.platform.web.PlatformErrorCode;
import com.erp.platform.web.paging.ListQuery;
import com.erp.platform.web.paging.PageResponse;
import com.erp.sales.SalesPermissions;
import com.erp.sales.domain.CreditCheck;
import com.erp.sales.domain.SalesOrderStatus;
import com.erp.sales.events.SalesOrderCancelled;
import com.erp.sales.events.SalesOrderConfirmed;
import com.erp.sales.persistence.DeliveryRepository;
import com.erp.sales.persistence.InvoiceRepository;
import com.erp.sales.persistence.PriceListRepository;
import com.erp.sales.persistence.SalesOrderRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
 * Sales orders (PRODUCT_SPEC.md §9): priced drafts (SAL-1) that are confirmed — numbered, checked
 * against the customer's credit (SAL-2, override with {@code sales.order.override_credit} and a
 * reason) and reserved in Inventory (SAL-3, partial reservations leave a backorder that
 * {@code reserve} retries) — then delivered, invoiced and closed. Confirmed orders are never edited;
 * cancelling or closing releases what is still reserved. The header row is the lock that serializes
 * every fulfilment of the order (DATABASE.md §9).
 */
@Service
public class SalesOrderService {

    static final String SOURCE_MODULE = "sales";
    static final String SOURCE_TYPE = "SALES_ORDER";
    static final Set<String> PATCHABLE = Set.of(
            "customerId",
            "warehouseId",
            "orderDate",
            "requestedDate",
            "customerReference",
            "currencyCode",
            "priceListId",
            "paymentTermsId",
            "invoicePolicy",
            "notes",
            "lines");

    private final SalesOrderRepository orders;
    private final DeliveryRepository deliveries;
    private final InvoiceRepository invoices;
    private final PriceListRepository priceLists;
    private final SalesDrafts drafts;
    private final PartnersFacade partners;
    private final InventoryFacade inventory;
    private final SalesSettingsService settings;
    private final SalesConfiguration.Receivables receivables;
    private final SalesContext context;
    private final DocumentNumberService numbering;
    private final ApplicationEventPublisher events;
    private final AuditPort audit;
    private final Clock clock;

    SalesOrderService(
            SalesOrderRepository orders,
            DeliveryRepository deliveries,
            InvoiceRepository invoices,
            PriceListRepository priceLists,
            SalesDrafts drafts,
            PartnersFacade partners,
            InventoryFacade inventory,
            SalesSettingsService settings,
            SalesConfiguration.Receivables receivables,
            SalesContext context,
            DocumentNumberService numbering,
            ApplicationEventPublisher events,
            AuditPort audit,
            Clock clock) {
        this.orders = orders;
        this.deliveries = deliveries;
        this.invoices = invoices;
        this.priceLists = priceLists;
        this.drafts = drafts;
        this.partners = partners;
        this.inventory = inventory;
        this.settings = settings;
        this.receivables = receivables;
        this.context = context;
        this.numbering = numbering;
        this.events = events;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PageResponse<SalesViews.SalesOrder> list(ListQuery query) {
        return orders.list(
                CurrentContext.requireCompany(), CurrentContext.require().branchScope(), query);
    }

    @Transactional(readOnly = true)
    public SalesViews.SalesOrderDetail get(UUID id) {
        UUID companyId = CurrentContext.requireCompany();
        SalesViews.SalesOrder order = visible(orders.find(companyId, id).orElseThrow(ApiException::notFound));
        return new SalesViews.SalesOrderDetail(order, orders.lines(companyId, id));
    }

    @Transactional
    public SalesViews.SalesOrderDetail create(SalesCommands.SalesOrder command) {
        return insert(command, null, null, List.of());
    }

    /** A draft order from an accepted quotation: its lines at the quoted prices (no new approvals). */
    SalesViews.SalesOrderDetail createFromQuotation(SalesViews.Quotation quotation, List<SalesViews.Line> lines) {
        UUID companyId = CurrentContext.requireCompany();
        LocalDate today = context.today(context.profile());
        UUID priceList = quotation.priceListId() == null
                ? null
                : priceLists
                        .find(companyId, quotation.priceListId())
                        .filter(l -> l.validOn(today) && l.currencyCode().equals(quotation.currencyCode()))
                        .map(SalesViews.PriceList::id)
                        .orElse(null);
        SalesCommands.SalesOrder command = new SalesCommands.SalesOrder(
                quotation.customerId(),
                quotation.warehouseId(),
                today,
                null,
                quotation.number(),
                quotation.currencyCode(),
                priceList,
                quotation.paymentTermsId(),
                null,
                quotation.notes(),
                lines.stream().map(SalesDrafts::asCommand).toList());
        return insert(command, quotation.id(), quotation.pricesIncludeTax(), lines);
    }

    private SalesViews.SalesOrderDetail insert(
            SalesCommands.SalesOrder command,
            @Nullable UUID quotationId,
            @Nullable Boolean pricesIncludeTax,
            List<SalesViews.Line> approved) {
        UUID companyId = CurrentContext.requireCompany();
        Prepared prepared = prepare(command, null, quotationId, pricesIncludeTax, approved);
        UUID actor = context.actor();
        UUID id = orders.insert(companyId, prepared.header(), actor);
        orders.insertLines(companyId, id, prepared.lines(), actor);
        audit.record(AuditEvent.builder("CREATE", "sales")
                .entity("sales_order", id, null)
                .detail("customerId", command.customerId())
                .detail("quotationId", quotationId)
                .detail("warehouseId", command.warehouseId())
                .detail("currencyCode", prepared.header().currencyCode())
                .detail("total", prepared.header().total().toPlainString())
                .detail("lines", prepared.lines().size())
                .build());
        return get(id);
    }

    /** Edits a draft; {@code lines} replaces all lines and the order is repriced. */
    @Transactional
    public SalesViews.SalesOrderDetail patch(UUID id, @Nullable String ifMatch, JsonNode document) {
        UUID companyId = CurrentContext.requireCompany();
        SalesViews.SalesOrder current = lock(companyId, id, ifMatch, SalesOrderStatus.Action.EDIT);
        List<SalesViews.Line> oldLines = orders.lines(companyId, id).stream()
                .map(SalesViews.SalesOrderLine::line)
                .toList();
        MergePatch patch = MergePatch.of(document, PATCHABLE);
        var customer = patch.uuid("customerId", true);
        var warehouse = patch.uuid("warehouseId", true);
        var orderDate = patch.date("orderDate", true);
        var requestedDate = patch.date("requestedDate", false);
        var reference = patch.text("customerReference", false, 100);
        var currency = patch.text("currencyCode", true, 3, v -> v.matches("^[A-Z]{3}$") ? null : "must be ISO 4217");
        var priceList = patch.uuid("priceListId", false);
        var terms = patch.uuid("paymentTermsId", false);
        var policy = patch.text(
                "invoicePolicy", true, 10, v -> Set.of("ORDERED", "DELIVERED").contains(v) ? null : "is not valid");
        var notes = patch.text("notes", false, 2000);
        patch.throwIfInvalid();
        List<SalesCommands.PricedLine> lines = document.has("lines")
                ? SalesDrafts.readLines(document.get("lines"))
                : oldLines.stream().map(SalesDrafts::asCommand).toList();
        SalesCommands.SalesOrder next = new SalesCommands.SalesOrder(
                Objects.requireNonNull(customer.orElse(current.customerId())),
                Objects.requireNonNull(warehouse.orElse(current.warehouseId())),
                orderDate.orElse(current.orderDate()),
                requestedDate.orElse(current.requestedDate()),
                reference.orElse(current.customerReference()),
                currency.orElse(current.currencyCode()),
                priceList.orElse(current.priceListId()),
                terms.orElse(current.paymentTermsId()),
                policy.orElse(current.invoicePolicy()),
                notes.orElse(current.notes()),
                lines);
        Boolean inclusive = Objects.equals(next.priceListId(), current.priceListId())
                        && Objects.equals(next.customerId(), current.customerId())
                        && current.priceListId() == null
                ? Boolean.valueOf(current.pricesIncludeTax())
                : null;
        Prepared prepared = prepare(next, current, current.quotationId(), inclusive, oldLines);
        UUID actor = context.actor();
        orders.deleteLines(companyId, id);
        orders.insertLines(companyId, id, prepared.lines(), actor);
        if (!orders.updateDraft(companyId, id, current.version(), actor, prepared.header())) {
            throw new ApiException(PlatformErrorCode.VERSION_CONFLICT, "The sales order was modified concurrently.");
        }
        SalesViews.SalesOrder after = orders.find(companyId, id).orElseThrow();
        audit.record(AuditEvent.builder("UPDATE", "sales")
                .entity("sales_order", id, current.number())
                .change("customerId", current.customerId(), after.customerId())
                .change("warehouseId", current.warehouseId(), after.warehouseId())
                .change("orderDate", current.orderDate(), after.orderDate())
                .change("requestedDate", current.requestedDate(), after.requestedDate())
                .change("customerReference", current.customerReference(), after.customerReference())
                .change("currencyCode", current.currencyCode(), after.currencyCode())
                .change("priceListId", current.priceListId(), after.priceListId())
                .change("paymentTermsId", current.paymentTermsId(), after.paymentTermsId())
                .change("invoicePolicy", current.invoicePolicy(), after.invoicePolicy())
                .change("total", current.total().toPlainString(), after.total().toPlainString())
                .change("notes", current.notes(), after.notes())
                .detail("linesReplaced", document.has("lines"))
                .build());
        return get(id);
    }

    @Transactional
    public void delete(UUID id, @Nullable String ifMatch) {
        UUID companyId = CurrentContext.requireCompany();
        SalesViews.SalesOrder current = lock(companyId, id, ifMatch, SalesOrderStatus.Action.DELETE);
        if (current.quotationId() != null) {
            throw new ApiException(
                    PlatformErrorCode.INVALID_STATE,
                    "The order was created from an accepted quotation; cancel it instead of deleting it.");
        }
        if (!orders.delete(companyId, id, current.version())) {
            throw new ApiException(PlatformErrorCode.VERSION_CONFLICT, "The sales order was modified concurrently.");
        }
        audit.record(AuditEvent.builder("DELETE", "sales")
                .entity("sales_order", id, null)
                .build());
    }

    /** The credit check (SAL-2) as confirming the order now would compute it. */
    @Transactional(readOnly = true)
    public SalesViews.CreditCheckResult creditCheck(UUID id) {
        UUID companyId = CurrentContext.requireCompany();
        SalesViews.SalesOrder order = visible(orders.find(companyId, id).orElseThrow(ApiException::notFound));
        CustomerInfo customer = partners.customer(order.customerId()).orElseThrow();
        BigDecimal rate = order.exchangeRate() != null
                ? order.exchangeRate()
                : context.exchangeRate(order.currencyCode(), order.orderDate());
        return creditCheck(order, customer, rate, context.profile());
    }

    /**
     * DRAFT → CONFIRMED: the customer must be active; the credit check decides (a blocked check needs
     * {@code overrideReason} and {@code sales.order.override_credit}); stockable lines are reserved
     * as far as stock allows (SAL-3); the order is numbered (G-6). Publishes
     * {@code sales.order.confirmed}.
     */
    @Transactional
    public SalesViews.SalesOrderDetail confirm(UUID id, @Nullable String ifMatch, @Nullable String overrideReason) {
        UUID companyId = CurrentContext.requireCompany();
        SalesViews.SalesOrder current = lock(companyId, id, ifMatch, SalesOrderStatus.Action.CONFIRM);
        CustomerInfo customer = drafts.usableCustomer(current.customerId());
        orders.lockCustomerCredit(companyId, customer.partnerId());
        CompanyProfile profile = context.profile();
        BigDecimal rate = context.exchangeRate(current.currencyCode(), current.orderDate());
        SalesViews.CreditCheckResult check = creditCheck(current, customer, rate, profile);
        CreditCheck.Outcome outcome = CreditCheck.Outcome.valueOf(check.outcome());
        UUID actor = context.actor();
        String result = outcome.name();
        UUID overrideBy = null;
        String reason = null;
        if (outcome.blocked()) {
            if (overrideReason == null || overrideReason.isBlank()) {
                throw creditRefusal(customer, check, outcome);
            }
            context.require(SalesPermissions.ORDER_OVERRIDE_CREDIT, "Confirming an order that fails the credit check");
            result = "OVERRIDDEN";
            overrideBy = actor;
            reason = overrideReason.strip();
        }
        SalesOrderStatus to = SalesOrderStatus.valueOf(current.status()).apply(SalesOrderStatus.Action.CONFIRM);
        BigDecimal backorder = BigDecimal.ZERO;
        if (settings.current(companyId).reserveOnConfirm()) {
            backorder = reserveOpen(current, null);
        }
        String number = numbering.next(
                companyId,
                SalesConfiguration.SALES_ORDER,
                FiscalYears.label(current.orderDate(), profile.fiscalYearStartMonth()));
        if (!orders.confirm(
                companyId,
                id,
                current.version(),
                actor,
                new SalesOrderRepository.Confirmation(number, rate, result, overrideBy, reason))) {
            throw new ApiException(PlatformErrorCode.VERSION_CONFLICT, "The sales order was modified concurrently.");
        }
        audit.record(AuditEvent.builder("STATE_CHANGE", "sales")
                .entity("sales_order", id, number)
                .transition(current.status(), to.name())
                .detail("creditCheckResult", result)
                .detail("creditCheckOutcome", outcome.name())
                .detail("exposureBase", check.exposureBase().toPlainString())
                .detail("totalBase", check.orderTotalBase().toPlainString())
                .detail(
                        "creditLimit",
                        check.creditLimit() == null ? null : check.creditLimit().toPlainString())
                .detail("creditOverrideReason", reason)
                .detail("exchangeRate", rate.toPlainString())
                .detail("backorderQuantityBase", backorder.toPlainString())
                .build());
        events.publishEvent(new SalesOrderConfirmed(
                DomainEvents.metadata(SalesOrderConfirmed.TYPE, SalesOrderConfirmed.SCHEMA_VERSION, companyId, clock),
                id,
                number,
                current.customerId(),
                current.currencyCode(),
                current.total(),
                check.orderTotalBase(),
                result));
        return get(id);
    }

    /** Retries the reservation of what is still open and unreserved (backorders, SAL-3). */
    @Transactional
    public SalesViews.SalesOrderDetail reserve(UUID id, @Nullable String ifMatch) {
        UUID companyId = CurrentContext.requireCompany();
        SalesViews.SalesOrder current = lock(companyId, id, ifMatch, SalesOrderStatus.Action.RESERVE);
        List<SalesViews.SalesOrderLine> before = orders.lines(companyId, id);
        BigDecimal backorder = reserveOpen(current, current.number());
        if (!orders.transition(companyId, id, current.version(), context.actor(), current.status(), null, null, null)) {
            throw new ApiException(PlatformErrorCode.VERSION_CONFLICT, "The sales order was modified concurrently.");
        }
        BigDecimal reservedBefore = before.stream()
                .map(SalesViews.SalesOrderLine::reservedQuantityBase)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal reservedAfter = orders.lines(companyId, id).stream()
                .map(SalesViews.SalesOrderLine::reservedQuantityBase)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        audit.record(AuditEvent.builder("RESERVE", "sales")
                .entity("sales_order", id, current.number())
                .change("reservedQuantityBase", reservedBefore.toPlainString(), reservedAfter.toPlainString())
                .detail("backorderQuantityBase", backorder.toPlainString())
                .build());
        return get(id);
    }

    /**
     * Cancels a draft or confirmed order; a confirmed one only while nothing was delivered or
     * invoiced. Releases its reservations and cancels its draft deliveries and invoices.
     */
    @Transactional
    public SalesViews.SalesOrderDetail cancel(UUID id, @Nullable String ifMatch, @Nullable String reason) {
        UUID companyId = CurrentContext.requireCompany();
        SalesViews.SalesOrder current = lock(companyId, id, ifMatch, SalesOrderStatus.Action.CANCEL);
        List<SalesViews.SalesOrderLine> lines = orders.lines(companyId, id);
        if (lines.stream()
                .anyMatch(l -> l.deliveredQuantityBase().signum() > 0
                        || l.invoicedQuantityBase().signum() > 0)) {
            throw new ApiException(
                    PlatformErrorCode.INVALID_STATE,
                    "Goods were delivered or invoiced on this order; close it instead of cancelling it.");
        }
        releaseReservations(companyId, lines);
        cancelDrafts(companyId, id, true);
        transition(current, SalesOrderStatus.Action.CANCEL, blankToEmpty(reason), null, reason);
        events.publishEvent(new SalesOrderCancelled(
                DomainEvents.metadata(SalesOrderCancelled.TYPE, SalesOrderCancelled.SCHEMA_VERSION, companyId, clock),
                id,
                current.number(),
                current.customerId(),
                current.currencyCode(),
                current.total(),
                reason));
        return get(id);
    }

    /**
     * Closes an order short: no more deliveries, the remaining reservations are released and draft
     * deliveries cancelled; invoices for what was delivered can still be posted. A confirmed order
     * with nothing delivered is cancelled instead, unless it has nothing to deliver or was invoiced.
     */
    @Transactional
    public SalesViews.SalesOrderDetail close(UUID id, @Nullable String ifMatch, @Nullable String reason) {
        UUID companyId = CurrentContext.requireCompany();
        SalesViews.SalesOrder current = lock(companyId, id, ifMatch, SalesOrderStatus.Action.CLOSE);
        List<SalesViews.SalesOrderLine> lines = orders.lines(companyId, id);
        if ("CONFIRMED".equals(current.status())
                && lines.stream().anyMatch(SalesViews.SalesOrderLine::stockable)
                && lines.stream().noneMatch(l -> l.invoicedQuantityBase().signum() > 0)) {
            throw new ApiException(
                    PlatformErrorCode.INVALID_STATE,
                    "Nothing has been delivered on this order yet; cancel it instead of closing it.");
        }
        releaseReservations(companyId, lines);
        cancelDrafts(companyId, id, false);
        transition(current, SalesOrderStatus.Action.CLOSE, null, blankToEmpty(reason), reason);
        return get(id);
    }

    // ------------------------------------------------- fulfilment (called under the order lock)

    /** The order locked {@code FOR UPDATE} for a delivery, return or invoice. */
    SalesViews.SalesOrder lockForFulfilment(UUID companyId, UUID id) {
        return orders.lock(companyId, id).orElseThrow(ApiException::notFound);
    }

    /** Moves the order along CONFIRMED / PARTIALLY_DELIVERED / DELIVERED after a delivery. */
    void deliveryProgress(UUID companyId, UUID orderId) {
        SalesViews.SalesOrder order = orders.find(companyId, orderId).orElseThrow();
        SalesOrderStatus from = SalesOrderStatus.valueOf(order.status());
        if (from.allows(SalesOrderStatus.Action.DELIVER)) {
            List<SalesViews.SalesOrderLine> stockable = orders.lines(companyId, orderId).stream()
                    .filter(SalesViews.SalesOrderLine::stockable)
                    .toList();
            boolean any =
                    stockable.stream().anyMatch(l -> l.deliveredQuantityBase().signum() > 0);
            boolean all = !stockable.isEmpty()
                    && stockable.stream()
                            .allMatch(l ->
                                    l.deliveredQuantityBase().compareTo(l.line().quantityBase()) >= 0);
            SalesOrderStatus to = from.delivered(any, all);
            if (to != from) {
                if (!orders.transition(
                        companyId, orderId, order.version(), context.actor(), to.name(), null, null, null)) {
                    throw new IllegalStateException("Sales order changed although it is locked");
                }
                audit.record(AuditEvent.builder("STATE_CHANGE", "sales")
                        .entity("sales_order", orderId, order.number())
                        .transition(from.name(), to.name())
                        .build());
            }
        }
        invoicingProgress(companyId, orderId);
    }

    /**
     * Updates the invoice status (SAL-5); a delivered (or delivery-free) order that is fully invoiced
     * closes itself.
     */
    void invoicingProgress(UUID companyId, UUID orderId) {
        SalesViews.SalesOrder order = orders.find(companyId, orderId).orElseThrow();
        List<SalesViews.SalesOrderLine> lines = orders.lines(companyId, orderId);
        SalesOrderStatus status = SalesOrderStatus.valueOf(order.status());
        boolean deliveryDone = status == SalesOrderStatus.DELIVERED
                || status == SalesOrderStatus.CLOSED
                || (status == SalesOrderStatus.CONFIRMED
                        && lines.stream().noneMatch(SalesViews.SalesOrderLine::stockable));
        boolean any = lines.stream().anyMatch(l -> l.invoicedQuantityBase().signum() > 0);
        boolean all = (deliveryDone || "ORDERED".equals(order.invoicePolicy()))
                && lines.stream().allMatch(l -> l.invoicedQuantityBase().compareTo(invoiceTarget(order, l)) >= 0);
        String invoicing = SalesOrderStatus.Invoicing.of(any, all).name();
        boolean close = all
                && deliveryDone
                && status != SalesOrderStatus.CLOSED
                && status.allows(SalesOrderStatus.Action.CLOSE);
        if (invoicing.equals(order.invoiceStatus()) && !close) {
            return;
        }
        String to = close ? SalesOrderStatus.CLOSED.name() : order.status();
        if (!orders.transition(
                companyId,
                orderId,
                order.version(),
                context.actor(),
                to,
                invoicing,
                null,
                close ? "Fully delivered and invoiced" : null)) {
            throw new IllegalStateException("Sales order changed although it is locked");
        }
        if (close) {
            releaseReservations(companyId, lines);
        }
        AuditEvent.Builder event = AuditEvent.builder("STATE_CHANGE", "sales")
                .entity("sales_order", orderId, order.number())
                .change("invoiceStatus", order.invoiceStatus(), invoicing);
        if (close) {
            event.transition(order.status(), to);
        }
        audit.record(event.build());
    }

    /**
     * SAL-5: what a line is invoiced up to. Stockable lines under the DELIVERED policy: delivered −
     * returned; otherwise ordered − returned.
     */
    static BigDecimal invoiceTarget(SalesViews.SalesOrder order, SalesViews.SalesOrderLine line) {
        BigDecimal basis = "DELIVERED".equals(order.invoicePolicy()) && line.stockable()
                ? line.deliveredQuantityBase()
                : line.line().quantityBase();
        return basis.subtract(line.returnedQuantityBase()).max(BigDecimal.ZERO);
    }

    /** The quantity of a line that may still be invoiced (SAL-5). */
    static BigDecimal invoiceable(SalesViews.SalesOrder order, SalesViews.SalesOrderLine line) {
        return invoiceTarget(order, line).subtract(line.invoicedQuantityBase()).max(BigDecimal.ZERO);
    }

    SalesViews.SalesOrder visible(SalesViews.SalesOrder order) {
        if (!context.canSeeBranch(order.branchId())) {
            throw ApiException.notFound();
        }
        return order;
    }

    // ------------------------------------------------------------------------------ helpers

    private record Prepared(
            SalesOrderRepository.Header header, List<com.erp.sales.persistence.QuotationRepository.NewLine> lines) {}

    private Prepared prepare(
            SalesCommands.SalesOrder command,
            SalesViews.@Nullable SalesOrder current,
            @Nullable UUID quotationId,
            @Nullable Boolean pricesIncludeTax,
            List<SalesViews.Line> approved) {
        UUID companyId = CurrentContext.requireCompany();
        CompanyProfile profile = context.profile();
        LocalDate orderDate = command.orderDate() != null ? command.orderDate() : context.today(profile);
        List<FieldViolation> violations = new ArrayList<>();
        if (command.requestedDate() != null && command.requestedDate().isBefore(orderDate)) {
            violations.add(FieldViolation.atPointer(
                    "/requestedDate", "BEFORE_ORDER_DATE", "must not be before the order date"));
        }
        String policy = command.invoicePolicy() != null
                ? command.invoicePolicy()
                : settings.current(companyId).defaultInvoicePolicy();
        SalesDrafts.Draft draft = drafts.build(
                new SalesDrafts.Input(
                        command.customerId(),
                        current == null ? null : current.customerId(),
                        command.warehouseId(),
                        orderDate,
                        command.currencyCode(),
                        command.priceListId(),
                        pricesIncludeTax,
                        command.paymentTermsId(),
                        command.lines(),
                        approved,
                        violations),
                profile);
        boolean sameCustomer = current != null && current.customerId().equals(command.customerId());
        Map<String, Object> shipping =
                sameCustomer && !current.shippingAddress().isEmpty()
                        ? current.shippingAddress()
                        : drafts.address(command.customerId(), "SHIPPING");
        Map<String, Object> billing = sameCustomer && !current.billingAddress().isEmpty()
                ? current.billingAddress()
                : drafts.address(command.customerId(), "BILLING");
        return new Prepared(
                new SalesOrderRepository.Header(
                        command.customerId(),
                        quotationId,
                        draft.branchId(),
                        command.warehouseId(),
                        orderDate,
                        command.requestedDate(),
                        command.customerReference(),
                        draft.currencyCode(),
                        draft.priceListId(),
                        draft.pricesIncludeTax(),
                        draft.paymentTermsId(),
                        policy,
                        shipping,
                        billing,
                        draft.totals().subtotal(),
                        draft.totals().taxTotal(),
                        draft.totals().total(),
                        command.notes()),
                draft.lines());
    }

    private SalesViews.CreditCheckResult creditCheck(
            SalesViews.SalesOrder order, CustomerInfo customer, BigDecimal rate, CompanyProfile profile) {
        UUID companyId = order.companyId();
        int scale = profile.baseCurrencyMinorUnits();
        BigDecimal ar = receivables.openReceivablesBase(companyId, customer.partnerId());
        BigDecimal open = orders.uninvoicedOrdersBase(companyId, customer.partnerId(), order.id(), scale);
        BigDecimal totalBase = context.baseRounding(profile).round(order.total().multiply(rate));
        CreditCheck.Mode mode =
                CreditCheck.Mode.valueOf(settings.current(companyId).creditCheckMode());
        CreditCheck.Outcome outcome =
                CreditCheck.evaluate(mode, customer.creditLimit(), customer.onHold(), ar.add(open), totalBase);
        return new SalesViews.CreditCheckResult(
                order.id(),
                customer.partnerId(),
                mode.name(),
                customer.creditLimit(),
                customer.onHold(),
                ar,
                open,
                totalBase,
                rate,
                outcome.name());
    }

    private static ApiException creditRefusal(
            CustomerInfo customer, SalesViews.CreditCheckResult check, CreditCheck.Outcome outcome) {
        Map<String, Object> details = new java.util.LinkedHashMap<>();
        details.put("exposureBase", check.exposureBase().toPlainString());
        details.put("orderTotalBase", check.orderTotalBase().toPlainString());
        if (check.creditLimit() != null) {
            details.put("creditLimit", check.creditLimit().toPlainString());
        }
        if (outcome == CreditCheck.Outcome.BLOCKED_ON_HOLD) {
            return new ApiException(
                    SalesErrorCode.PARTNER_ON_HOLD,
                    "Customer " + customer.code() + " is on hold; confirming needs a credit override.",
                    List.of(new FieldViolation(
                            "/overrideCredit",
                            null,
                            SalesErrorCode.PARTNER_ON_HOLD.code(),
                            "The customer is on hold",
                            details)));
        }
        return new ApiException(
                SalesErrorCode.CREDIT_LIMIT_EXCEEDED,
                "The order exceeds the credit limit of customer " + customer.code() + ".",
                List.of(new FieldViolation(
                        "/overrideCredit",
                        null,
                        SalesErrorCode.CREDIT_LIMIT_EXCEEDED.code(),
                        "Exposure plus this order exceeds the credit limit",
                        details)));
    }

    /**
     * Reserves what is open and not yet reserved on the stockable lines (partial reservations
     * allowed); returns the total backorder quantity.
     */
    private BigDecimal reserveOpen(SalesViews.SalesOrder order, @Nullable String number) {
        UUID companyId = order.companyId();
        BigDecimal backorder = BigDecimal.ZERO;
        for (SalesViews.SalesOrderLine line : orders.lines(companyId, order.id())) {
            if (!line.stockable()) {
                continue;
            }
            BigDecimal open = line.openToDeliverBase().subtract(line.reservedQuantityBase());
            if (open.signum() <= 0) {
                continue;
            }
            ReservationResult result = inventory.reserve(new ReservationRequest(
                    new SourceRef(SOURCE_MODULE, SOURCE_TYPE, order.id(), number),
                    line.id(),
                    line.line().variantId(),
                    order.warehouseId(),
                    open,
                    true));
            backorder = backorder.add(result.backorderQuantityBase());
            if (result.reservedQuantityBase().signum() > 0) {
                orders.updateLine(
                        companyId,
                        line.id(),
                        result.reservationId(),
                        line.reservedQuantityBase().add(result.reservedQuantityBase()),
                        line.deliveredQuantityBase(),
                        line.returnedQuantityBase(),
                        line.invoicedQuantityBase());
            }
        }
        return backorder;
    }

    /** Releases what the lines still have reserved. */
    private void releaseReservations(UUID companyId, List<SalesViews.SalesOrderLine> lines) {
        for (SalesViews.SalesOrderLine line : lines) {
            if (line.reservationId() != null && line.reservedQuantityBase().signum() > 0) {
                inventory.release(line.reservationId(), line.reservedQuantityBase());
                orders.updateLine(
                        companyId,
                        line.id(),
                        line.reservationId(),
                        BigDecimal.ZERO,
                        line.deliveredQuantityBase(),
                        line.returnedQuantityBase(),
                        line.invoicedQuantityBase());
            }
        }
    }

    private void cancelDrafts(UUID companyId, UUID orderId, boolean includingInvoices) {
        UUID actor = context.actor();
        for (SalesViews.Delivery draft : deliveries.draftsOfOrder(companyId, orderId)) {
            deliveries.markCancelled(companyId, draft.id(), draft.version(), actor);
            audit.record(AuditEvent.builder("STATE_CHANGE", "sales")
                    .entity("delivery", draft.id(), null)
                    .transition("DRAFT", "CANCELLED")
                    .detail("reason", "Sales order no longer deliverable")
                    .build());
        }
        if (includingInvoices) {
            for (SalesViews.Invoice draft : invoices.draftsOfOrder(companyId, orderId)) {
                invoices.markCancelled(companyId, draft.id(), draft.version(), actor);
                audit.record(AuditEvent.builder("STATE_CHANGE", "sales")
                        .entity("invoice", draft.id(), null)
                        .transition("DRAFT", "CANCELLED")
                        .detail("reason", "Sales order cancelled")
                        .build());
            }
        }
    }

    private SalesViews.SalesOrder lock(
            UUID companyId, UUID id, @Nullable String ifMatch, SalesOrderStatus.Action action) {
        SalesViews.SalesOrder current = visible(orders.lock(companyId, id).orElseThrow(ApiException::notFound));
        EntityTags.requireMatch(ifMatch, current.version());
        if (!SalesOrderStatus.valueOf(current.status()).allows(action)) {
            throw new ApiException(
                    PlatformErrorCode.INVALID_STATE,
                    "A " + current.status() + " sales order does not allow "
                            + action.name().toLowerCase(Locale.ROOT) + ".");
        }
        return current;
    }

    private void transition(
            SalesViews.SalesOrder current,
            SalesOrderStatus.Action action,
            @Nullable String cancelReason,
            @Nullable String closeReason,
            @Nullable String detail) {
        SalesOrderStatus from = SalesOrderStatus.valueOf(current.status());
        SalesOrderStatus to = from.apply(action);
        if (!orders.transition(
                current.companyId(),
                current.id(),
                current.version(),
                context.actor(),
                to.name(),
                null,
                cancelReason,
                closeReason)) {
            throw new ApiException(PlatformErrorCode.VERSION_CONFLICT, "The sales order was modified concurrently.");
        }
        AuditEvent.Builder event = AuditEvent.builder("STATE_CHANGE", "sales")
                .entity("sales_order", current.id(), current.number())
                .transition(from.name(), to.name());
        if (detail != null && !detail.isBlank()) {
            event.detail("reason", detail);
        }
        audit.record(event.build());
    }

    private static String blankToEmpty(@Nullable String reason) {
        return reason == null || reason.isBlank() ? "" : reason.strip();
    }
}
