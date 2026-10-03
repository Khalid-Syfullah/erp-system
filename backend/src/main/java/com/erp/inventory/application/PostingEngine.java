package com.erp.inventory.application;

import com.erp.inventory.InventoryPermissions;
import com.erp.inventory.domain.MovementStatus;
import com.erp.inventory.domain.MovementType;
import com.erp.inventory.domain.Valuation;
import com.erp.inventory.events.StockMovementPosted;
import com.erp.inventory.persistence.MovementRepository;
import com.erp.inventory.persistence.ReferenceRepository;
import com.erp.inventory.persistence.ReservationRepository;
import com.erp.inventory.persistence.StockRepository;
import com.erp.inventory.persistence.StockRepository.LedgerRow;
import com.erp.inventory.persistence.StockRepository.LocationKey;
import com.erp.inventory.persistence.StockRepository.WarehouseKey;
import com.erp.inventory.persistence.StockRepository.WarehouseStock;
import com.erp.inventory.persistence.VariantRepository;
import com.erp.inventory.persistence.WarehouseRepository;
import com.erp.org.api.CompanyProfile;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditPort;
import com.erp.platform.context.CurrentContext;
import com.erp.platform.context.RequestContext;
import com.erp.platform.events.DomainEvents;
import com.erp.platform.money.RoundingPolicy;
import com.erp.platform.numbering.DocumentNumberService;
import com.erp.platform.numbering.FiscalYears;
import com.erp.platform.web.ApiException;
import com.erp.platform.web.FieldViolation;
import com.erp.platform.web.PlatformErrorCode;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Posts a draft stock movement (PRODUCT_SPEC.md §6.3–§6.5). In one transaction, in the global lock
 * order of ARCHITECTURE.md §6.2:
 *
 * <ol>
 *   <li>the movement header (locked by the caller), variants, locations and warehouses {@code FOR SHARE};
 *   <li>warehouse stock, reservations, location balances and item valuations {@code FOR UPDATE}, each
 *       in key order;
 *   <li>checks: no negative stock at any location or warehouse (INV-1), reserved stock stays reserved
 *       unless a delivery consumes its own reservation (INV-2), valuation invariants;
 *   <li>moving-average valuation line by line (INV-4), the append-only ledger rows (one per location
 *       affected), the balances and the valuation (INV-5);
 *   <li>the gapless number — the last lock — and the {@code POSTED} state;
 *   <li>the audit record and {@code inventory.stock_movement.posted}.
 * </ol>
 *
 * Any failure rolls the whole posting back: no partial transfer, no half-applied lines, no number.
 */
@Component
class PostingEngine {

    static final String DOCUMENT_TYPE = "STOCK_MOVEMENT";

    /** The valued outcome of one movement line. */
    record PostedLine(
            UUID lineId,
            @Nullable UUID sourceLineId,
            UUID variantId,
            BigDecimal quantityBase,
            BigDecimal unitCostBase,
            BigDecimal valueBase) {}

    record Result(String number, List<PostedLine> lines) {}

    private final MovementRepository movements;
    private final VariantRepository variants;
    private final WarehouseRepository warehouses;
    private final StockRepository stock;
    private final ReservationRepository reservations;
    private final ReferenceRepository references;
    private final InventoryContext context;
    private final DocumentNumberService numbering;
    private final AuditPort audit;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    PostingEngine(
            MovementRepository movements,
            VariantRepository variants,
            WarehouseRepository warehouses,
            StockRepository stock,
            ReservationRepository reservations,
            ReferenceRepository references,
            InventoryContext context,
            DocumentNumberService numbering,
            AuditPort audit,
            ApplicationEventPublisher events,
            Clock clock) {
        this.movements = movements;
        this.variants = variants;
        this.warehouses = warehouses;
        this.stock = stock;
        this.reservations = reservations;
        this.references = references;
        this.context = context;
        this.numbering = numbering;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
    }

