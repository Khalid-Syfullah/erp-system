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
import com.erp.sales.application.SalesReturnService;
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

/** Sales returns (API.md §17.7, SAL-7). */
@RestController
@RequestMapping(ApiPaths.V1 + "/companies/{companyId}/sales-returns")
class SalesReturnController {

    private final SalesReturnService returns;
    private final ListQueryParser parser;
    private final IdempotencyExecutor idempotency;

    SalesReturnController(SalesReturnService returns, ListQueryParser parser, IdempotencyExecutor idempotency) {
        this.returns = returns;
        this.parser = parser;
        this.idempotency = idempotency;
    }

    record LineRequest(
            @NotNull UUID deliveryLineId,

            @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 12, fraction = 6) BigDecimal quantity,

            @Nullable UUID uomId) {}

    record ReturnRequest(
            @NotNull UUID deliveryId,
            @Nullable LocalDate returnDate,
            @NotBlank @Size(max = 500) String reason,
            @NotNull @Size(min = 1, max = 500) List<@Valid @NotNull LineRequest> lines) {}

    @RequiresPermission(SalesPermissions.RETURN_MANAGE)
    @GetMapping
    PageResponse<SalesResponses.SalesReturn> list(
            @PathVariable UUID companyId, @RequestParam MultiValueMap<String, String> parameters) {
        return returns.list(parser.parse(parameters, SalesListings.RETURNS))
                .map(r -> SalesResponses.SalesReturn.from(r, null));
    }

    @RequiresPermission(SalesPermissions.RETURN_MANAGE)
    @GetMapping("/{returnId}")
    ResponseEntity<SalesResponses.SalesReturn> get(@PathVariable UUID companyId, @PathVariable UUID returnId) {
        return SalesResponses.SalesReturn.entity(returns.get(returnId));
    }

    @RequiresPermission(SalesPermissions.RETURN_MANAGE)
    @PostMapping
    ResponseEntity<SalesResponses.SalesReturn> create(
            @PathVariable UUID companyId, @Valid @RequestBody ReturnRequest request) {
        var created = returns.create(new SalesCommands.SalesReturn(
                request.deliveryId(),
                request.returnDate(),
                request.reason(),
                request.lines().stream()
                        .map(l -> new SalesCommands.ReturnLine(l.deliveryLineId(), l.quantity(), l.uomId()))
                        .toList()));
        return ResponseEntity.created(URI.create(ApiPaths.V1 + "/companies/" + companyId + "/sales-returns/"
                        + created.salesReturn().id()))
                .eTag(EntityTags.forVersion(created.salesReturn().version()))
                .body(SalesResponses.SalesReturn.from(created.salesReturn(), created.lines()));
    }

    @RequiresPermission(SalesPermissions.RETURN_MANAGE)
    @PostMapping("/{returnId}/receive")
    ResponseEntity<?> receive(
            @PathVariable UUID companyId,
            @PathVariable UUID returnId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch,
            HttpServletRequest http) {
        return idempotency.execute(
                http, null, true, () -> SalesResponses.SalesReturn.entity(returns.receive(returnId, ifMatch)));
    }

    @RequiresPermission(SalesPermissions.RETURN_MANAGE)
    @PostMapping("/{returnId}/cancel")
    ResponseEntity<SalesResponses.SalesReturn> cancel(
            @PathVariable UUID companyId,
            @PathVariable UUID returnId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch) {
        return SalesResponses.SalesReturn.entity(returns.cancel(returnId, ifMatch));
    }
}
