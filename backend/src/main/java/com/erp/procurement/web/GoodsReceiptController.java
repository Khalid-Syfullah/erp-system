package com.erp.procurement.web;

import com.erp.platform.idempotency.IdempotencyExecutor;
import com.erp.platform.security.RequiresPermission;
import com.erp.platform.web.ApiPaths;
import com.erp.platform.web.EntityTags;
import com.erp.platform.web.paging.ListQueryParser;
import com.erp.platform.web.paging.PageResponse;
import com.erp.procurement.ProcurementPermissions;
import com.erp.procurement.application.GoodsReceiptService;
import com.erp.procurement.application.ProcurementCommands;
import com.erp.procurement.application.ProcurementListings;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
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

/** Goods receipts against purchase orders (API.md §17.6). Posting requires an Idempotency-Key. */
@RestController
@RequestMapping(ApiPaths.V1 + "/companies/{companyId}/goods-receipts")
class GoodsReceiptController {

    private final GoodsReceiptService receipts;
    private final ListQueryParser parser;
    private final IdempotencyExecutor idempotency;

    GoodsReceiptController(GoodsReceiptService receipts, ListQueryParser parser, IdempotencyExecutor idempotency) {
        this.receipts = receipts;
        this.parser = parser;
        this.idempotency = idempotency;
    }

    record LineRequest(
            @NotNull UUID purchaseOrderLineId,

            @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 12, fraction = 6) BigDecimal quantity,

            @Nullable UUID uomId,
            @Nullable UUID locationId) {}

    /** {@code lines} omitted: everything still open on the order's stockable lines. */
    record ReceiptRequest(
            @NotNull UUID purchaseOrderId,
            @Nullable LocalDate receiptDate,
            @Size(max = 100) @Nullable String supplierDeliveryNote,
            @Size(max = 2000) @Nullable String notes,
            @Nullable @Size(min = 1, max = 500) List<@Valid @NotNull LineRequest> lines) {}

    @RequiresPermission(ProcurementPermissions.RECEIPT_READ)
    @GetMapping
    PageResponse<ProcurementResponses.GoodsReceipt> list(
            @PathVariable UUID companyId, @RequestParam MultiValueMap<String, String> parameters) {
        return receipts.list(parser.parse(parameters, ProcurementListings.RECEIPTS))
                .map(r -> ProcurementResponses.GoodsReceipt.from(r, null));
    }

    @RequiresPermission(ProcurementPermissions.RECEIPT_READ)
    @GetMapping("/{receiptId}")
    ResponseEntity<ProcurementResponses.GoodsReceipt> get(@PathVariable UUID companyId, @PathVariable UUID receiptId) {
        return ProcurementResponses.GoodsReceipt.entity(receipts.get(receiptId));
    }

    @RequiresPermission(ProcurementPermissions.RECEIPT_CREATE)
    @PostMapping
    ResponseEntity<ProcurementResponses.GoodsReceipt> create(
            @PathVariable UUID companyId, @Valid @RequestBody ReceiptRequest request) {
        var created = receipts.create(new ProcurementCommands.Receipt(
                request.purchaseOrderId(),
                request.receiptDate(),
                request.supplierDeliveryNote(),
                request.notes(),
                request.lines() == null
                        ? null
                        : request.lines().stream()
                                .map(l -> new ProcurementCommands.ReceiptLine(
                                        l.purchaseOrderLineId(), l.quantity(), l.uomId(), l.locationId()))
                                .toList()));
        return ResponseEntity.created(URI.create(ApiPaths.V1 + "/companies/" + companyId + "/goods-receipts/"
                        + created.receipt().id()))
                .eTag(EntityTags.forVersion(created.receipt().version()))
                .body(ProcurementResponses.GoodsReceipt.from(created.receipt(), created.lines()));
    }

    @RequiresPermission(ProcurementPermissions.RECEIPT_CREATE)
    @PatchMapping(path = "/{receiptId}", consumes = ProcurementResponses.MERGE_PATCH)
    ResponseEntity<ProcurementResponses.GoodsReceipt> patch(
            @PathVariable UUID companyId,
            @PathVariable UUID receiptId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch,
            @RequestBody JsonNode patch) {
        return ProcurementResponses.GoodsReceipt.entity(receipts.patch(receiptId, ifMatch, patch));
    }

    @RequiresPermission(ProcurementPermissions.RECEIPT_CREATE)
    @DeleteMapping("/{receiptId}")
    ResponseEntity<Void> delete(
            @PathVariable UUID companyId,
            @PathVariable UUID receiptId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch) {
        receipts.delete(receiptId, ifMatch);
        return ResponseEntity.noContent().build();
    }

    @RequiresPermission(ProcurementPermissions.RECEIPT_POST)
    @PostMapping("/{receiptId}/post")
    ResponseEntity<?> post(
            @PathVariable UUID companyId,
            @PathVariable UUID receiptId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch,
            HttpServletRequest http) {
        return idempotency.execute(
                http, null, true, () -> ProcurementResponses.GoodsReceipt.entity(receipts.post(receiptId, ifMatch)));
    }

    @RequiresPermission(ProcurementPermissions.RECEIPT_POST)
    @PostMapping("/{receiptId}/cancel")
    ResponseEntity<ProcurementResponses.GoodsReceipt> cancel(
            @PathVariable UUID companyId,
            @PathVariable UUID receiptId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch) {
        return ProcurementResponses.GoodsReceipt.entity(receipts.cancel(receiptId, ifMatch));
    }
}
