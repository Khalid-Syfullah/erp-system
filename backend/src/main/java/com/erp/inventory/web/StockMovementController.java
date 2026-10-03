package com.erp.inventory.web;

import com.erp.inventory.InventoryPermissions;
import com.erp.inventory.application.InventoryCommands;
import com.erp.inventory.application.InventoryListings;
import com.erp.inventory.application.StockMovementService;
import com.erp.inventory.domain.MovementType;
import com.erp.platform.idempotency.IdempotencyExecutor;
import com.erp.platform.security.RequiresPermission;
import com.erp.platform.web.ApiPaths;
import com.erp.platform.web.EntityTags;
import com.erp.platform.web.paging.ListQueryParser;
import com.erp.platform.web.paging.PageResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * Stock movements (API.md §17.5): opening stock, transfers, two-step transfers, adjustments and scrap.
 * Posting, reversing and receiving require an {@code Idempotency-Key}; creation honors one.
 */
@RestController
@RequestMapping(ApiPaths.V1 + "/companies/{companyId}/stock-movements")
class StockMovementController {

    private final StockMovementService movements;
    private final IdempotencyExecutor idempotency;
    private final ListQueryParser parser;

    StockMovementController(StockMovementService movements, IdempotencyExecutor idempotency, ListQueryParser parser) {
        this.movements = movements;
        this.idempotency = idempotency;
        this.parser = parser;
    }

    record LineRequest(
            @NotNull UUID variantId,
            @Nullable UUID fromLocationId,
            @Nullable UUID toLocationId,

            @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 12, fraction = 6) BigDecimal quantity,

            @NotNull UUID uomId,

