package com.erp.inventory.application;

import com.erp.inventory.InventoryPermissions;
import com.erp.inventory.domain.MovementStatus;
import com.erp.inventory.domain.MovementType;
import com.erp.inventory.persistence.MovementRepository;
import com.erp.inventory.persistence.ReferenceRepository;
import com.erp.inventory.persistence.WarehouseRepository;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditPort;
import com.erp.platform.context.CurrentContext;
import com.erp.platform.web.ApiException;
import com.erp.platform.web.EntityTags;
import com.erp.platform.web.FieldViolation;
import com.erp.platform.web.MergePatch;
import com.erp.platform.web.PlatformErrorCode;
import com.erp.platform.web.paging.ListQuery;
import com.erp.platform.web.paging.PageResponse;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * Stock movements entered through the API (API.md §17.5): opening stock, transfers (one- and
 * two-step), adjustments and scrap, as drafts that are posted, cancelled or — once posted — reversed.
 * Receipts, issues and returns come only from Procurement and Sales through {@code InventoryFacade}.
 * Branch-restricted users work only with movements of warehouses in their branches.
 */
@Service
public class StockMovementService {

    static final Set<String> PATCHABLE = Set.of("movementDate", "notes", "reasonCodeId", "destWarehouseId", "lines");

    /** A line entered for receiving a two-step transfer: where a shipped line goes. */
    public record ReceiveLine(UUID shipLineId, UUID toLocationId) {}

    private final MovementRepository movements;
    private final WarehouseRepository warehouses;
    private final ReferenceRepository references;
    private final MovementLineResolver resolver;
    private final PostingEngine engine;
    private final InventoryContext context;
    private final AuditPort audit;
    private final LinesReader linesReader;

    StockMovementService(
            MovementRepository movements,
            WarehouseRepository warehouses,
            ReferenceRepository references,
            MovementLineResolver resolver,
            PostingEngine engine,
            InventoryContext context,
            AuditPort audit,
            LinesReader linesReader) {
        this.movements = movements;
        this.warehouses = warehouses;
        this.references = references;
        this.resolver = resolver;
        this.engine = engine;
        this.context = context;
        this.audit = audit;
        this.linesReader = linesReader;
    }

    @Transactional(readOnly = true)
    public PageResponse<InventoryViews.Movement> list(ListQuery query) {
        return movements.list(CurrentContext.requireCompany(), context.visibleWarehouses(), query);
    }

    @Transactional(readOnly = true)
    public InventoryViews.MovementDetail get(UUID id) {
        UUID companyId = CurrentContext.requireCompany();
        InventoryViews.Movement movement = visible(movements.find(companyId, id).orElseThrow(ApiException::notFound));
        return new InventoryViews.MovementDetail(movement, movements.lines(companyId, id));
    }

    @Transactional
    public InventoryViews.MovementDetail create(InventoryCommands.Movement command, boolean postImmediately) {
        UUID companyId = CurrentContext.requireCompany();
        MovementType type = command.type();
        if (!MovementType.API_CREATABLE.contains(type)) {
            throw ApiException.validationFailed(
                    "This movement type is created by its source document.",
                    List.of(FieldViolation.atPointer(
                            "/movementType", "NOT_ALLOWED", "must be one of " + MovementType.API_CREATABLE)));
        }
        if (type.isAdjustment()) {
            context.require(InventoryPermissions.ADJUSTMENT_MANAGE, "Creating adjustments");
        }
        validateHeader(companyId, type, command.warehouseId(), command.destWarehouseId(), command.reasonCodeId());
        List<InventoryCommands.ResolvedLine> lines =
                resolver.resolve(companyId, type, command.warehouseId(), command.destWarehouseId(), command.lines());
        UUID actor = CurrentContext.requireActor().userId();
        UUID id = movements.insert(companyId, command, actor);
        movements.insertLines(companyId, id, lines, actor);
        audit.record(AuditEvent.builder("CREATE", "inventory")
                .entity("stock_movement", id, type.name())
                .detail("movementType", type.name())
                .detail("warehouseId", command.warehouseId())
                .detail("destWarehouseId", command.destWarehouseId())
                .detail("movementDate", command.movementDate().toString())
                .detail("lines", lines.size())
                .build());
        if (postImmediately) {
            context.require(InventoryPermissions.MOVEMENT_POST, "Posting movements");
            return post(id, EntityTags.forVersion(0));
        }
        return get(id);
    }

