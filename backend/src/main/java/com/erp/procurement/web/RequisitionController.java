package com.erp.procurement.web;

import com.erp.platform.idempotency.IdempotencyExecutor;
import com.erp.platform.security.RequiresPermission;
import com.erp.platform.web.ApiPaths;
import com.erp.platform.web.EntityTags;
import com.erp.platform.web.paging.ListQueryParser;
import com.erp.platform.web.paging.PageResponse;
import com.erp.procurement.ProcurementPermissions;
import com.erp.procurement.application.ProcurementCommands;
import com.erp.procurement.application.ProcurementListings;
import com.erp.procurement.application.PurchaseOrderService;
import com.erp.procurement.application.RequisitionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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

/** Purchase requisitions and their conversion into orders (API.md §17.6). */
@RestController
@RequestMapping(ApiPaths.V1 + "/companies/{companyId}/purchase-requisitions")
class RequisitionController {

    private final RequisitionService requisitions;
    private final PurchaseOrderService orders;
    private final ListQueryParser parser;
    private final IdempotencyExecutor idempotency;

    RequisitionController(
            RequisitionService requisitions,
            PurchaseOrderService orders,
            ListQueryParser parser,
            IdempotencyExecutor idempotency) {
        this.requisitions = requisitions;
        this.orders = orders;
        this.parser = parser;
        this.idempotency = idempotency;
    }

    record LineRequest(
            @NotNull UUID variantId,
            @Size(max = 300) @Nullable String description,

            @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 12, fraction = 6) BigDecimal quantity,

            @NotNull UUID uomId,

            @DecimalMin("0") @Digits(integer = 13, fraction = 6) @Nullable BigDecimal estimatedUnitPrice,