    /** @param movement the draft, already locked {@code FOR UPDATE} by the caller */
    @Transactional(propagation = Propagation.MANDATORY)
    Result post(InventoryViews.Movement movement) {
        if (!movement.status().allows(MovementStatus.Action.POST)) {
            throw new ApiException(PlatformErrorCode.INVALID_STATE, "Only draft movements can be posted.");
        }
        UUID companyId = movement.companyId();
        CompanyProfile profile = context.profile(companyId);
        RoundingPolicy rounding = context.rounding(profile);
        if (movement.movementDate().isAfter(context.today(profile))) {
            throw ApiException.validationFailed(
                    "The movement is dated in the future.",
                    List.of(FieldViolation.atPointer("/movementDate", "IN_FUTURE", "must not be after today")));
        }
        List<InventoryViews.MovementLine> lines = movements.lines(companyId, movement.id());
        if (lines.isEmpty()) {
            throw ApiException.validationFailed(
                    "The movement has no lines.",
                    List.of(FieldViolation.atPointer("/lines", "SIZE", "must not be empty")));
        }
        boolean reversal = movement.type() == MovementType.REVERSAL;
        Map<UUID, List<InventoryViews.LedgerEntry>> original = reversal
                ? stock.ledgerOfMovement(companyId, movement.reversalOfId()).stream()
                        .collect(Collectors.groupingBy(
                                InventoryViews.LedgerEntry::movementLineId, LinkedHashMap::new, Collectors.toList()))
                : Map.of();

        // 1. Reference data, shared-locked so that archiving or deactivation waits for this posting.
        Set<UUID> variantIds =
                lines.stream().map(InventoryViews.MovementLine::variantId).collect(Collectors.toSet());
        Set<UUID> locationIds = new LinkedHashSet<>();
        lines.forEach(l -> {
            if (l.fromLocationId() != null) {
                locationIds.add(l.fromLocationId());
            }
            if (l.toLocationId() != null) {
                locationIds.add(l.toLocationId());
            }
        });
        original.values().forEach(rows -> rows.forEach(r -> locationIds.add(r.locationId())));
        Map<UUID, InventoryViews.StockItem> items = variants.lockItems(companyId, variantIds);
        Map<UUID, InventoryViews.Location> locations = warehouses.lockLocationsForUse(companyId, locationIds);
        Set<UUID> warehouseIds = locations.values().stream()
                .map(InventoryViews.Location::warehouseId)
                .collect(Collectors.toSet());
        Map<UUID, InventoryViews.Warehouse> warehouseRows = warehouses.lockForUse(companyId, warehouseIds);
        List<FieldViolation> invalid = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            InventoryViews.MovementLine line = lines.get(i);
            InventoryViews.StockItem item = items.get(line.variantId());
            if (item == null || !"STOCKABLE".equals(item.productType()) || (!reversal && !item.usable())) {
                invalid.add(FieldViolation.atPointer(
                        "/lines/" + i + "/variantId", "INACTIVE", "is not an active stock item"));
            }
            for (UUID locationId : new UUID[] {line.fromLocationId(), line.toLocationId()}) {
                if (locationId == null) {
                    continue;
                }
                InventoryViews.Location location = locations.get(locationId);
                InventoryViews.Warehouse warehouse =
                        location == null ? null : warehouseRows.get(location.warehouseId());
                if (!reversal && (location == null || !location.active() || warehouse == null || !warehouse.active())) {
                    invalid.add(FieldViolation.atPointer(
                            "/lines/" + i, "INACTIVE", "uses an inactive location or warehouse"));
                }
            }
        }
        if (!invalid.isEmpty()) {
            throw ApiException.validationFailed("The movement can no longer be posted as entered.", invalid);
        }