    /** Edits a draft; {@code lines} replaces all lines (RFC 7396 replaces arrays). */
    @Transactional
    public InventoryViews.MovementDetail patch(UUID id, @Nullable String ifMatch, JsonNode document) {
        UUID companyId = CurrentContext.requireCompany();
        InventoryViews.Movement current = lock(companyId, id);
        EntityTags.requireMatch(ifMatch, current.version());
        requireAllowed(current, MovementStatus.Action.EDIT);
        if (current.type().isAdjustment()) {
            context.require(InventoryPermissions.ADJUSTMENT_MANAGE, "Editing adjustments");
        }
        MergePatch patch = MergePatch.of(document, PATCHABLE);
        var date = patch.date("movementDate", true);
        var notes = patch.text("notes", false, 2000);
        var reason = patch.uuid("reasonCodeId", false);
        var dest = patch.uuid("destWarehouseId", false);
        patch.throwIfInvalid();
        List<InventoryCommands.Line> newLines = document.has("lines") ? linesReader.read(document.get("lines")) : null;

        UUID newDest = dest.orElse(current.destWarehouseId());
        UUID newReason = reason.orElse(current.reasonCodeId());
        validateHeader(companyId, current.type(), current.warehouseId(), newDest, newReason);
        UUID actor = CurrentContext.requireActor().userId();
        if (newLines != null || !Objects.equals(newDest, current.destWarehouseId())) {
            List<InventoryCommands.Line> lines =
                    newLines != null ? newLines : asCommands(movements.lines(companyId, id));
            List<InventoryCommands.ResolvedLine> resolved =
                    resolver.resolve(companyId, current.type(), current.warehouseId(), newDest, lines);
            movements.deleteLines(companyId, id);
            movements.insertLines(companyId, id, resolved, actor);
        }
        if (!movements.updateDraft(
                companyId,
                id,
                current.version(),
                actor,
                date.orElse(current.movementDate()),
                newDest,
                newReason,
                notes.orElse(current.notes()))) {
            throw new ApiException(PlatformErrorCode.VERSION_CONFLICT, "The movement was modified concurrently.");
        }
        InventoryViews.MovementDetail after = get(id);
        audit.record(AuditEvent.builder("UPDATE", "inventory")
                .entity("stock_movement", id, current.type().name())
                .change(
                        "movementDate",
                        current.movementDate().toString(),
                        after.movement().movementDate().toString())
                .change("notes", current.notes(), after.movement().notes())
                .change("reasonCodeId", current.reasonCodeId(), after.movement().reasonCodeId())
                .change(
                        "destWarehouseId",
                        current.destWarehouseId(),
                        after.movement().destWarehouseId())
                .detail("linesReplaced", newLines != null)
                .build());
        return after;
    }

    @Transactional
    public void delete(UUID id, @Nullable String ifMatch) {
        UUID companyId = CurrentContext.requireCompany();
        InventoryViews.Movement current = lock(companyId, id);
        EntityTags.requireMatch(ifMatch, current.version());
        requireAllowed(current, MovementStatus.Action.DELETE);
        if (current.type().isAdjustment()) {
            context.require(InventoryPermissions.ADJUSTMENT_MANAGE, "Deleting adjustments");
        }
        if (!movements.delete(companyId, id, current.version())) {
            throw new ApiException(PlatformErrorCode.VERSION_CONFLICT, "The movement was modified concurrently.");
        }
        audit.record(AuditEvent.builder("DELETE", "inventory")
                .entity("stock_movement", id, current.type().name())
                .detail("movementType", current.type().name())
                .build());
    }

