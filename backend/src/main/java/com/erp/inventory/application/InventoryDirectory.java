package com.erp.inventory.application;

import com.erp.inventory.api.InventoryFacade;
import com.erp.inventory.domain.MovementType;
import com.erp.inventory.domain.UomConversion;
import com.erp.inventory.persistence.CategoryRepository;
import com.erp.inventory.persistence.MovementRepository;
import com.erp.inventory.persistence.ProductRepository;
import com.erp.inventory.persistence.ReferenceRepository;
import com.erp.inventory.persistence.ReservationRepository;
import com.erp.inventory.persistence.StockRepository;
import com.erp.inventory.persistence.UomRepository;
import com.erp.inventory.persistence.VariantRepository;
import com.erp.inventory.persistence.WarehouseRepository;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditPort;
import com.erp.platform.context.CurrentContext;
import com.erp.platform.web.ApiException;
import com.erp.platform.web.FieldViolation;
import com.erp.platform.web.PlatformErrorCode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** {@link InventoryFacade} implementation: document-driven stock changes and reservations. */
@Service
class InventoryDirectory implements InventoryFacade {

    private final MovementRepository movements;
    private final WarehouseRepository warehouses;
    private final VariantRepository variants;
    private final ProductRepository products;
    private final UomRepository uoms;
    private final StockRepository stock;
    private final ReservationRepository reservations;
    private final MovementLineResolver resolver;
    private final PostingEngine engine;
    private final ReferenceRepository references;
    private final CategoryRepository categories;
    private final AuditPort audit;

    InventoryDirectory(
            MovementRepository movements,
            WarehouseRepository warehouses,
            VariantRepository variants,
            ProductRepository products,
            UomRepository uoms,
            StockRepository stock,
            ReservationRepository reservations,
            MovementLineResolver resolver,
            PostingEngine engine,
            ReferenceRepository references,
            CategoryRepository categories,
            AuditPort audit) {
        this.movements = movements;
        this.warehouses = warehouses;
        this.variants = variants;
        this.products = products;
        this.uoms = uoms;
        this.stock = stock;
        this.reservations = reservations;
        this.resolver = resolver;
        this.engine = engine;
        this.references = references;
        this.categories = categories;
        this.audit = audit;
    }

    @Override
    @Transactional
    public PostedMovement receive(StockInRequest request) {
        return stockIn(MovementType.PURCHASE_RECEIPT, request);
    }

    @Override
    @Transactional
    public PostedMovement returnFromCustomer(StockInRequest request) {
        return stockIn(MovementType.SALES_RETURN, request);
    }

    @Override
    @Transactional
    public PostedMovement issue(StockOutRequest request) {
        return stockOut(MovementType.SALES_ISSUE, request);
    }

    @Override
    @Transactional
    public PostedMovement returnToSupplier(StockOutRequest request) {
        return stockOut(MovementType.PURCHASE_RETURN, request);
    }

    private PostedMovement stockIn(MovementType type, StockInRequest request) {
        UUID companyId = CurrentContext.requireCompany();
        UUID stockLocation = defaultLocation(companyId, request.warehouseId());
        List<InventoryCommands.Line> lines = request.lines().stream()
                .map(l -> new InventoryCommands.Line(
                        l.variantId(),
                        null,
                        l.locationId() != null ? l.locationId() : stockLocation,
                        l.quantity(),
                        l.uomId(),
                        l.unitCostBase(),
                        null,
                        null,
                        l.sourceLineId(),
                        null))
                .toList();
        return createAndPost(
                type,
                request.source(),
                request.movementDate(),
                request.warehouseId(),
                request.partnerId(),
                request.notes(),
                lines);
    }

    private PostedMovement stockOut(MovementType type, StockOutRequest request) {
        UUID companyId = CurrentContext.requireCompany();
        UUID stockLocation = defaultLocation(companyId, request.warehouseId());
        List<InventoryCommands.Line> lines = request.lines().stream()
                .map(l -> new InventoryCommands.Line(
                        l.variantId(),
                        l.locationId() != null ? l.locationId() : stockLocation,
                        null,
                        l.quantity(),
                        l.uomId(),
                        null,
                        l.referenceUnitCostBase(),
                        l.reservationId(),
                        l.sourceLineId(),
                        null))
                .toList();
        return createAndPost(
                type,
                request.source(),
                request.movementDate(),
                request.warehouseId(),
                request.partnerId(),
                request.notes(),
                lines);
    }