        // 2. Lock the stock rows in the global order.
        Set<WarehouseKey> warehouseKeys = new LinkedHashSet<>();
        Set<LocationKey> locationKeys = new LinkedHashSet<>();
        Set<UUID> reservationIds = new LinkedHashSet<>();
        for (InventoryViews.MovementLine line : lines) {
            List<UUID> touched = new ArrayList<>();
            if (reversal) {
                original.getOrDefault(line.reversalOfLineId(), List.of()).forEach(r -> touched.add(r.locationId()));
            } else {
                if (line.fromLocationId() != null) {
                    touched.add(line.fromLocationId());
                }
                if (line.toLocationId() != null) {
                    touched.add(line.toLocationId());
                }
            }
            for (UUID locationId : touched) {
                InventoryViews.Location location = locations.get(locationId);
                locationKeys.add(new LocationKey(line.variantId(), locationId, location.warehouseId()));
                if (location.type().countsTowardWarehouse()) {
                    warehouseKeys.add(new WarehouseKey(line.variantId(), location.warehouseId()));
                }
            }
            if (line.reservationId() != null) {
                reservationIds.add(line.reservationId());
            }
        }
        Map<WarehouseKey, WarehouseStock> warehouseState =
                new HashMap<>(stock.lockWarehouseStock(companyId, warehouseKeys));
        Map<UUID, InventoryViews.Reservation> reservationState =
                new HashMap<>(reservations.lock(companyId, reservationIds));
        Map<LocationKey, BigDecimal> balanceState = new HashMap<>(stock.lockBalances(companyId, locationKeys));
        Map<UUID, Valuation> valuationState = new HashMap<>(stock.lockValuations(companyId, variantIds));

        // 3. Apply line by line in memory; collect every stock violation.
        Simulation sim = new Simulation(
                movement, locations, warehouseState, reservationState, balanceState, valuationState, rounding);
        for (int i = 0; i < lines.size(); i++) {
            InventoryViews.MovementLine line = lines.get(i);
            if (reversal) {
                sim.mirror(i, line, original.getOrDefault(line.reversalOfLineId(), List.of()));
            } else if (line.fromLocationId() != null && line.toLocationId() != null) {
                sim.move(i, line);
            } else if (line.toLocationId() != null) {
                sim.in(i, line);
            } else {
                sim.out(i, line);
            }
        }
        sim.checkValuations(variantIds);
        sim.throwIfViolated();
        requireAdjustmentApproval(companyId, movement.type(), sim.ledger);

        // 4. Persist ledger, balances, valuations, reservations and computed line costs.
        UUID actor = actor();
        stock.appendLedger(companyId, sim.ledger, actor);
        sim.touchedWarehouses.forEach(k -> {
            WarehouseStock s = warehouseState.get(k);
            stock.updateWarehouseStock(companyId, k, s.onHand(), s.reserved());
        });
        sim.touchedBalances.forEach(k -> stock.updateBalance(companyId, k, balanceState.get(k)));
        sim.touchedValuations.forEach(v -> stock.updateValuation(companyId, v, valuationState.get(v)));
        sim.touchedReservations.forEach(id -> {
            InventoryViews.Reservation r = reservationState.get(id);
            reservations.update(
                    companyId, id, r.quantityBase(), r.quantityBase().signum() == 0 ? "CONSUMED" : "ACTIVE", actor);
        });
        for (PostedLine posted : sim.posted) {
            InventoryViews.MovementLine line = lines.stream()
                    .filter(l -> l.id().equals(posted.lineId()))
                    .findFirst()
                    .orElseThrow();
            if (line.unitCostBase() == null || line.unitCostBase().compareTo(posted.unitCostBase()) != 0) {
                movements.setLineCost(companyId, line.id(), posted.unitCostBase());
            }
        }

        // 5. Number (last lock) and state.
        String number = numbering.next(
                companyId, DOCUMENT_TYPE, FiscalYears.label(movement.movementDate(), profile.fiscalYearStartMonth()));
        if (!movements.markPosted(
                companyId, movement.id(), movement.version(), number, OffsetDateTime.now(clock), actor)) {
            throw new ApiException(PlatformErrorCode.VERSION_CONFLICT, "The movement was modified concurrently.");
        }