    @Transactional
    public InventoryViews.MovementDetail cancel(UUID id, @Nullable String ifMatch) {
        UUID companyId = CurrentContext.requireCompany();
        InventoryViews.Movement current = lock(companyId, id);
        EntityTags.requireMatch(ifMatch, current.version());
        requireAllowed(current, MovementStatus.Action.CANCEL);
        if (current.type().isAdjustment()) {
            context.require(InventoryPermissions.ADJUSTMENT_MANAGE, "Cancelling adjustments");
        }
        if (!movements.markCancelled(
                companyId, id, current.version(), CurrentContext.requireActor().userId())) {
            throw new ApiException(PlatformErrorCode.VERSION_CONFLICT, "The movement was modified concurrently.");
        }
        audit.record(AuditEvent.builder("STATE_CHANGE", "inventory")
                .entity("stock_movement", id, current.type().name())
                .transition(MovementStatus.DRAFT.name(), MovementStatus.CANCELLED.name())
                .build());
        return get(id);
    }

    @Transactional
    public InventoryViews.MovementDetail post(UUID id, @Nullable String ifMatch) {
        UUID companyId = CurrentContext.requireCompany();
        InventoryViews.Movement current = lock(companyId, id);
        EntityTags.requireMatch(ifMatch, current.version());
        requireAllowed(current, MovementStatus.Action.POST);
        if (current.type().isAdjustment()) {
            context.require(InventoryPermissions.ADJUSTMENT_MANAGE, "Posting adjustments");
        }
        engine.post(current);
        return get(id);
    }

    /**
     * Reverses a posted movement with a mirror {@code REVERSAL} (INV-3), dated today unless given. A
     * shipped two-step transfer is reversed only after its receipt has been reversed.
     */
    @Transactional
    public InventoryViews.MovementDetail reverse(UUID id, @Nullable String ifMatch, @Nullable LocalDate movementDate) {
        UUID companyId = CurrentContext.requireCompany();
        InventoryViews.Movement original = lock(companyId, id);
        EntityTags.requireMatch(ifMatch, original.version());
        requireAllowed(original, MovementStatus.Action.REVERSE);
        if (original.type() == MovementType.REVERSAL) {
            throw new ApiException(
                    PlatformErrorCode.INVALID_STATE, "A reversal cannot itself be reversed; post a new movement.");
        }
        if (movements.findLinked(companyId, MovementType.REVERSAL, null, id).isPresent()) {
            throw new ApiException(PlatformErrorCode.INVALID_STATE, "The movement has already been reversed.");
        }
        if (original.type() == MovementType.TRANSFER_SHIP) {
            var receipt = movements.findLinked(companyId, MovementType.TRANSFER_RECEIVE, id, null);
            if (receipt.isPresent()
                    && movements
                            .findLinked(
                                    companyId,
                                    MovementType.REVERSAL,
                                    null,
                                    receipt.get().id())
                            .isEmpty()) {
                throw new ApiException(
                        PlatformErrorCode.INVALID_STATE, "The transfer has been received; reverse its receipt first.");
            }
        }
        LocalDate date = movementDate != null ? movementDate : context.today(context.profile(companyId));
        List<InventoryCommands.ResolvedLine> lines = new ArrayList<>();
        for (InventoryViews.MovementLine line : movements.lines(companyId, id)) {
            lines.add(new InventoryCommands.ResolvedLine(
                    new InventoryCommands.Line(
                            line.variantId(),
                            line.toLocationId(),
                            line.fromLocationId(),
                            line.quantity(),
                            line.uomId(),
                            null,
                            null,
                            null,
                            line.sourceLineId(),
                            line.id()),
                    line.quantityBase()));
        }
        InventoryCommands.Movement command = new InventoryCommands.Movement(
                MovementType.REVERSAL,
                date,
                original.warehouseId(),
                original.destWarehouseId(),
                original.partnerId(),
                null,
                original.sourceModule(),
                original.sourceType(),
                original.sourceId(),
                original.sourceNumber(),
                id,
                null,
                "Reversal of " + original.number(),
                List.of());
        UUID actor = CurrentContext.requireActor().userId();
        UUID reversalId = movements.insert(companyId, command, actor);
        movements.insertLines(companyId, reversalId, lines, actor);
        audit.record(AuditEvent.builder("REVERSE", "inventory")
                .entity("stock_movement", id, original.number())
                .detail("reversalId", reversalId)
                .build());
        engine.post(lock(companyId, reversalId));
        return get(reversalId);
    }