            @DecimalMin("0") @Digits(integer = 13, fraction = 6) @Nullable BigDecimal unitCostBase) {

        InventoryCommands.Line toCommand() {
            return new InventoryCommands.Line(
                    variantId, fromLocationId, toLocationId, quantity, uomId, unitCostBase, null, null, null, null);
        }
    }

    record CreateRequest(
            @NotBlank @Pattern(regexp = "^(OPENING|TRANSFER|TRANSFER_SHIP|ADJUSTMENT|SCRAP)$")
            String movementType,

            @NotNull LocalDate movementDate,
            @NotNull UUID warehouseId,
            @Nullable UUID destWarehouseId,
            @Nullable UUID reasonCodeId,
            @Size(max = 2000) @Nullable String notes,
            @NotNull @Size(min = 1, max = 500) List<@Valid @NotNull LineRequest> lines,
            @Nullable Boolean postImmediately) {}

    record ReverseRequest(@Nullable LocalDate movementDate) {}

    record ReceiveLineRequest(
            @NotNull UUID shipLineId, @NotNull UUID toLocationId) {}

    record ReceiveRequest(
            @Nullable LocalDate movementDate,
            @Size(max = 500) @Nullable List<@Valid ReceiveLineRequest> lines) {}

    @RequiresPermission(InventoryPermissions.MOVEMENT_READ)
    @GetMapping
    PageResponse<InventoryResponses.Movement> list(
            @PathVariable UUID companyId, @RequestParam MultiValueMap<String, String> parameters) {
        return movements
                .list(parser.parse(parameters, InventoryListings.MOVEMENTS))
                .map(m -> InventoryResponses.Movement.from(m, null));
    }

    @RequiresPermission(InventoryPermissions.MOVEMENT_READ)
    @GetMapping("/{movementId}")
    ResponseEntity<InventoryResponses.Movement> get(@PathVariable UUID companyId, @PathVariable UUID movementId) {
        return InventoryResponses.Movement.entity(movements.get(movementId));
    }

    /** Creates a draft; {@code postImmediately} posts it in the same transaction and then requires a key. */
    @RequiresPermission(InventoryPermissions.MOVEMENT_CREATE)
    @PostMapping
    ResponseEntity<?> create(
            @PathVariable UUID companyId, @Valid @RequestBody CreateRequest request, HttpServletRequest http) {
        boolean postNow = Boolean.TRUE.equals(request.postImmediately());
        return idempotency.execute(http, request, postNow, () -> {
            var created = movements.create(
                    new InventoryCommands.Movement(
                            MovementType.valueOf(request.movementType()),
                            request.movementDate(),
                            request.warehouseId(),
                            request.destWarehouseId(),
                            null,
                            request.reasonCodeId(),
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            request.notes(),
                            request.lines().stream().map(LineRequest::toCommand).toList()),
                    postNow);
            return ResponseEntity.created(URI.create(ApiPaths.V1 + "/companies/" + companyId + "/stock-movements/"
                            + created.movement().id()))
                    .eTag(EntityTags.forVersion(created.movement().version()))
                    .body(InventoryResponses.Movement.from(created.movement(), created.lines()));
        });
    }

    /** Edits a draft (merge patch; {@code lines} replaces all lines). */
    @RequiresPermission(InventoryPermissions.MOVEMENT_CREATE)
    @PatchMapping(path = "/{movementId}", consumes = InventoryResponses.MERGE_PATCH)
    ResponseEntity<InventoryResponses.Movement> patch(
            @PathVariable UUID companyId,
            @PathVariable UUID movementId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch,
            @RequestBody JsonNode patch) {
        return InventoryResponses.Movement.entity(movements.patch(movementId, ifMatch, patch));
    }

    @RequiresPermission(InventoryPermissions.MOVEMENT_CREATE)
    @DeleteMapping("/{movementId}")
    ResponseEntity<Void> delete(
            @PathVariable UUID companyId,
            @PathVariable UUID movementId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch) {
        movements.delete(movementId, ifMatch);
        return ResponseEntity.noContent().build();
    }

    @RequiresPermission(InventoryPermissions.MOVEMENT_POST)
    @PostMapping("/{movementId}/post")
    ResponseEntity<?> post(
            @PathVariable UUID companyId,
            @PathVariable UUID movementId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch,
            HttpServletRequest http) {
        return idempotency.execute(
                http, null, true, () -> InventoryResponses.Movement.entity(movements.post(movementId, ifMatch)));
    }

    @RequiresPermission(InventoryPermissions.MOVEMENT_POST)
    @PostMapping("/{movementId}/cancel")
    ResponseEntity<InventoryResponses.Movement> cancel(
            @PathVariable UUID companyId,
            @PathVariable UUID movementId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch) {
        return InventoryResponses.Movement.entity(movements.cancel(movementId, ifMatch));
    }

    /** Creates and posts the mirror REVERSAL; answers with the reversal. */
    @RequiresPermission(InventoryPermissions.MOVEMENT_REVERSE)
    @PostMapping("/{movementId}/reverse")
    ResponseEntity<?> reverse(
            @PathVariable UUID companyId,
            @PathVariable UUID movementId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch,
            @RequestBody(required = false) @Nullable ReverseRequest request,
            HttpServletRequest http) {
        return idempotency.execute(http, request, true, () -> {
            var reversal = movements.reverse(movementId, ifMatch, request == null ? null : request.movementDate());
            return ResponseEntity.status(201)
                    .eTag(EntityTags.forVersion(reversal.movement().version()))
                    .location(URI.create(ApiPaths.V1 + "/companies/" + companyId + "/stock-movements/"
                            + reversal.movement().id()))
                    .body(InventoryResponses.Movement.from(reversal.movement(), reversal.lines()));
        });
    }

    /** Receives a shipped two-step transfer (TRANSFER_SHIP → TRANSFER_RECEIVE); answers with the receipt. */
    @RequiresPermission(InventoryPermissions.MOVEMENT_POST)
    @PostMapping("/{movementId}/receive")
    ResponseEntity<?> receive(
            @PathVariable UUID companyId,
            @PathVariable UUID movementId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch,
            @RequestBody(required = false) @Valid @Nullable ReceiveRequest request,
            HttpServletRequest http) {
        return idempotency.execute(http, request, true, () -> {
            var receipt = movements.receive(
                    movementId,
                    ifMatch,
                    request == null ? null : request.movementDate(),
                    request == null || request.lines() == null
                            ? List.of()
                            : request.lines().stream()
                                    .map(l -> new StockMovementService.ReceiveLine(l.shipLineId(), l.toLocationId()))
                                    .toList());
            return ResponseEntity.status(201)
                    .eTag(EntityTags.forVersion(receipt.movement().version()))
                    .location(URI.create(ApiPaths.V1 + "/companies/" + companyId + "/stock-movements/"
                            + receipt.movement().id()))
                    .body(InventoryResponses.Movement.from(receipt.movement(), receipt.lines()));
        });
    }
}