    private PostedMovement createAndPost(
            MovementType type,
            SourceRef source,
            java.time.LocalDate date,
            UUID warehouseId,
            @Nullable UUID partnerId,
            @Nullable String notes,
            List<InventoryCommands.Line> lines) {
        UUID companyId = CurrentContext.requireCompany();
        if (warehouses.find(companyId, warehouseId, null).isEmpty()) {
            throw ApiException.validationFailed(
                    "The warehouse is unknown.",
                    List.of(FieldViolation.atPointer(
                            "/warehouseId", "UNKNOWN_WAREHOUSE", "is not a warehouse of the company")));
        }
        // Checked up front for a clear error; the unique index still decides concurrent duplicates.
        if (movements.existsForSource(companyId, source.module(), source.type(), source.id())) {
            throw new ApiException(
                    InventoryErrorCode.DUPLICATE_SOURCE_DOCUMENT, "The source document already has a stock movement.");
        }
        List<InventoryCommands.ResolvedLine> resolved = resolver.resolve(companyId, type, warehouseId, null, lines);
        UUID actor = actor();
        UUID id = movements.insert(
                companyId,
                new InventoryCommands.Movement(
                        type,
                        date,
                        warehouseId,
                        null,
                        partnerId,
                        null,
                        source.module(),
                        source.type(),
                        source.id(),
                        source.number(),
                        null,
                        null,
                        notes,
                        lines),
                actor);
        movements.insertLines(companyId, id, resolved, actor);
        PostingEngine.Result result =
                engine.post(movements.lockForChange(companyId, id).orElseThrow());
        // One posted line per request line, in order (receipts and issues have one location each).
        List<PostedLine> posted = new java.util.ArrayList<>();
        for (int i = 0; i < result.lines().size(); i++) {
            var l = result.lines().get(i);
            InventoryCommands.Line requested = lines.get(i);
            UUID location = requested.toLocationId() != null ? requested.toLocationId() : requested.fromLocationId();
            posted.add(new PostedLine(
                    l.lineId(),
                    l.sourceLineId(),
                    l.variantId(),
                    java.util.Objects.requireNonNull(location),
                    l.quantityBase(),
                    l.unitCostBase(),
                    l.valueBase()));
        }
        return new PostedMovement(id, result.number(), List.copyOf(posted));
    }

    // ----------------------------------------------------------------------- reservations

