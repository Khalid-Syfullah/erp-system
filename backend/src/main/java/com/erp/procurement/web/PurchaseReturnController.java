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
import com.erp.procurement.application.PurchaseReturnService;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Purchase returns of received goods (API.md §17.6). */
@RestController
@RequestMapping(ApiPaths.V1 + "/companies/{companyId}/purchase-returns")
class PurchaseReturnController {

    private final PurchaseReturnService returns;
    private final ListQueryParser parser;
    private final IdempotencyExecutor idempotency;

    PurchaseReturnController(PurchaseReturnService returns, ListQueryParser parser, IdempotencyExecutor idempotency) {
        this.returns = returns;
        this.parser = parser;
        this.idempotency = idempotency;
    }

    record LineRequest(
            @NotNull UUID goodsReceiptLineId,

            @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 12, fraction = 6) BigDecimal quantity,

            @Nullable UUID uomId) {}

    record ReturnRequest(
            @NotNull UUID goodsReceiptId,
            @Nullable LocalDate returnDate,

            @NotBlank @Size(max = 500) String reason,

            @NotNull @Size(min = 1, max = 500) List<@Valid @NotNull LineRequest> lines) {}

    @RequiresPermission(ProcurementPermissions.RETURN_MANAGE)
    @GetMapping
    PageResponse<ProcurementResponses.PurchaseReturn> list(
            @PathVariable UUID companyId, @RequestParam MultiValueMap<String, String> parameters) {
        return returns.list(parser.parse(parameters, ProcurementListings.RETURNS))
                .map(r -> ProcurementResponses.PurchaseReturn.from(r, null));
    }

    @RequiresPermission(ProcurementPermissions.RETURN_MANAGE)
    @GetMapping("/{returnId}")
    ResponseEntity<ProcurementResponses.PurchaseReturn> get(@PathVariable UUID companyId, @PathVariable UUID returnId) {
        return ProcurementResponses.PurchaseReturn.entity(returns.get(returnId));
    }

    @RequiresPermission(ProcurementPermissions.RETURN_MANAGE)
    @PostMapping
    ResponseEntity<ProcurementResponses.PurchaseReturn> create(
            @PathVariable UUID companyId, @Valid @RequestBody ReturnRequest request) {
        var created = returns.create(new ProcurementCommands.PurchaseReturn(
                request.goodsReceiptId(),
                request.returnDate(),
                request.reason().strip(),
                request.lines().stream()
                        .map(l -> new ProcurementCommands.ReturnLine(l.goodsReceiptLineId(), l.quantity(), l.uomId()))
                        .toList()));
        return ResponseEntity.created(URI.create(ApiPaths.V1 + "/companies/" + companyId + "/purchase-returns/"
                        + created.purchaseReturn().id()))
                .eTag(EntityTags.forVersion(created.purchaseReturn().version()))
                .body(ProcurementResponses.PurchaseReturn.from(created.purchaseReturn(), created.lines()));
    }

    @RequiresPermission(ProcurementPermissions.RETURN_MANAGE)
    @PostMapping("/{returnId}/post")
    ResponseEntity<?> post(
            @PathVariable UUID companyId,
            @PathVariable UUID returnId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch,
            HttpServletRequest http) {
        return idempotency.execute(
                http, null, true, () -> ProcurementResponses.PurchaseReturn.entity(returns.post(returnId, ifMatch)));
    }

    @RequiresPermission(ProcurementPermissions.RETURN_MANAGE)
    @PostMapping("/{returnId}/cancel")
    ResponseEntity<?> cancel(
            @PathVariable UUID companyId,
            @PathVariable UUID returnId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch,
            HttpServletRequest http) {
        return idempotency.execute(
                http, null, true, () -> ProcurementResponses.PurchaseReturn.entity(returns.cancel(returnId, ifMatch)));
    }
}
