package com.erp.sales.web;

import com.erp.platform.idempotency.IdempotencyExecutor;
import com.erp.platform.security.RequiresPermission;
import com.erp.platform.web.ApiPaths;
import com.erp.platform.web.EntityTags;
import com.erp.platform.web.paging.ListQueryParser;
import com.erp.platform.web.paging.PageResponse;
import com.erp.sales.SalesPermissions;
import com.erp.sales.application.SalesCommands;
import com.erp.sales.application.SalesListings;
import com.erp.sales.application.SalesOrderService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
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

/** Sales orders and their workflow (API.md §17.7). */
@RestController
@RequestMapping(ApiPaths.V1 + "/companies/{companyId}/sales-orders")
class SalesOrderController {

    private final SalesOrderService orders;
    private final ListQueryParser parser;
    private final IdempotencyExecutor idempotency;

    SalesOrderController(SalesOrderService orders, ListQueryParser parser, IdempotencyExecutor idempotency) {
        this.orders = orders;
        this.parser = parser;
        this.idempotency = idempotency;
    }

    record OrderRequest(
            @NotNull UUID customerId,
            @NotNull UUID warehouseId,
            @Nullable LocalDate orderDate,
            @Nullable LocalDate requestedDate,
            @Size(max = 100) @Nullable String customerReference,
            @Pattern(regexp = "^[A-Z]{3}$") @Nullable String currencyCode,
            @Nullable UUID priceListId,
            @Nullable UUID paymentTermsId,

            @Pattern(regexp = "^(ORDERED|DELIVERED)$") @Nullable String invoicePolicy,

            @Size(max = 2000) @Nullable String notes,
            @NotNull @Size(min = 1, max = 500) List<SalesRequests.@Valid @NotNull PricedLine> lines) {}

    record ConfirmRequest(@Valid SalesRequests.@Nullable Reason overrideCredit) {}

    @RequiresPermission(SalesPermissions.ORDER_READ)
    @GetMapping
    PageResponse<SalesResponses.SalesOrder> list(
            @PathVariable UUID companyId, @RequestParam MultiValueMap<String, String> parameters) {
        return orders.list(parser.parse(parameters, SalesListings.ORDERS))
                .map(o -> SalesResponses.SalesOrder.from(o, null));
    }

    @RequiresPermission(SalesPermissions.ORDER_READ)
    @GetMapping("/{orderId}")
    ResponseEntity<SalesResponses.SalesOrder> get(@PathVariable UUID companyId, @PathVariable UUID orderId) {
        return SalesResponses.SalesOrder.entity(orders.get(orderId));
    }

    @RequiresPermission(SalesPermissions.ORDER_READ)
    @GetMapping("/{orderId}/credit-check")
    SalesResponses.CreditCheck creditCheck(@PathVariable UUID companyId, @PathVariable UUID orderId) {
        return SalesResponses.CreditCheck.from(orders.creditCheck(orderId));
    }

    @RequiresPermission(SalesPermissions.ORDER_CREATE)
    @PostMapping
    ResponseEntity<SalesResponses.SalesOrder> create(
            @PathVariable UUID companyId, @Valid @RequestBody OrderRequest request) {
        var created = orders.create(new SalesCommands.SalesOrder(
                request.customerId(),
                request.warehouseId(),
                request.orderDate(),
                request.requestedDate(),
                request.customerReference(),
                request.currencyCode(),
                request.priceListId(),
                request.paymentTermsId(),
                request.invoicePolicy(),
                request.notes(),
                request.lines().stream().map(SalesRequests.PricedLine::command).toList()));
        return ResponseEntity.created(URI.create(ApiPaths.V1 + "/companies/" + companyId + "/sales-orders/"
                        + created.order().id()))
                .eTag(EntityTags.forVersion(created.order().version()))
                .body(SalesResponses.SalesOrder.from(created.order(), created.lines()));
    }

    @RequiresPermission(SalesPermissions.ORDER_CREATE)
    @PatchMapping(path = "/{orderId}", consumes = SalesResponses.MERGE_PATCH)
    ResponseEntity<SalesResponses.SalesOrder> patch(
            @PathVariable UUID companyId,
            @PathVariable UUID orderId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch,
            @RequestBody JsonNode patch) {
        return SalesResponses.SalesOrder.entity(orders.patch(orderId, ifMatch, patch));
    }

    @RequiresPermission(SalesPermissions.ORDER_CREATE)
    @DeleteMapping("/{orderId}")
    ResponseEntity<Void> delete(
            @PathVariable UUID companyId,
            @PathVariable UUID orderId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch) {
        orders.delete(orderId, ifMatch);
        return ResponseEntity.noContent().build();
    }

    @RequiresPermission(SalesPermissions.ORDER_CONFIRM)
    @PostMapping("/{orderId}/confirm")
    ResponseEntity<?> confirm(
            @PathVariable UUID companyId,
            @PathVariable UUID orderId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch,
            @Valid @RequestBody(required = false) @Nullable ConfirmRequest request,
            HttpServletRequest http) {
        String reason = request == null || request.overrideCredit() == null
                ? null
                : request.overrideCredit().reason();
        return idempotency.execute(
                http, request, true, () -> SalesResponses.SalesOrder.entity(orders.confirm(orderId, ifMatch, reason)));
    }

    @RequiresPermission(SalesPermissions.ORDER_CONFIRM)
    @PostMapping("/{orderId}/reserve")
    ResponseEntity<SalesResponses.SalesOrder> reserve(
            @PathVariable UUID companyId,
            @PathVariable UUID orderId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch) {
        return SalesResponses.SalesOrder.entity(orders.reserve(orderId, ifMatch));
    }

    @RequiresPermission(SalesPermissions.ORDER_CANCEL)
    @PostMapping("/{orderId}/cancel")
    ResponseEntity<SalesResponses.SalesOrder> cancel(
            @PathVariable UUID companyId,
            @PathVariable UUID orderId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch,
            @Valid @RequestBody(required = false) SalesRequests.@Nullable OptionalReason request) {
        return SalesResponses.SalesOrder.entity(
                orders.cancel(orderId, ifMatch, request == null ? null : request.reason()));
    }

    @RequiresPermission(SalesPermissions.ORDER_CLOSE)
    @PostMapping("/{orderId}/close")
    ResponseEntity<SalesResponses.SalesOrder> close(
            @PathVariable UUID companyId,
            @PathVariable UUID orderId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch,
            @Valid @RequestBody(required = false) SalesRequests.@Nullable OptionalReason request) {
        return SalesResponses.SalesOrder.entity(
                orders.close(orderId, ifMatch, request == null ? null : request.reason()));
    }
}