    @Override
    @Transactional
    public ReservationResult reserve(ReservationRequest request) {
        UUID companyId = CurrentContext.requireCompany();
        BigDecimal requested = request.quantityBase();
        if (requested.signum() <= 0 || requested.stripTrailingZeros().scale() > UomConversion.QUANTITY_SCALE) {
            throw ApiException.validationFailed(
                    "The reservation is invalid.",
                    List.of(FieldViolation.atPointer(
                            "/quantityBase", "INVALID_VALUE", "must be positive with at most 6 decimals")));
        }
        InventoryViews.StockItem item =
                variants.items(companyId, List.of(request.variantId())).get(request.variantId());
        var warehouse = warehouses.find(companyId, request.warehouseId(), null);
        if (item == null
                || !"STOCKABLE".equals(item.productType())
                || !item.usable()
                || warehouse.isEmpty()
                || !warehouse.get().active()) {
            throw ApiException.validationFailed(
                    "The reservation is invalid.",
                    List.of(FieldViolation.atPointer(
                            "/variantId", "INVALID_VALUE", "must be an active stock item in an active warehouse")));
        }
        StockRepository.WarehouseKey key = new StockRepository.WarehouseKey(request.variantId(), request.warehouseId());
        StockRepository.WarehouseStock ws =
                stock.lockWarehouseStock(companyId, List.of(key)).get(key);
        Optional<InventoryViews.Reservation> existing = reservations.lockActiveForSourceLine(
                companyId, request.source().module(), request.sourceLineId(), request.warehouseId());
        BigDecimal available = ws.onHand().subtract(ws.reserved());
        BigDecimal reserved = requested.min(available.max(BigDecimal.ZERO));
        if (reserved.compareTo(requested) < 0 && !request.allowPartial()) {
            throw new ApiException(
                    InventoryErrorCode.INSUFFICIENT_STOCK,
                    "Not enough available stock to reserve.",
                    List.of(new FieldViolation(
                            "/quantityBase",
                            null,
                            InventoryErrorCode.INSUFFICIENT_STOCK.code(),
                            "Requested " + requested.stripTrailingZeros().toPlainString() + ", available "
                                    + available
                                            .max(BigDecimal.ZERO)
                                            .stripTrailingZeros()
                                            .toPlainString(),
                            Map.of(
                                    "variantId", request.variantId().toString(),
                                    "warehouseId", request.warehouseId().toString(),
                                    "requested", requested.stripTrailingZeros().toPlainString(),
                                    "available",
                                            available
                                                    .max(BigDecimal.ZERO)
                                                    .stripTrailingZeros()
                                                    .toPlainString()))));
        }
        BigDecimal backorder = requested.subtract(reserved);
        if (reserved.signum() == 0) {
            return new ReservationResult(
                    existing.map(InventoryViews.Reservation::id).orElse(null), BigDecimal.ZERO, backorder);
        }
        UUID actor = actor();
        stock.updateWarehouseStock(companyId, key, ws.onHand(), ws.reserved().add(reserved));
        UUID reservationId;
        if (existing.isPresent()) {
            reservationId = existing.get().id();
            reservations.update(
                    companyId, reservationId, existing.get().quantityBase().add(reserved), "ACTIVE", actor);
        } else {
            reservationId = reservations.insert(
                    companyId,
                    request.variantId(),
                    request.warehouseId(),
                    reserved,
                    request.source().module(),
                    request.source().type(),
                    request.source().id(),
                    request.sourceLineId(),
                    actor);
        }
        audit.record(AuditEvent.builder("RESERVE", "inventory")
                .entity("stock_reservation", reservationId, item.sku())
                .detail("warehouseId", request.warehouseId())
                .detail("quantityBase", reserved.toPlainString())
                .detail(
                        "source",
                        request.source().module() + ":" + request.source().type() + ":"
                                + request.source().id())
                .build());
        return new ReservationResult(reservationId, reserved, backorder);
    }

    @Override
    @Transactional
    public void release(UUID reservationId, @Nullable BigDecimal quantityBase) {
        UUID companyId = CurrentContext.requireCompany();
        InventoryViews.Reservation found =
                reservations.find(companyId, reservationId).orElseThrow(ApiException::notFound);
        StockRepository.WarehouseKey key = new StockRepository.WarehouseKey(found.variantId(), found.warehouseId());
        StockRepository.WarehouseStock ws =
                stock.lockWarehouseStock(companyId, List.of(key)).get(key);
        InventoryViews.Reservation reservation =
                reservations.lock(companyId, List.of(reservationId)).get(reservationId);
        if (!"ACTIVE".equals(reservation.status())) {
            throw new ApiException(PlatformErrorCode.INVALID_STATE, "The reservation is no longer active.");
        }
        BigDecimal release = quantityBase == null ? reservation.quantityBase() : quantityBase;
        if (release.signum() <= 0 || release.compareTo(reservation.quantityBase()) > 0) {
            throw ApiException.validationFailed(
                    "The release is invalid.",
                    List.of(FieldViolation.atPointer(
                            "/quantityBase", "INVALID_VALUE", "must be positive and at most the reserved quantity")));
        }
        UUID actor = actor();
        BigDecimal remaining = reservation.quantityBase().subtract(release);
        stock.updateWarehouseStock(companyId, key, ws.onHand(), ws.reserved().subtract(release));
        reservations.update(
                companyId, reservationId, remaining, remaining.signum() == 0 ? "RELEASED" : "ACTIVE", actor);
        audit.record(AuditEvent.builder("RELEASE", "inventory")
                .entity("stock_reservation", reservationId, null)
                .detail("quantityBase", release.toPlainString())
                .build());
    }

    // ---------------------------------------------------------------------------- queries

    @Override
    @Transactional(readOnly = true)
    public Availability availability(UUID variantId, UUID warehouseId) {
        StockRepository.WarehouseStock ws =
                stock.warehouseStock(CurrentContext.requireCompany(), variantId, warehouseId);
        return new Availability(
                variantId, warehouseId, ws.onHand(), ws.reserved(), ws.onHand().subtract(ws.reserved()));
    }

