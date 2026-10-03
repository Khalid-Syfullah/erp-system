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
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
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

/** Purchase orders and their workflow (API.md §17.6). */
@RestController
@RequestMapping(ApiPaths.V1 + "/companies/{companyId}/purchase-orders")
class PurchaseOrderController {

    private final PurchaseOrderService orders;
    private final ListQueryParser parser;
    private final IdempotencyExecutor idempotency;

    PurchaseOrderController(PurchaseOrderService orders, ListQueryParser parser, IdempotencyExecutor idempotency) {
        this.orders = orders;
        this.parser = parser;
        this.idempotency = idempotency;
    }

    record LineRequest(
            @NotNull UUID variantId,
            @Size(max = 300) @Nullable String description,

            @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 12, fraction = 6) BigDecimal quantity,

            @NotNull UUID uomId,

            @NotNull @DecimalMin("0") @Digits(integer = 13, fraction = 6) BigDecimal unitPrice,

            @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 4) @Nullable BigDecimal discountPercent,

            @Nullable UUID taxCodeId,
            @Nullable UUID requisitionLineId) {}

    record OrderRequest(
            @NotNull UUID supplierId,
            @NotNull UUID warehouseId,
            @Nullable UUID departmentId,
            @Nullable LocalDate orderDate,
            @Nullable LocalDate expectedDate,
            @Pattern(regexp = "^[A-Z]{3}$") @Nullable String currencyCode,
            @Nullable UUID paymentTermsId,
            @Nullable Boolean pricesIncludeTax,
            @Size(max = 2000) @Nullable String notes,

            @NotNull @Size(min = 1, max = 500) List<@Valid @NotNull LineRequest> lines) {}

    record ReasonRequest(@NotBlank @Size(max = 500) String reason) {}

    record OptionalReasonRequest(@Size(max = 500) @Nullable String reason) {}

    @RequiresPermission(ProcurementPermissions.PO_READ)
    @GetMapping
    PageResponse<ProcurementResponses.PurchaseOrder> list(
            @PathVariable UUID companyId, @RequestParam MultiValueMap<String, String> parameters) {
        return orders.list(parser.parse(parameters, ProcurementListings.PURCHASE_ORDERS))
                .map(o -> ProcurementResponses.PurchaseOrder.from(o, null));
    }

    @RequiresPermission(ProcurementPermissions.PO_READ)
    @GetMapping("/{orderId}")
    ResponseEntity<ProcurementResponses.PurchaseOrder> get(@PathVariable UUID companyId, @PathVariable UUID orderId) {
        return ProcurementResponses.PurchaseOrder.entity(orders.get(orderId));
    }

    @RequiresPermission(ProcurementPermissions.PO_CREATE)
    @PostMapping
    ResponseEntity<ProcurementResponses.PurchaseOrder> create(
            @PathVariable UUID companyId, @Valid @RequestBody OrderRequest request) {
        var created = orders.create(new ProcurementCommands.PurchaseOrder(
                request.supplierId(),
                request.warehouseId(),
                request.departmentId(),
                request.orderDate(),
                request.expectedDate(),
                request.currencyCode(),
                request.paymentTermsId(),
                Boolean.TRUE.equals(request.pricesIncludeTax()),
                request.notes(),
                request.lines().stream()
                        .map(l -> new ProcurementCommands.OrderLine(
                                l.variantId(),
                                l.description(),
                                l.quantity(),
                                l.uomId(),
                                l.unitPrice(),
                                l.discountPercent() == null ? BigDecimal.ZERO : l.discountPercent(),
                                l.taxCodeId(),
                                l.requisitionLineId()))
                        .toList()));
        return ResponseEntity.created(URI.create(ApiPaths.V1 + "/companies/" + companyId + "/purchase-orders/"
                        + created.order().id()))
                .eTag(EntityTags.forVersion(created.order().version()))
                .body(ProcurementResponses.PurchaseOrder.from(created.order(), created.lines()));
    }

    @RequiresPermission(ProcurementPermissions.PO_CREATE)
    @PatchMapping(path = "/{orderId}", consumes = ProcurementResponses.MERGE_PATCH)
    ResponseEntity<ProcurementResponses.PurchaseOrder> patch(
            @PathVariable UUID companyId,
            @PathVariable UUID orderId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch,
            @RequestBody JsonNode patch) {
        return ProcurementResponses.PurchaseOrder.entity(orders.patch(orderId, ifMatch, patch));
    }

    @RequiresPermission(ProcurementPermissions.PO_CREATE)
    @DeleteMapping("/{orderId}")
    ResponseEntity<Void> delete(
            @PathVariable UUID companyId,
            @PathVariable UUID orderId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch) {
        orders.delete(orderId, ifMatch);
        return ResponseEntity.noContent().build();
    }

    @RequiresPermission(ProcurementPermissions.PO_CREATE)
    @PostMapping("/{orderId}/submit")
    ResponseEntity<ProcurementResponses.PurchaseOrder> submit(
            @PathVariable UUID companyId,
            @PathVariable UUID orderId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch) {
        return ProcurementResponses.PurchaseOrder.entity(orders.submit(orderId, ifMatch));
    }

    @RequiresPermission(ProcurementPermissions.PO_APPROVE)
    @PostMapping("/{orderId}/approve")
    ResponseEntity<?> approve(
            @PathVariable UUID companyId,
            @PathVariable UUID orderId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch,
            HttpServletRequest http) {
        return idempotency.execute(
                http, null, true, () -> ProcurementResponses.PurchaseOrder.entity(orders.approve(orderId, ifMatch)));
    }

    @RequiresPermission(ProcurementPermissions.PO_APPROVE)
    @PostMapping("/{orderId}/reject")
    ResponseEntity<ProcurementResponses.PurchaseOrder> reject(
            @PathVariable UUID companyId,
            @PathVariable UUID orderId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch,
            @Valid @RequestBody ReasonRequest request) {
        return ProcurementResponses.PurchaseOrder.entity(
                orders.reject(orderId, ifMatch, request.reason().strip()));
    }

    @RequiresPermission(ProcurementPermissions.PO_CANCEL)
    @PostMapping("/{orderId}/cancel")
    ResponseEntity<ProcurementResponses.PurchaseOrder> cancel(
            @PathVariable UUID companyId,
            @PathVariable UUID orderId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch,
            @Valid @RequestBody(required = false) @Nullable OptionalReasonRequest request) {
        return ProcurementResponses.PurchaseOrder.entity(
                orders.cancel(orderId, ifMatch, request == null ? null : request.reason()));
    }

    @RequiresPermission(ProcurementPermissions.PO_CLOSE)
    @PostMapping("/{orderId}/close")
    ResponseEntity<ProcurementResponses.PurchaseOrder> close(
            @PathVariable UUID companyId,
            @PathVariable UUID orderId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch,
            @Valid @RequestBody(required = false) @Nullable OptionalReasonRequest request) {
        return ProcurementResponses.PurchaseOrder.entity(
                orders.close(orderId, ifMatch, request == null ? null : request.reason()));
    }
}