            @Nullable UUID suggestedSupplierId) {}

    record RequisitionRequest(
            @NotNull UUID branchId,
            @Nullable UUID departmentId,
            @Nullable LocalDate neededBy,
            @Size(max = 2000) @Nullable String notes,

            @NotNull @Size(min = 1, max = 500) List<@Valid @NotNull LineRequest> lines) {}

    record ReasonRequest(@NotBlank @Size(max = 500) String reason) {}

    record OptionalReasonRequest(@Size(max = 500) @Nullable String reason) {}

    record ConvertRequest(
            @NotNull UUID supplierId,
            @NotNull UUID warehouseId,
            @Nullable @Size(max = 500) List<@NotNull UUID> lineIds) {}

    @RequiresPermission(ProcurementPermissions.REQUISITION_READ)
    @GetMapping
    PageResponse<ProcurementResponses.Requisition> list(
            @PathVariable UUID companyId, @RequestParam MultiValueMap<String, String> parameters) {
        return requisitions
                .list(parser.parse(parameters, ProcurementListings.REQUISITIONS))
                .map(r -> ProcurementResponses.Requisition.from(r, null));
    }

    @RequiresPermission(ProcurementPermissions.REQUISITION_READ)
    @GetMapping("/{requisitionId}")
    ResponseEntity<ProcurementResponses.Requisition> get(
            @PathVariable UUID companyId, @PathVariable UUID requisitionId) {
        return ProcurementResponses.Requisition.entity(requisitions.get(requisitionId));
    }

    @RequiresPermission(ProcurementPermissions.REQUISITION_CREATE)
    @PostMapping
    ResponseEntity<ProcurementResponses.Requisition> create(
            @PathVariable UUID companyId, @Valid @RequestBody RequisitionRequest request) {
        var created = requisitions.create(new ProcurementCommands.Requisition(
                request.branchId(),
                request.departmentId(),
                request.neededBy(),
                request.notes(),
                request.lines().stream()
                        .map(l -> new ProcurementCommands.RequisitionLine(
                                l.variantId(),
                                l.description(),
                                l.quantity(),
                                l.uomId(),
                                l.estimatedUnitPrice(),
                                l.suggestedSupplierId()))
                        .toList()));
        return ResponseEntity.created(URI.create(ApiPaths.V1 + "/companies/" + companyId + "/purchase-requisitions/"
                        + created.requisition().id()))
                .eTag(EntityTags.forVersion(created.requisition().version()))
                .body(ProcurementResponses.Requisition.from(created.requisition(), created.lines()));
    }

    @RequiresPermission(ProcurementPermissions.REQUISITION_CREATE)
    @PatchMapping(path = "/{requisitionId}", consumes = ProcurementResponses.MERGE_PATCH)
    ResponseEntity<ProcurementResponses.Requisition> patch(
            @PathVariable UUID companyId,
            @PathVariable UUID requisitionId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch,
            @RequestBody JsonNode patch) {
        return ProcurementResponses.Requisition.entity(requisitions.patch(requisitionId, ifMatch, patch));
    }

    @RequiresPermission(ProcurementPermissions.REQUISITION_CREATE)
    @DeleteMapping("/{requisitionId}")
    ResponseEntity<Void> delete(
            @PathVariable UUID companyId,
            @PathVariable UUID requisitionId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch) {
        requisitions.delete(requisitionId, ifMatch);
        return ResponseEntity.noContent().build();
    }

    @RequiresPermission(ProcurementPermissions.REQUISITION_CREATE)
    @PostMapping("/{requisitionId}/submit")
    ResponseEntity<ProcurementResponses.Requisition> submit(
            @PathVariable UUID companyId,
            @PathVariable UUID requisitionId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch) {
        return ProcurementResponses.Requisition.entity(requisitions.submit(requisitionId, ifMatch));
    }

    @RequiresPermission(ProcurementPermissions.REQUISITION_APPROVE)
    @PostMapping("/{requisitionId}/approve")
    ResponseEntity<ProcurementResponses.Requisition> approve(
            @PathVariable UUID companyId,
            @PathVariable UUID requisitionId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch) {
        return ProcurementResponses.Requisition.entity(requisitions.approve(requisitionId, ifMatch));
    }

    @RequiresPermission(ProcurementPermissions.REQUISITION_APPROVE)
    @PostMapping("/{requisitionId}/reject")
    ResponseEntity<ProcurementResponses.Requisition> reject(
            @PathVariable UUID companyId,
            @PathVariable UUID requisitionId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch,
            @Valid @RequestBody ReasonRequest request) {
        return ProcurementResponses.Requisition.entity(
                requisitions.reject(requisitionId, ifMatch, request.reason().strip()));
    }

    @RequiresPermission(ProcurementPermissions.REQUISITION_CREATE)
    @PostMapping("/{requisitionId}/cancel")
    ResponseEntity<ProcurementResponses.Requisition> cancel(
            @PathVariable UUID companyId,
            @PathVariable UUID requisitionId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch,
            @Valid @RequestBody(required = false) @Nullable OptionalReasonRequest request) {
        return ProcurementResponses.Requisition.entity(
                requisitions.cancel(requisitionId, ifMatch, request == null ? null : request.reason()));
    }

    /** Creates a draft purchase order from requisition lines; answers with the order. */
    @RequiresPermission(ProcurementPermissions.PO_CREATE)
    @PostMapping("/{requisitionId}/convert")
    ResponseEntity<?> convert(
            @PathVariable UUID companyId,
            @PathVariable UUID requisitionId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch,
            @Valid @RequestBody ConvertRequest request,
            HttpServletRequest http) {
        return idempotency.execute(http, request, true, () -> {
            var order = orders.convert(
                    requisitionId,
                    ifMatch,
                    request.supplierId(),
                    request.warehouseId(),
                    request.lineIds() == null ? List.of() : request.lineIds());
            return ResponseEntity.created(URI.create(ApiPaths.V1 + "/companies/" + companyId + "/purchase-orders/"
                            + order.order().id()))
                    .eTag(EntityTags.forVersion(order.order().version()))
                    .body(ProcurementResponses.PurchaseOrder.from(order.order(), order.lines()));
        });
    }
}