        // 6. Audit and event.
        BigDecimal value = sim.ledger.stream().map(LedgerRow::valueBase).reduce(BigDecimal.ZERO, BigDecimal::add);
        audit.record(AuditEvent.builder("POST", "inventory")
                .entity("stock_movement", movement.id(), number)
                .transition(MovementStatus.DRAFT.name(), MovementStatus.POSTED.name())
                .detail("movementType", movement.type().name())
                .detail("movementDate", movement.movementDate().toString())
                .detail("lines", lines.size())
                .detail("ledgerRows", sim.ledger.size())
                .detail("netValueBase", value.toPlainString())
                .build());
        events.publishEvent(event(movement, number, lines, sim.ledger, items, locations, warehouseRows, rounding));
        return new Result(number, sim.posted);
    }

    private void requireAdjustmentApproval(UUID companyId, MovementType type, List<LedgerRow> ledger) {
        if (!type.isAdjustment()) {
            return;
        }
        BigDecimal threshold = references
                .settings(companyId)
                .map(InventoryViews.Settings::adjustmentApprovalThreshold)
                .orElse(null);
        if (threshold == null) {
            return;
        }
        BigDecimal total = ledger.stream().map(r -> r.valueBase().abs()).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.compareTo(threshold) > 0 && !context.isGranted(InventoryPermissions.ADJUSTMENT_APPROVE)) {
            throw new ApiException(
                    InventoryErrorCode.ADJUSTMENT_APPROVAL_REQUIRED,
                    "The adjustment's value " + total.toPlainString() + " exceeds the approval threshold "
                            + threshold.toPlainString() + "; it must be posted by a user with "
                            + InventoryPermissions.ADJUSTMENT_APPROVE + ".");
        }
    }

    private StockMovementPosted event(
            InventoryViews.Movement movement,
            String number,
            List<InventoryViews.MovementLine> lines,
            List<LedgerRow> ledger,
            Map<UUID, InventoryViews.StockItem> items,
            Map<UUID, InventoryViews.Location> locations,
            Map<UUID, InventoryViews.Warehouse> warehouseRows,
            RoundingPolicy rounding) {
        Map<UUID, InventoryViews.MovementLine> byId =
                lines.stream().collect(Collectors.toMap(InventoryViews.MovementLine::id, l -> l));
        List<StockMovementPosted.Line> eventLines = ledger.stream()
                .map(r -> {
                    InventoryViews.MovementLine line = byId.get(r.movementLineId());
                    BigDecimal reference = line.referenceUnitCostBase() == null
                            ? null
                            : rounding.round(r.quantityBase().multiply(line.referenceUnitCostBase()));
                    return new StockMovementPosted.Line(
                            r.movementLineId(),
                            r.variantId(),
                            items.get(r.variantId()).categoryId(),
                            r.warehouseId(),
                            warehouseRows
                                    .get(locations.get(r.locationId()).warehouseId())
                                    .branchId(),
                            r.locationId(),
                            r.quantityBase(),
                            r.unitCostBase(),
                            r.valueBase(),
                            reference);
                })
                .toList();
        StockMovementPosted.SourceRef source = movement.sourceId() == null
                ? null
                : new StockMovementPosted.SourceRef(
                        movement.sourceModule(), movement.sourceType(), movement.sourceId(), movement.sourceNumber());
        return new StockMovementPosted(
                DomainEvents.metadata(
                        StockMovementPosted.TYPE, StockMovementPosted.SCHEMA_VERSION, movement.companyId(), clock),
                movement.id(),
                number,
                movement.type().name(),
                movement.movementDate(),
                source,
                movement.partnerId(),
                movement.reasonCodeId(),
                movement.reversalOfId(),
                eventLines);
    }

    private static @Nullable UUID actor() {
        RequestContext context = CurrentContext.get().orElse(null);
        return context == null || context.actor() == null
                ? null
                : context.actor().userId();
    }

    /** In-memory application of the lines against the locked state. */
    private static final class Simulation {

        final InventoryViews.Movement movement;
        final Map<UUID, InventoryViews.Location> locations;
        final Map<WarehouseKey, WarehouseStock> warehouseState;
        final Map<UUID, InventoryViews.Reservation> reservationState;
        final Map<LocationKey, BigDecimal> balanceState;
        final Map<UUID, Valuation> valuationState;
        final RoundingPolicy rounding;

        final List<LedgerRow> ledger = new ArrayList<>();
        final List<PostedLine> posted = new ArrayList<>();
        final List<FieldViolation> violations = new ArrayList<>();
        boolean insufficient;
        boolean reservedConflict;
        boolean valuationConflict;
        final Set<WarehouseKey> touchedWarehouses = new LinkedHashSet<>();
        final Set<LocationKey> touchedBalances = new LinkedHashSet<>();
        final Set<UUID> touchedValuations = new LinkedHashSet<>();
        final Set<UUID> touchedReservations = new LinkedHashSet<>();

        Simulation(
                InventoryViews.Movement movement,
                Map<UUID, InventoryViews.Location> locations,
                Map<WarehouseKey, WarehouseStock> warehouseState,
                Map<UUID, InventoryViews.Reservation> reservationState,
                Map<LocationKey, BigDecimal> balanceState,
                Map<UUID, Valuation> valuationState,
                RoundingPolicy rounding) {
            this.movement = movement;
            this.locations = locations;
            this.warehouseState = warehouseState;
            this.reservationState = reservationState;
            this.balanceState = balanceState;
            this.valuationState = valuationState;
            this.rounding = rounding;
        }

        void in(int index, InventoryViews.MovementLine line) {
            Valuation valuation = valuationState.get(line.variantId());
            BigDecimal cost = line.unitCostBase() != null ? line.unitCostBase() : valuation.averageCost();
            if (cost == null) {
                violations.add(FieldViolation.atPointer(
                        "/lines/" + index + "/unitCostBase",
                        "REQUIRED",
                        "is required: the item has no stock and therefore no average cost"));
                valuationConflict = true;
                return;
            }
            Valuation.Movement received = valuation.receive(line.quantityBase(), cost, rounding);
            setValuation(line.variantId(), received.after());
            change(line.variantId(), line.toLocationId(), line.quantityBase());
            ledger.add(row(line, line.toLocationId(), line.quantityBase(), cost, received.value()));
            posted.add(new PostedLine(
                    line.id(), line.sourceLineId(), line.variantId(), line.quantityBase(), cost, received.value()));
        }

        void out(int index, InventoryViews.MovementLine line) {
            BigDecimal qty = line.quantityBase();
            if (!takeFrom(index, line, line.fromLocationId(), qty, line.reservationId(), true)) {
                return;
            }
            Valuation.Movement issued = valuationState.get(line.variantId()).issue(qty, rounding);
            setValuation(line.variantId(), issued.after());
            BigDecimal cost = Valuation.unitCost(issued.value(), qty);
            ledger.add(row(
                    line,
                    line.fromLocationId(),
                    qty.negate(),
                    cost,
                    issued.value().negate()));
            posted.add(new PostedLine(line.id(), line.sourceLineId(), line.variantId(), qty, cost, issued.value()));
        }

        void move(int index, InventoryViews.MovementLine line) {
            BigDecimal qty = line.quantityBase();
            InventoryViews.Location from = locations.get(line.fromLocationId());
            InventoryViews.Location to = locations.get(line.toLocationId());
            // Moving between counting locations of one warehouse leaves its available stock unchanged,
            // so reserved stock may move; anything else (another warehouse, quarantine, transit) may not.
            boolean leavesWarehousePool = from.type().countsTowardWarehouse()
                    && !(to.type().countsTowardWarehouse() && to.warehouseId().equals(from.warehouseId()));
            if (!takeFrom(index, line, line.fromLocationId(), qty, null, leavesWarehousePool)) {
                return;
            }
            change(line.variantId(), line.toLocationId(), qty);
            BigDecimal value = valuationState.get(line.variantId()).valueOf(qty, rounding);
            BigDecimal cost = Valuation.unitCost(value, qty);
            ledger.add(row(line, line.fromLocationId(), qty.negate(), cost, value.negate()));
            ledger.add(row(line, line.toLocationId(), qty, cost, value));
            posted.add(new PostedLine(line.id(), line.sourceLineId(), line.variantId(), qty, cost, value));
        }

        /** Reversal: mirror of the original line's ledger rows, increases first. */
        void mirror(int index, InventoryViews.MovementLine line, List<InventoryViews.LedgerEntry> rows) {
            List<InventoryViews.LedgerEntry> ordered = rows.stream()
                    .sorted(Comparator.comparing(
                            (InventoryViews.LedgerEntry r) -> r.quantityBase().signum()))
                    .toList();
            BigDecimal net = BigDecimal.ZERO;
            for (InventoryViews.LedgerEntry r : ordered) {
                BigDecimal qty = r.quantityBase().negate();
                BigDecimal value = r.valueBase().negate();
                LocationKey key = new LocationKey(line.variantId(), r.locationId(), r.warehouseId());
                BigDecimal balance = balanceState.get(key).add(qty);
                InventoryViews.Location location = locations.get(r.locationId());
                if (balance.signum() < 0) {
                    violation(index, line, location, qty.negate(), balanceState.get(key), true);
                    return;
                }
                change(line.variantId(), r.locationId(), qty);
                if (location.type().countsTowardWarehouse()) {
                    WarehouseStock ws = warehouseState.get(new WarehouseKey(line.variantId(), location.warehouseId()));
                    if (ws.onHand().compareTo(ws.reserved()) < 0) {
                        violation(
                                index,
                                line,
                                location,
                                qty.negate(),
                                ws.onHand().subtract(qty).subtract(ws.reserved()),
                                false);
                        return;
                    }
                }
                setValuation(
                        line.variantId(), valuationState.get(line.variantId()).plus(qty, value));
                ledger.add(new LedgerRow(
                        movement.id(),
                        line.id(),
                        movement.type().name(),
                        movement.movementDate(),
                        line.variantId(),
                        r.warehouseId(),
                        r.locationId(),
                        qty,
                        r.unitCostBase(),
                        value));
                net = net.add(value);
            }
            BigDecimal cost = rows.isEmpty() ? BigDecimal.ZERO : rows.getFirst().unitCostBase();
            posted.add(new PostedLine(
                    line.id(), line.sourceLineId(), line.variantId(), line.quantityBase(), cost, net.abs()));
        }

        /** Takes stock out of a location after checking location and warehouse availability (INV-1, INV-2). */
        private boolean takeFrom(
                int index,
                InventoryViews.MovementLine line,
                UUID locationId,
                BigDecimal qty,
                @Nullable UUID reservationId,
                boolean checkWarehouseAvailability) {
            InventoryViews.Location location = locations.get(locationId);
            LocationKey key = new LocationKey(line.variantId(), locationId, location.warehouseId());
            BigDecimal balance = balanceState.get(key);
            if (qty.compareTo(balance) > 0) {
                violation(index, line, location, qty, balance, true);
                return false;
            }
            BigDecimal consumed = BigDecimal.ZERO;
            if (location.type().countsTowardWarehouse()) {
                WarehouseKey wk = new WarehouseKey(line.variantId(), location.warehouseId());
                WarehouseStock ws = warehouseState.get(wk);
                InventoryViews.Reservation reservation =
                        reservationId == null ? null : reservationState.get(reservationId);
                if (reservationId != null) {
                    if (reservation == null
                            || !"ACTIVE".equals(reservation.status())
                            || !reservation.variantId().equals(line.variantId())
                            || !reservation.warehouseId().equals(location.warehouseId())) {
                        violations.add(FieldViolation.atPointer(
                                "/lines/" + index + "/reservationId",
                                "INVALID_RESERVATION",
                                "is not an active reservation of this item in this warehouse"));
                        insufficient = true;
                        return false;
                    }
                    consumed = reservation.quantityBase().min(qty);
                }
                BigDecimal available = ws.onHand().subtract(ws.reserved()).add(consumed);
                if (checkWarehouseAvailability && qty.compareTo(available) > 0) {
                    violation(
                            index,
                            line,
                            location,
                            qty,
                            available,
                            !movement.type().isAdjustment());
                    return false;
                }
                warehouseState.put(
                        wk,
                        new WarehouseStock(
                                ws.onHand().subtract(qty), ws.reserved().subtract(consumed)));
                touchedWarehouses.add(wk);
                if (reservation != null) {
                    reservationState.put(
                            reservationId,
                            new InventoryViews.Reservation(
                                    reservation.id(),
                                    reservation.variantId(),
                                    reservation.warehouseId(),
                                    reservation.quantityBase().subtract(consumed),
                                    reservation.sourceModule(),
                                    reservation.sourceType(),
                                    reservation.sourceId(),
                                    reservation.sourceLineId(),
                                    reservation.status(),
                                    reservation.version()));
                    touchedReservations.add(reservationId);
                }
            }
            balanceState.put(key, balance.subtract(qty));
            touchedBalances.add(key);
            return true;
        }

        /** Adds a signed quantity to a location (and its warehouse if the location counts). */
        private void change(UUID variantId, UUID locationId, BigDecimal qty) {
            InventoryViews.Location location = locations.get(locationId);
            LocationKey key = new LocationKey(variantId, locationId, location.warehouseId());
            balanceState.put(key, balanceState.get(key).add(qty));
            touchedBalances.add(key);
            if (location.type().countsTowardWarehouse()) {
                WarehouseKey wk = new WarehouseKey(variantId, location.warehouseId());
                WarehouseStock ws = warehouseState.get(wk);
                warehouseState.put(wk, new WarehouseStock(ws.onHand().add(qty), ws.reserved()));
                touchedWarehouses.add(wk);
            }
        }

        private void setValuation(UUID variantId, Valuation valuation) {
            valuationState.put(variantId, valuation);
            touchedValuations.add(variantId);
        }

        private void violation(
                int index,
                InventoryViews.MovementLine line,
                InventoryViews.Location location,
                BigDecimal requested,
                BigDecimal available,
                boolean isInsufficient) {
            if (isInsufficient) {
                insufficient = true;
            } else {
                reservedConflict = true;
            }
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("variantId", line.variantId().toString());
            meta.put("locationId", location.id().toString());
            meta.put("requested", requested.stripTrailingZeros().toPlainString());
            meta.put(
                    "available",
                    available.max(BigDecimal.ZERO).stripTrailingZeros().toPlainString());
            violations.add(new FieldViolation(
                    "/lines/" + index + "/quantity",
                    null,
                    isInsufficient
                            ? InventoryErrorCode.INSUFFICIENT_STOCK.code()
                            : InventoryErrorCode.RESERVED_STOCK_CONFLICT.code(),
                    isInsufficient
                            ? "Requested " + meta.get("requested") + ", available " + meta.get("available") + " at "
                                    + location.code()
                            : "Would leave less than the reserved quantity at " + location.code(),
                    meta));
        }

        void checkValuations(Set<UUID> variantIds) {
            for (UUID variant : variantIds) {
                if (!valuationState.get(variant).isValid()) {
                    valuationConflict = true;
                    violations.add(FieldViolation.atPointer(
                            "/lines",
                            "VALUATION_CONFLICT",
                            "the item's stock has been consumed or revalued since; correct it with an adjustment"));
                }
            }
        }

        void throwIfViolated() {
            if (violations.isEmpty()) {
                return;
            }
            if (insufficient) {
                throw new ApiException(
                        InventoryErrorCode.INSUFFICIENT_STOCK, "Not enough available stock.", violations);
            }
            if (reservedConflict) {
                throw new ApiException(
                        InventoryErrorCode.RESERVED_STOCK_CONFLICT,
                        "The movement would reduce stock below the reserved quantity.",
                        violations);
            }
            if (movement.type() == MovementType.REVERSAL) {
                throw new ApiException(
                        InventoryErrorCode.REVERSAL_NOT_POSSIBLE, "The movement cannot be reversed.", violations);
            }
            throw ApiException.validationFailed("The movement cannot be posted.", violations);
        }

        private LedgerRow row(
                InventoryViews.MovementLine line,
                UUID locationId,
                BigDecimal qty,
                BigDecimal unitCost,
                BigDecimal value) {
            return new LedgerRow(
                    movement.id(),
                    line.id(),
                    movement.type().name(),
                    movement.movementDate(),
                    line.variantId(),
                    locations.get(locationId).warehouseId(),
                    locationId,
                    qty,
                    unitCost,
                    value);
        }
    }
}
