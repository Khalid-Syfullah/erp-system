package com.erp.inventory.application;

import com.erp.inventory.domain.CountStatus;
import com.erp.inventory.domain.LocationType;
import com.erp.inventory.domain.MovementType;
import com.erp.inventory.domain.UomConversion;
import com.erp.inventory.persistence.CountRepository;
import com.erp.inventory.persistence.MovementRepository;
import com.erp.inventory.persistence.ReferenceRepository;
import com.erp.inventory.persistence.StockRepository;
import com.erp.inventory.persistence.VariantRepository;
import com.erp.inventory.persistence.WarehouseRepository;
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
import com.erp.platform.web.PlatformErrorCode;
import com.erp.platform.web.paging.ListQuery;
import com.erp.platform.web.paging.PageResponse;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * Physical counts (INV-8): start snapshots the system quantity of every stocked location of the
 * warehouse (transit excluded); counters enter quantities; completing requires every line counted;
 * posting books the difference between the counted and the <b>current</b> quantity as a
 * COUNT_ADJUSTMENT, valued at the moving average. Locations are not frozen during a count (the
 * optional freeze of INV-8 is not implemented; differences are computed at posting).
 */
@Service
public class StockCountService {

    static final String DOCUMENT_TYPE = "STOCK_COUNT";
    static final Set<String> PATCHABLE = Set.of("countDate", "reasonCodeId", "notes");

    /** A counted quantity in base units. */
    public record CountedLine(
            UUID variantId, UUID locationId, @Nullable BigDecimal countedQuantityBase) {}

    private final CountRepository counts;
    private final WarehouseRepository warehouses;
    private final StockRepository stock;
    private final VariantRepository variants;
    private final ReferenceRepository references;
    private final MovementRepository movements;
    private final MovementLineResolver resolver;
    private final PostingEngine engine;
    private final DocumentNumberService numbering;
    private final InventoryContext context;
    private final AuditPort audit;

