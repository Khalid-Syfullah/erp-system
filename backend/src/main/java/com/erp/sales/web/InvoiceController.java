package com.erp.sales.web;

import com.erp.platform.idempotency.IdempotencyExecutor;
import com.erp.platform.security.RequiresPermission;
import com.erp.platform.web.ApiPaths;
import com.erp.platform.web.EntityTags;
import com.erp.platform.web.paging.ListQueryParser;
import com.erp.platform.web.paging.PageResponse;
import com.erp.sales.SalesPermissions;
import com.erp.sales.application.InvoiceService;
import com.erp.sales.application.SalesCommands;
import com.erp.sales.application.SalesListings;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
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

/** Customer invoices and credit notes (API.md §17.7, SAL-5, SAL-6). */
@RestController
@RequestMapping(ApiPaths.V1 + "/companies/{companyId}/invoices")
class InvoiceController {

    private final InvoiceService invoices;
    private final ListQueryParser parser;
    private final IdempotencyExecutor idempotency;

    InvoiceController(InvoiceService invoices, ListQueryParser parser, IdempotencyExecutor idempotency) {
        this.invoices = invoices;
        this.parser = parser;
        this.idempotency = idempotency;
    }

    record LineRequest(
            @Nullable UUID salesOrderLineId,
            @Nullable UUID originalInvoiceLineId,
            @Nullable UUID salesReturnLineId,
            @Nullable UUID variantId,
            @Size(max = 300) @Nullable String description,

            @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 12, fraction = 6) BigDecimal quantity,

            @Nullable UUID uomId,

            @DecimalMin("0") @Digits(integer = 13, fraction = 6) @Nullable BigDecimal unitPrice,

            @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 4) @Nullable BigDecimal discountPercent,

            @Nullable UUID taxCodeId,
            @Nullable UUID branchId,
            @Nullable UUID departmentId) {}

    record InvoiceRequest(
            @NotNull @Pattern(regexp = "^(INVOICE|CREDIT_NOTE)$")
            String documentType,

            @NotNull UUID customerId,
            @Nullable LocalDate invoiceDate,
            @Nullable LocalDate accountingDate,
            @Nullable LocalDate dueDate,
            @Nullable UUID salesOrderId,
            @Nullable UUID originalInvoiceId,
            @Nullable UUID salesReturnId,
            @Size(max = 2000) @Nullable String notes,
            @NotNull @Size(min = 1, max = 500) List<@Valid @NotNull LineRequest> lines) {}

    record FromOrderRequest(
            @NotNull UUID salesOrderId,
            @Size(max = 100) @Nullable List<@NotNull UUID> deliveryIds) {}

    @RequiresPermission(SalesPermissions.INVOICE_READ)
    @GetMapping
    PageResponse<SalesResponses.Invoice> list(
            @PathVariable UUID companyId, @RequestParam MultiValueMap<String, String> parameters) {
        return invoices.list(parser.parse(parameters, SalesListings.INVOICES))
                .map(i -> SalesResponses.Invoice.from(i, null, null));
    }

    @RequiresPermission(SalesPermissions.INVOICE_READ)
    @GetMapping("/{invoiceId}")
    ResponseEntity<SalesResponses.Invoice> get(@PathVariable UUID companyId, @PathVariable UUID invoiceId) {
        return SalesResponses.Invoice.entity(invoices.get(invoiceId));
    }

    @RequiresPermission(SalesPermissions.INVOICE_READ)
    @GetMapping("/{invoiceId}/settlement")
    SalesResponses.Settlement settlement(@PathVariable UUID companyId, @PathVariable UUID invoiceId) {
        return SalesResponses.Settlement.from(invoices.settlement(invoiceId));
    }

    @RequiresPermission(SalesPermissions.INVOICE_CREATE)
    @PostMapping
    ResponseEntity<SalesResponses.Invoice> create(
            @PathVariable UUID companyId, @Valid @RequestBody InvoiceRequest request) {
        var created = invoices.create(new SalesCommands.Invoice(
                request.documentType(),
                request.customerId(),
                request.invoiceDate(),
                request.accountingDate(),
                request.dueDate(),
                request.salesOrderId(),
                request.originalInvoiceId(),
                request.salesReturnId(),
                request.notes(),
                request.lines().stream()
                        .map(l -> new SalesCommands.InvoiceLine(
                                l.salesOrderLineId(),
                                l.originalInvoiceLineId(),
                                l.salesReturnLineId(),
                                l.variantId(),
                                l.description(),
                                l.quantity(),
                                l.uomId(),
                                l.unitPrice(),
                                l.discountPercent(),
                                l.taxCodeId(),
                                l.branchId(),
                                l.departmentId()))
                        .toList()));
        return created(companyId, created);
    }

    @RequiresPermission(SalesPermissions.INVOICE_CREATE)
    @PostMapping("/from-order")
    ResponseEntity<SalesResponses.Invoice> fromOrder(
            @PathVariable UUID companyId, @Valid @RequestBody FromOrderRequest request) {
        return created(companyId, invoices.fromOrder(request.salesOrderId(), request.deliveryIds()));
    }

    @RequiresPermission(SalesPermissions.INVOICE_CREATE)
    @PatchMapping(path = "/{invoiceId}", consumes = SalesResponses.MERGE_PATCH)
    ResponseEntity<SalesResponses.Invoice> patch(
            @PathVariable UUID companyId,
            @PathVariable UUID invoiceId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch,
            @RequestBody JsonNode patch) {
        return SalesResponses.Invoice.entity(invoices.patch(invoiceId, ifMatch, patch));
    }

    @RequiresPermission(SalesPermissions.INVOICE_CREATE)
    @DeleteMapping("/{invoiceId}")
    ResponseEntity<Void> delete(
            @PathVariable UUID companyId,
            @PathVariable UUID invoiceId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch) {
        invoices.delete(invoiceId, ifMatch);
        return ResponseEntity.noContent().build();
    }

    @RequiresPermission(SalesPermissions.INVOICE_POST)
    @PostMapping("/{invoiceId}/post")
    ResponseEntity<?> post(
            @PathVariable UUID companyId,
            @PathVariable UUID invoiceId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch,
            HttpServletRequest http) {
        return idempotency.execute(
                http, null, true, () -> SalesResponses.Invoice.entity(invoices.post(invoiceId, ifMatch)));
    }

    @RequiresPermission(SalesPermissions.INVOICE_POST)
    @PostMapping("/{invoiceId}/cancel")
    ResponseEntity<SalesResponses.Invoice> cancel(
            @PathVariable UUID companyId,
            @PathVariable UUID invoiceId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch) {
        return SalesResponses.Invoice.entity(invoices.cancel(invoiceId, ifMatch));
    }

    private static ResponseEntity<SalesResponses.Invoice> created(
            UUID companyId, com.erp.sales.application.SalesViews.InvoiceDetail created) {
        return ResponseEntity.created(URI.create(ApiPaths.V1 + "/companies/" + companyId + "/invoices/"
                        + created.invoice().id()))
                .eTag(EntityTags.forVersion(created.invoice().version()))
                .body(SalesResponses.Invoice.from(created.invoice(), created.lines(), created.taxes()));
    }
}