    /**
     * Receives a shipped two-step transfer ({@code TRANSFER_SHIP} → {@code TRANSFER_RECEIVE}): the
     * stock leaves the destination's transit location for its stock location, or the locations given
     * per shipped line. A shipment is received once, completely.
     */
    @Transactional
    public InventoryViews.MovementDetail receive(
            UUID shipId, @Nullable String ifMatch, @Nullable LocalDate movementDate, List<ReceiveLine> targets) {
        UUID companyId = CurrentContext.requireCompany();
        InventoryViews.Movement ship = lock(companyId, shipId);
        EntityTags.requireMatch(ifMatch, ship.version());
        if (ship.type() != MovementType.TRANSFER_SHIP || ship.status() != MovementStatus.POSTED) {
            throw new ApiException(
                    PlatformErrorCode.INVALID_STATE, "Only posted TRANSFER_SHIP movements can be received.");
        }
        if (movements
                .findLinked(companyId, MovementType.TRANSFER_RECEIVE, shipId, null)
                .isPresent()) {
            throw new ApiException(PlatformErrorCode.INVALID_STATE, "The transfer has already been received.");
        }
        if (movements.findLinked(companyId, MovementType.REVERSAL, null, shipId).isPresent()) {
            throw new ApiException(PlatformErrorCode.INVALID_STATE, "The transfer has been reversed.");
        }
        UUID destination = Objects.requireNonNull(ship.destWarehouseId());
        if (context.visibleWarehouses() != null && !context.visibleWarehouses().contains(destination)) {
            throw ApiException.notFound();
        }
        UUID defaultLocation = warehouses
                .locationByCode(companyId, destination, WarehouseService.STOCK_LOCATION)
                .map(InventoryViews.Location::id)
                .orElseThrow(() -> new ApiException(
                        PlatformErrorCode.INVALID_STATE, "The destination warehouse has no STOCK location."));
        Map<UUID, UUID> targetByLine = new HashMap<>();
        targets.forEach(t -> targetByLine.put(t.shipLineId(), t.toLocationId()));
        List<InventoryViews.MovementLine> shipLines = movements.lines(companyId, shipId);
        if (!shipLines.stream().map(InventoryViews.MovementLine::id).toList().containsAll(targetByLine.keySet())) {
            throw ApiException.validationFailed(
                    "The receipt is invalid.",
                    List.of(FieldViolation.atPointer(
                            "/lines", "UNKNOWN_LINE", "names a line that is not part of the shipment")));
        }
        List<InventoryCommands.Line> lines = shipLines.stream()
                .map(l -> new InventoryCommands.Line(
                        l.variantId(),
                        l.toLocationId(),
                        targetByLine.getOrDefault(l.id(), defaultLocation),
                        l.quantity(),
                        l.uomId(),
                        null,
                        null,
                        null,
                        l.sourceLineId(),
                        null))
                .toList();
        List<InventoryCommands.ResolvedLine> resolved =
                resolver.resolve(companyId, MovementType.TRANSFER_RECEIVE, destination, null, lines);
        LocalDate date = movementDate != null ? movementDate : context.today(context.profile(companyId));
        if (date.isBefore(ship.movementDate())) {
            throw ApiException.validationFailed(
                    "The receipt is invalid.",
                    List.of(FieldViolation.atPointer(
                            "/movementDate", "BEFORE_SHIPMENT", "must not be before the shipment date")));
        }
        UUID actor = CurrentContext.requireActor().userId();
        UUID receiptId = movements.insert(
                companyId,
                new InventoryCommands.Movement(
                        MovementType.TRANSFER_RECEIVE,
                        date,
                        destination,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        shipId,
                        "Receipt of " + ship.number(),
                        List.of()),
                actor);
        movements.insertLines(companyId, receiptId, resolved, actor);
        audit.record(AuditEvent.builder("CREATE", "inventory")
                .entity("stock_movement", receiptId, MovementType.TRANSFER_RECEIVE.name())
                .detail("relatedMovementId", shipId)
                .build());
        engine.post(lock(companyId, receiptId));
        return get(receiptId);
    }