    StockCountService(
            CountRepository counts,
            WarehouseRepository warehouses,
            StockRepository stock,
            VariantRepository variants,
            ReferenceRepository references,
            MovementRepository movements,
            MovementLineResolver resolver,
            PostingEngine engine,
            DocumentNumberService numbering,
            InventoryContext context,
            AuditPort audit) {
        this.counts = counts;
        this.warehouses = warehouses;
        this.stock = stock;
        this.variants = variants;
        this.references = references;
        this.movements = movements;
        this.resolver = resolver;
        this.engine = engine;
        this.numbering = numbering;
        this.context = context;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public PageResponse<InventoryViews.Count> list(ListQuery query) {
        return counts.list(CurrentContext.requireCompany(), context.visibleWarehouses(), query);
    }

    @Transactional(readOnly = true)
    public InventoryViews.CountDetail get(UUID id) {
        UUID companyId = CurrentContext.requireCompany();
        InventoryViews.Count count = visible(counts.find(companyId, id).orElseThrow(ApiException::notFound));
        return new InventoryViews.CountDetail(count, counts.lines(companyId, id));
    }

    @Transactional
    public InventoryViews.CountDetail create(
            UUID warehouseId, LocalDate countDate, @Nullable UUID reasonCodeId, @Nullable String notes) {
        UUID companyId = CurrentContext.requireCompany();
        var warehouse =
                warehouses.find(companyId, warehouseId, CurrentContext.require().branchScope());
        List<FieldViolation> violations = new ArrayList<>();
        if (warehouse.isEmpty()) {
            violations.add(
                    FieldViolation.atPointer("/warehouseId", "UNKNOWN_WAREHOUSE", "is not a warehouse of the company"));
        } else if (!warehouse.get().active()) {
            violations.add(FieldViolation.atPointer("/warehouseId", "INACTIVE", "is an inactive warehouse"));
        }
        if (reasonCodeId != null) {
            checkReason(companyId, reasonCodeId, violations);
        }
        if (!violations.isEmpty()) {
            throw ApiException.validationFailed("The count is invalid.", violations);
        }
        UUID id = counts.insert(
                companyId,
                warehouseId,
                countDate,
                reasonCodeId,
                notes,
                CurrentContext.requireActor().userId());
        audit.record(AuditEvent.builder("CREATE", "inventory")
                .entity("stock_count", id, null)
                .detail("warehouseId", warehouseId)
                .detail("countDate", countDate.toString())
                .build());
        return get(id);
    }

    /** Edits the header: the date until the count starts (it dates the snapshot), reason and notes until posting. */
    @Transactional
    public InventoryViews.CountDetail patch(UUID id, @Nullable String ifMatch, JsonNode document) {
        UUID companyId = CurrentContext.requireCompany();
        InventoryViews.Count count = visible(counts.lockForChange(companyId, id).orElseThrow(ApiException::notFound));
        EntityTags.requireMatch(ifMatch, count.version());
        CountStatus status = CountStatus.valueOf(count.status());
        if (!status.allows(CountStatus.Action.CANCEL)) {
            throw new ApiException(PlatformErrorCode.INVALID_STATE, "A " + count.status() + " count cannot be edited.");
        }
        MergePatch patch = MergePatch.of(document, PATCHABLE);
        var date = patch.date("countDate", true);
        var reason = patch.uuid("reasonCodeId", false);
        var notes = patch.text("notes", false, 2000);
        if (date.present() && status != CountStatus.DRAFT && !count.countDate().equals(date.value())) {
            patch.reject("countDate", "LOCKED", "cannot change once the count has started");
        }
        patch.throwIfInvalid();
        UUID newReason = reason.orElse(count.reasonCodeId());
        if (reason.present() && newReason != null) {
            List<FieldViolation> violations = new ArrayList<>();
            checkReason(companyId, newReason, violations);
            if (!violations.isEmpty()) {
                throw ApiException.validationFailed("The count is invalid.", violations);
            }
        }
        LocalDate newDate = date.orElse(count.countDate());
        String newNotes = notes.orElse(count.notes());
        if (!counts.updateDetails(
                companyId, id, count.version(), CurrentContext.requireActor().userId(), newDate, newReason, newNotes)) {
            throw new ApiException(PlatformErrorCode.VERSION_CONFLICT, "The count was modified concurrently.");
        }
        audit.record(AuditEvent.builder("UPDATE", "inventory")
                .entity("stock_count", id, count.number())
                .change("countDate", count.countDate().toString(), newDate.toString())
                .change("reasonCodeId", count.reasonCodeId(), newReason)
                .change("notes", count.notes(), newNotes)
                .build());
        return get(id);
    }

    /** Snapshots the system quantity of every stocked location and numbers the count. */
    @Transactional
    public InventoryViews.CountDetail start(UUID id, @Nullable String ifMatch) {
        UUID companyId = CurrentContext.requireCompany();
        InventoryViews.Count count = lock(companyId, id, ifMatch, CountStatus.Action.START);
        UUID actor = CurrentContext.requireActor().userId();
        Map<UUID, InventoryViews.Location> locations = new HashMap<>();
        for (InventoryViews.LocationStockLevel level : stock.balancesOfWarehouse(companyId, count.warehouseId())) {
            InventoryViews.Location location = locations.computeIfAbsent(
                    level.locationId(),
                    l -> warehouses.findLocation(companyId, l).orElseThrow());
            if (location.type() != LocationType.TRANSIT) {
                counts.insertLine(companyId, id, level.variantId(), level.locationId(), level.onHand(), actor);
            }
        }
        CompanyProfile profile = context.profile(companyId);
        String number = numbering.next(
                companyId, DOCUMENT_TYPE, FiscalYears.label(count.countDate(), profile.fiscalYearStartMonth()));
        transition(count, CountStatus.Action.START, number, count.reasonCodeId(), null);
        return get(id);
    }

    /** Enters counted quantities; items found at locations without a snapshot line are added. */
    @Transactional
    public InventoryViews.CountDetail enter(UUID id, @Nullable String ifMatch, List<CountedLine> lines) {
        UUID companyId = CurrentContext.requireCompany();
        InventoryViews.Count count = lock(companyId, id, ifMatch, CountStatus.Action.ENTER);
        Set<String> existing = new HashSet<>();
        counts.lines(companyId, id).forEach(l -> existing.add(l.variantId() + "/" + l.locationId()));
        Map<UUID, InventoryViews.StockItem> items = variants.items(
                companyId, lines.stream().map(CountedLine::variantId).toList());
        List<FieldViolation> violations = new ArrayList<>();
        UUID actor = CurrentContext.requireActor().userId();
        for (int i = 0; i < lines.size(); i++) {
            CountedLine line = lines.get(i);
            String at = "/lines/" + i;
            BigDecimal counted = line.countedQuantityBase();
            if (counted != null
                    && (counted.signum() < 0 || counted.stripTrailingZeros().scale() > UomConversion.QUANTITY_SCALE)) {
                violations.add(FieldViolation.atPointer(
                        at + "/countedQuantityBase", "INVALID_VALUE", "must be ≥ 0 with at most 6 decimal places"));
                continue;
            }
            if (!existing.contains(line.variantId() + "/" + line.locationId())) {
                InventoryViews.StockItem item = items.get(line.variantId());
                InventoryViews.Location location =
                        warehouses.findLocation(companyId, line.locationId()).orElse(null);
                if (item == null || !"STOCKABLE".equals(item.productType())) {
                    violations.add(FieldViolation.atPointer(
                            at + "/variantId", "UNKNOWN_VARIANT", "is not a stock item of the company"));
                    continue;
                }
                if (location == null
                        || !location.warehouseId().equals(count.warehouseId())
                        || location.type() == LocationType.TRANSIT) {
                    violations.add(FieldViolation.atPointer(
                            at + "/locationId", "UNKNOWN_LOCATION", "is not a countable location of the warehouse"));
                    continue;
                }
                counts.insertLine(
                        companyId,
                        id,
                        line.variantId(),
                        line.locationId(),
                        stock.balance(companyId, line.variantId(), line.locationId()),
                        actor);
                existing.add(line.variantId() + "/" + line.locationId());
            }
            counts.setCounted(companyId, id, line.variantId(), line.locationId(), counted, actor);
        }
        if (!violations.isEmpty()) {
            throw ApiException.validationFailed("The counted lines are invalid.", violations);
        }
        transition(count, CountStatus.Action.ENTER, count.number(), count.reasonCodeId(), null);
        return get(id);
    }

    @Transactional
    public InventoryViews.CountDetail complete(UUID id, @Nullable String ifMatch) {
        UUID companyId = CurrentContext.requireCompany();
        InventoryViews.Count count = lock(companyId, id, ifMatch, CountStatus.Action.COMPLETE);
        long uncounted = counts.lines(companyId, id).stream()
                .filter(l -> l.countedQuantityBase() == null)
                .count();
        if (uncounted > 0) {
            throw ApiException.validationFailed(
                    "The count is incomplete.",
                    List.of(FieldViolation.atPointer(
                            "/lines", "UNCOUNTED", uncounted + " line(s) have no counted quantity")));
        }
        transition(count, CountStatus.Action.COMPLETE, count.number(), count.reasonCodeId(), null);
        return get(id);
    }

    /** Posts counted − current quantity per line as one COUNT_ADJUSTMENT (valued at the moving average). */
    @Transactional
    public InventoryViews.CountDetail post(UUID id, @Nullable String ifMatch, @Nullable UUID reasonCodeId) {
        UUID companyId = CurrentContext.requireCompany();
        InventoryViews.Count count = lock(companyId, id, ifMatch, CountStatus.Action.POST);
        UUID reason = reasonCodeId != null ? reasonCodeId : count.reasonCodeId();
        List<FieldViolation> violations = new ArrayList<>();
        if (reason == null) {
            violations.add(
                    FieldViolation.atPointer("/reasonCodeId", "REQUIRED", "a COUNT reason code is required (INV-7)"));
        } else {
            checkReason(companyId, reason, violations);
        }
        if (!violations.isEmpty()) {
            throw ApiException.validationFailed("The count cannot be posted.", violations);
        }
        List<InventoryViews.CountLine> lines = counts.lines(companyId, id);
        Map<UUID, InventoryViews.StockItem> items = variants.items(
                companyId,
                lines.stream().map(InventoryViews.CountLine::variantId).toList());
        // Lock the stock rows (in ARCHITECTURE.md §6.2 order) before reading the current quantities, so
        // no movement can change them between computing the differences and posting them.
        stock.lockWarehouseStock(
                companyId,
                lines.stream()
                        .map(l -> new StockRepository.WarehouseKey(l.variantId(), count.warehouseId()))
                        .collect(Collectors.toCollection(LinkedHashSet::new)));
        Map<StockRepository.LocationKey, BigDecimal> current = stock.lockBalances(
                companyId,
                lines.stream()
                        .map(l -> new StockRepository.LocationKey(l.variantId(), l.locationId(), count.warehouseId()))
                        .toList());
        List<InventoryCommands.Line> adjustments = new ArrayList<>();
        for (InventoryViews.CountLine line : lines) {
            BigDecimal difference = line.countedQuantityBase()
                    .subtract(current.get(
                            new StockRepository.LocationKey(line.variantId(), line.locationId(), count.warehouseId())));
            if (difference.signum() == 0) {
                continue;
            }
            boolean gain = difference.signum() > 0;
            adjustments.add(new InventoryCommands.Line(
                    line.variantId(),
                    gain ? null : line.locationId(),
                    gain ? line.locationId() : null,
                    difference.abs(),
                    items.get(line.variantId()).baseUomId(),
                    null,
                    null,
                    null,
                    line.id(),
                    null));
        }
        UUID movementId = null;
        if (!adjustments.isEmpty()) {
            UUID actor = CurrentContext.requireActor().userId();
            var resolved =
                    resolver.resolve(companyId, MovementType.COUNT_ADJUSTMENT, count.warehouseId(), null, adjustments);
            movementId = movements.insert(
                    companyId,
                    new InventoryCommands.Movement(
                            MovementType.COUNT_ADJUSTMENT,
                            count.countDate(),
                            count.warehouseId(),
                            null,
                            null,
                            reason,
                            "inventory",
                            "STOCK_COUNT",
                            id,
                            count.number(),
                            null,
                            null,
                            "Count " + count.number(),
                            List.of()),
                    actor);
            movements.insertLines(companyId, movementId, resolved, actor);
            engine.post(movements.lockForChange(companyId, movementId).orElseThrow());
        }
        transition(count, CountStatus.Action.POST, count.number(), reason, movementId);
        return get(id);
    }

    @Transactional
    public InventoryViews.CountDetail cancel(UUID id, @Nullable String ifMatch) {
        UUID companyId = CurrentContext.requireCompany();
        InventoryViews.Count count = lock(companyId, id, ifMatch, CountStatus.Action.CANCEL);
        transition(count, CountStatus.Action.CANCEL, count.number(), count.reasonCodeId(), null);
        return get(id);
    }

    private void transition(
            InventoryViews.Count count,
            CountStatus.Action action,
            @Nullable String number,
            @Nullable UUID reasonCodeId,
            @Nullable UUID adjustmentMovementId) {
        CountStatus from = CountStatus.valueOf(count.status());
        CountStatus to = from.apply(action);
        if (!counts.updateHeader(
                count.companyId(),
                count.id(),
                count.version(),
                CurrentContext.requireActor().userId(),
                to.name(),
                number,
                reasonCodeId,
                count.notes(),
                adjustmentMovementId)) {
            throw new ApiException(PlatformErrorCode.VERSION_CONFLICT, "The count was modified concurrently.");
        }
        if (from != to) {
            AuditEvent.Builder event = AuditEvent.builder("STATE_CHANGE", "inventory")
                    .entity("stock_count", count.id(), number)
                    .transition(from.name(), to.name());
            if (adjustmentMovementId != null) {
                event.detail("adjustmentMovementId", adjustmentMovementId);
            }
            audit.record(event.build());
        } else {
            audit.record(AuditEvent.builder("UPDATE", "inventory")
                    .entity("stock_count", count.id(), number)
                    .detail("countedLines", "entered")
                    .build());
        }
    }

    private InventoryViews.Count lock(UUID companyId, UUID id, @Nullable String ifMatch, CountStatus.Action action) {
        InventoryViews.Count count = visible(counts.lockForChange(companyId, id).orElseThrow(ApiException::notFound));
        EntityTags.requireMatch(ifMatch, count.version());
        if (!CountStatus.valueOf(count.status()).allows(action)) {
            throw new ApiException(
                    PlatformErrorCode.INVALID_STATE,
                    "A " + count.status() + " count does not allow "
                            + action.name().toLowerCase(java.util.Locale.ROOT) + ".");
        }
        return count;
    }

    private InventoryViews.Count visible(InventoryViews.Count count) {
        Set<UUID> visibleWarehouses = context.visibleWarehouses();
        if (visibleWarehouses != null && !visibleWarehouses.contains(count.warehouseId())) {
            throw ApiException.notFound();
        }
        return count;
    }

    private void checkReason(UUID companyId, UUID reasonCodeId, List<FieldViolation> violations) {
        var reason = references.findReasonCode(companyId, reasonCodeId).orElse(null);
        if (reason == null || !reason.active() || !"COUNT".equals(reason.appliesTo())) {
            violations.add(FieldViolation.atPointer(
                    "/reasonCodeId", "INVALID_VALUE", "must be an active reason code for COUNT"));
        }
    }
}
