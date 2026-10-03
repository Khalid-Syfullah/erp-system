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
import com.erp.procurement.application.SupplierBillService;
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

/** Supplier bills and debit notes, the three-way match and settlement (API.md §17.6). */
@RestController
@RequestMapping(ApiPaths.V1 + "/companies/{companyId}/supplier-bills")
class SupplierBillController {

    private final SupplierBillService bills;
    private final ListQueryParser parser;
    private final IdempotencyExecutor idempotency;

    SupplierBillController(SupplierBillService bills, ListQueryParser parser, IdempotencyExecutor idempotency) {
        this.bills = bills;
        this.parser = parser;
        this.idempotency = idempotency;
    }

    record LineRequest(
            @Nullable UUID goodsReceiptLineId,
            @Nullable UUID purchaseOrderLineId,
            @Nullable UUID variantId,
            @Size(max = 300) @Nullable String description,

            @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 12, fraction = 6) BigDecimal quantity,

            @Nullable UUID uomId,

            @NotNull @DecimalMin("0") @Digits(integer = 13, fraction = 6) BigDecimal unitPrice,

            @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 4) @Nullable BigDecimal discountPercent,

            @Nullable UUID taxCodeId,
            @Nullable UUID branchId,
            @Nullable UUID departmentId) {}

    record BillRequest(
            @NotBlank @Pattern(regexp = "^(BILL|DEBIT_NOTE)$")
            String documentType,

            @NotNull UUID supplierId,

            @NotBlank @Size(max = 50) String supplierInvoiceNumber,

            @NotNull LocalDate billDate,
            @Nullable LocalDate accountingDate,
            @Nullable LocalDate dueDate,
            @Nullable UUID purchaseOrderId,
            @Nullable UUID originalBillId,
            @Nullable Boolean pricesIncludeTax,
            @Size(max = 2000) @Nullable String notes,

            @NotNull @Size(min = 1, max = 500) List<@Valid @NotNull LineRequest> lines) {}

    record FromReceiptsRequest(
            @NotNull @Size(min = 1, max = 50) List<@NotNull UUID> goodsReceiptIds,

            @NotBlank @Size(max = 50) String supplierInvoiceNumber,
            @Nullable LocalDate billDate) {}

    record ReasonRequest(@NotBlank @Size(max = 500) String reason) {}

    @RequiresPermission(ProcurementPermissions.BILL_READ)
    @GetMapping
    PageResponse<ProcurementResponses.SupplierBill> list(
            @PathVariable UUID companyId, @RequestParam MultiValueMap<String, String> parameters) {
        return bills.list(parser.parse(parameters, ProcurementListings.BILLS))
                .map(b -> ProcurementResponses.SupplierBill.from(b, null, null));
    }

    @RequiresPermission(ProcurementPermissions.BILL_READ)
    @GetMapping("/{billId}")
    ResponseEntity<ProcurementResponses.SupplierBill> get(@PathVariable UUID companyId, @PathVariable UUID billId) {
        return ProcurementResponses.SupplierBill.entity(bills.get(billId));
    }

    @RequiresPermission(ProcurementPermissions.BILL_CREATE)
    @PostMapping
    ResponseEntity<ProcurementResponses.SupplierBill> create(
            @PathVariable UUID companyId, @Valid @RequestBody BillRequest request) {
        var created = bills.create(new ProcurementCommands.SupplierBill(
                request.documentType(),
                request.supplierId(),
                request.supplierInvoiceNumber().strip(),
                request.billDate(),
                request.accountingDate(),
                request.dueDate(),
                request.purchaseOrderId(),
                request.originalBillId(),
                Boolean.TRUE.equals(request.pricesIncludeTax()),
                request.notes(),
                request.lines().stream()
                        .map(l -> new ProcurementCommands.BillLine(
                                l.goodsReceiptLineId(),
                                l.purchaseOrderLineId(),
                                l.variantId(),
                                l.description(),
                                l.quantity(),
                                l.uomId(),
                                l.unitPrice(),
                                l.discountPercent() == null ? BigDecimal.ZERO : l.discountPercent(),
                                l.taxCodeId(),
                                l.branchId(),
                                l.departmentId()))
                        .toList()));
        return created(companyId, created);
    }

    /** A draft bill with everything still unbilled on posted receipts of one supplier. */
    @RequiresPermission(ProcurementPermissions.BILL_CREATE)
    @PostMapping("/from-receipts")
    ResponseEntity<ProcurementResponses.SupplierBill> fromReceipts(
            @PathVariable UUID companyId, @Valid @RequestBody FromReceiptsRequest request) {
        return created(
                companyId,
                bills.fromReceipts(
                        request.goodsReceiptIds(),
                        request.supplierInvoiceNumber().strip(),
                        request.billDate()));
    }

    @RequiresPermission(ProcurementPermissions.BILL_CREATE)
    @PatchMapping(path = "/{billId}", consumes = ProcurementResponses.MERGE_PATCH)
    ResponseEntity<ProcurementResponses.SupplierBill> patch(
            @PathVariable UUID companyId,
            @PathVariable UUID billId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch,
            @RequestBody JsonNode patch) {
        return ProcurementResponses.SupplierBill.entity(bills.patch(billId, ifMatch, patch));
    }

    @RequiresPermission(ProcurementPermissions.BILL_CREATE)
    @DeleteMapping("/{billId}")
    ResponseEntity<Void> delete(
            @PathVariable UUID companyId,
            @PathVariable UUID billId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch) {
        bills.delete(billId, ifMatch);
        return ResponseEntity.noContent().build();
    }

    @RequiresPermission(ProcurementPermissions.BILL_CREATE)
    @PostMapping("/{billId}/check-match")
    ProcurementResponses.MatchResult checkMatch(
            @PathVariable UUID companyId,
            @PathVariable UUID billId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch) {
        return ProcurementResponses.MatchResult.from(bills.checkMatch(billId, ifMatch));
    }

    @RequiresPermission(ProcurementPermissions.BILL_OVERRIDE_MATCH)
    @PostMapping("/{billId}/override-match")
    ResponseEntity<ProcurementResponses.SupplierBill> overrideMatch(
            @PathVariable UUID companyId,
            @PathVariable UUID billId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch,
            @Valid @RequestBody ReasonRequest request) {
        return ProcurementResponses.SupplierBill.entity(
                bills.overrideMatch(billId, ifMatch, request.reason().strip()));
    }

    @RequiresPermission(ProcurementPermissions.BILL_POST)
    @PostMapping("/{billId}/post")
    ResponseEntity<?> post(
            @PathVariable UUID companyId,
            @PathVariable UUID billId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch,
            HttpServletRequest http) {
        return idempotency.execute(
                http, null, true, () -> ProcurementResponses.SupplierBill.entity(bills.post(billId, ifMatch)));
    }

    @RequiresPermission(ProcurementPermissions.BILL_POST)
    @PostMapping("/{billId}/cancel")
    ResponseEntity<ProcurementResponses.SupplierBill> cancel(
            @PathVariable UUID companyId,
            @PathVariable UUID billId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch) {
        return ProcurementResponses.SupplierBill.entity(bills.cancel(billId, ifMatch));
    }

    /** Open amount through Accounting's port; {@code UNKNOWN} until Accounting exists (Phase 8). */
    @RequiresPermission(ProcurementPermissions.BILL_READ)
    @GetMapping("/{billId}/settlement")
    ProcurementResponses.Settlement settlement(@PathVariable UUID companyId, @PathVariable UUID billId) {
        var s = bills.settlement(billId);
        return new ProcurementResponses.Settlement(s.billId(), s.status().name(), s.openAmount());
    }

    private static ResponseEntity<ProcurementResponses.SupplierBill> created(
            UUID companyId, com.erp.procurement.application.ProcurementViews.SupplierBillDetail created) {
        return ResponseEntity.created(URI.create(ApiPaths.V1 + "/companies/" + companyId + "/supplier-bills/"
                        + created.bill().id()))
                .eTag(EntityTags.forVersion(created.bill().version()))
                .body(ProcurementResponses.SupplierBill.from(created.bill(), created.lines(), created.taxes()));
    }
}