    private void validateHeader(
            UUID companyId,
            MovementType type,
            UUID warehouseId,
            @Nullable UUID destWarehouseId,
            @Nullable UUID reasonCodeId) {
        List<FieldViolation> violations = new ArrayList<>();
        var warehouse =
                warehouses.find(companyId, warehouseId, CurrentContext.require().branchScope());
        if (warehouse.isEmpty()) {
            violations.add(
                    FieldViolation.atPointer("/warehouseId", "UNKNOWN_WAREHOUSE", "is not a warehouse of the company"));
        } else if (!warehouse.get().active()) {
            violations.add(FieldViolation.atPointer("/warehouseId", "INACTIVE", "is an inactive warehouse"));
        }
        boolean destAllowed = type == MovementType.TRANSFER || type == MovementType.TRANSFER_SHIP;
        if (destWarehouseId != null && !destAllowed) {
            violations.add(FieldViolation.atPointer("/destWarehouseId", "NOT_ALLOWED", "is only used by transfers"));
        }
        if (type == MovementType.TRANSFER_SHIP && (destWarehouseId == null || destWarehouseId.equals(warehouseId))) {
            violations.add(FieldViolation.atPointer(
                    "/destWarehouseId", "REQUIRED", "a two-step transfer goes to another warehouse"));
        }
        if (destWarehouseId != null && destAllowed) {
            var dest = warehouses.find(companyId, destWarehouseId, null);
            if (dest.isEmpty()) {
                violations.add(FieldViolation.atPointer(
                        "/destWarehouseId", "UNKNOWN_WAREHOUSE", "is not a warehouse of the company"));
            } else if (!dest.get().active()) {
                violations.add(FieldViolation.atPointer("/destWarehouseId", "INACTIVE", "is an inactive warehouse"));
            } else if (type == MovementType.TRANSFER_SHIP
                    && warehouses.transitLocation(companyId, destWarehouseId).isEmpty()) {
                violations.add(FieldViolation.atPointer("/destWarehouseId", "NO_TRANSIT", "has no transit location"));
            }
        }
        if (type.requiresReason()) {
            String appliesTo = type == MovementType.SCRAP
                    ? "SCRAP"
                    : type == MovementType.COUNT_ADJUSTMENT ? "COUNT" : "ADJUSTMENT";
            var reason = reasonCodeId == null
                    ? null
                    : references.findReasonCode(companyId, reasonCodeId).orElse(null);
            if (reason == null) {
                violations.add(
                        FieldViolation.atPointer("/reasonCodeId", "REQUIRED", "a reason code is required (INV-7)"));
            } else if (!reason.active() || !reason.appliesTo().equals(appliesTo)) {
                violations.add(FieldViolation.atPointer(
                        "/reasonCodeId", "INVALID_VALUE", "must be an active reason code for " + appliesTo));
            }
        } else if (reasonCodeId != null) {
            violations.add(
                    FieldViolation.atPointer("/reasonCodeId", "NOT_ALLOWED", "only adjustments carry a reason code"));
        }
        if (!violations.isEmpty()) {
            throw ApiException.validationFailed("The movement is invalid.", violations);
        }
    }

    private InventoryViews.Movement lock(UUID companyId, UUID id) {
        return visible(movements.lockForChange(companyId, id).orElseThrow(ApiException::notFound));
    }

    private InventoryViews.Movement visible(InventoryViews.Movement movement) {
        Set<UUID> visibleWarehouses = context.visibleWarehouses();
        if (visibleWarehouses != null
                && !visibleWarehouses.contains(movement.warehouseId())
                && (movement.destWarehouseId() == null || !visibleWarehouses.contains(movement.destWarehouseId()))) {
            throw ApiException.notFound();
        }
        return movement;
    }

    private static void requireAllowed(InventoryViews.Movement movement, MovementStatus.Action action) {
        if (!movement.status().allows(action)) {
            throw new ApiException(
                    PlatformErrorCode.INVALID_STATE,
                    "A " + movement.status() + " movement does not allow "
                            + action.name().toLowerCase(java.util.Locale.ROOT) + ".");
        }
    }

    private static List<InventoryCommands.Line> asCommands(List<InventoryViews.MovementLine> lines) {
        return lines.stream()
                .map(l -> new InventoryCommands.Line(
                        l.variantId(),
                        l.fromLocationId(),
                        l.toLocationId(),
                        l.quantity(),
                        l.uomId(),
                        l.unitCostBase(),
                        l.referenceUnitCostBase(),
                        l.reservationId(),
                        l.sourceLineId(),
                        l.reversalOfLineId()))
                .toList();
    }
}