    @Override
    @Transactional(readOnly = true)
    public BigDecimal convertQuantity(UUID variantId, BigDecimal quantity, UUID uomId) {
        UUID companyId = CurrentContext.requireCompany();
        InventoryViews.StockItem item =
                variants.items(companyId, List.of(variantId)).get(variantId);
        if (item == null) {
            throw ApiException.notFound();
        }
        var units = uoms.units(List.of(uomId, item.baseUomId()));
        if (!units.containsKey(uomId)) {
            throw ApiException.validationFailed(
                    "Unknown unit.",
                    List.of(FieldViolation.atPointer("/uomId", "UNKNOWN_UOM", "is not a unit of measure")));
        }
        UomConversion.Result result = UomConversion.toBase(
                quantity,
                units.get(uomId),
                units.get(item.baseUomId()),
                products.conversionFactors(companyId, item.productId()));
        if (!result.ok()) {
            throw new ApiException(
                    result.failure() == UomConversion.Failure.NOT_CONVERTIBLE
                            ? InventoryErrorCode.UOM_NOT_CONVERTIBLE
                            : PlatformErrorCode.VALIDATION_FAILED,
                    "The quantity cannot be expressed in the base unit (" + result.failure() + ").");
        }
        return result.quantityBase();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<VariantInfo> variantInfo(UUID variantId) {
        UUID companyId = CurrentContext.requireCompany();
        return variants.find(companyId, variantId)
                .flatMap(v -> products.find(companyId, v.productId())
                        .map(p -> new VariantInfo(
                                v.id(),
                                p.id(),
                                v.sku(),
                                v.name(),
                                p.productType(),
                                v.active() && p.active() ? "ACTIVE" : "ARCHIVED",
                                p.categoryId(),
                                p.baseUomId(),
                                p.purchaseUomId(),
                                p.salesUomId(),
                                p.purchasable(),
                                p.sellable(),
                                p.salesTaxCodeId(),
                                p.purchaseTaxCodeId())));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<WarehouseInfo> warehouse(UUID warehouseId) {
        return warehouses
                .find(CurrentContext.requireCompany(), warehouseId, null)
                .map(w -> new WarehouseInfo(w.id(), w.code(), w.name(), w.branchId(), w.active()));
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> categoryAncestry(UUID categoryId) {
        UUID companyId = CurrentContext.requireCompany();
        List<UUID> ancestry = new ArrayList<>();
        UUID current = categoryId;
        while (current != null && ancestry.size() < 100) {
            InventoryViews.Category category =
                    categories.find(companyId, current).orElse(null);
            if (category == null) {
                break;
            }
            ancestry.add(category.id());
            current = category.parentId();
        }
        return List.copyOf(ancestry);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ReasonCodeInfo> reasonCode(UUID reasonCodeId) {
        return references
                .findReasonCode(CurrentContext.requireCompany(), reasonCodeId)
                .map(r -> new ReasonCodeInfo(r.id(), r.code(), r.appliesTo(), r.active()));
    }

    @Override
    @Transactional(readOnly = true)
    public BigDecimal valuationTotalBase() {
        return stock.valuationTotal(CurrentContext.requireCompany());
    }

    @Override
    @Transactional(readOnly = true)
    public BigDecimal overReceiptTolerancePercent() {
        return references
                .settings(CurrentContext.requireCompany())
                .map(InventoryViews.Settings::overReceiptTolerancePercent)
                .orElse(InventoryReferenceService.DEFAULTS.overReceiptTolerancePercent());
    }

    private UUID defaultLocation(UUID companyId, UUID warehouseId) {
        return warehouses
                .locationByCode(companyId, warehouseId, WarehouseService.STOCK_LOCATION)
                .map(InventoryViews.Location::id)
                .orElseThrow(() -> ApiException.validationFailed(
                        "The warehouse is unknown.",
                        List.of(FieldViolation.atPointer(
                                "/warehouseId", "UNKNOWN_WAREHOUSE", "is not a warehouse of the company"))));
    }

    private static @Nullable UUID actor() {
        return CurrentContext.get()
                .map(c -> c.actor() == null ? null : c.actor().userId())
                .orElse(null);
    }
}
