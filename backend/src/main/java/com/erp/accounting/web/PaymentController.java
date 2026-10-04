package com.erp.accounting.web;

import com.erp.accounting.AccountingPermissions;
import com.erp.accounting.application.AccountingCommands;
import com.erp.accounting.application.AccountingListings;
import com.erp.accounting.application.PaymentService;
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

/** Customer receipts and supplier payments (API.md §17.8, PRODUCT_SPEC.md §8.8). */
@RestController
@RequestMapping(ApiPaths.V1 + "/companies/{companyId}/payments")
class PaymentController {

    private final PaymentService payments;
    private final ListQueryParser parser;
    private final IdempotencyExecutor idempotency;

    PaymentController(PaymentService payments, ListQueryParser parser, IdempotencyExecutor idempotency) {
        this.payments = payments;
        this.parser = parser;
        this.idempotency = idempotency;
    }

    record AllocationRequest(
            @NotNull UUID openItemId,

            @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 15, fraction = 4) BigDecimal amount) {}

    record PaymentRequest(
            @NotNull @Pattern(regexp = "^(INBOUND|OUTBOUND)$")
            String direction,

            @NotNull UUID partnerId,
            @NotNull UUID bankAccountId,
            @Nullable LocalDate paymentDate,

            @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 15, fraction = 4) BigDecimal amount,

            @NotNull @Pattern(regexp = "^(CASH|BANK_TRANSFER|CHEQUE|CARD|OTHER)$")
            String method,

            @Size(max = 100) @Nullable String reference,
            @Size(max = 2000) @Nullable String notes,
            @Size(max = 500) @Nullable List<@Valid @NotNull AllocationRequest> allocations) {}

    record AllocationsRequest(
            @NotNull @Size(min = 1, max = 500) List<@Valid @NotNull AllocationRequest> allocations) {}

    record VoidRequest(@NotBlank @Size(max = 500) String reason) {}

    @RequiresPermission(AccountingPermissions.PAYMENT_READ)
    @GetMapping
    PageResponse<AccountingResponses.Payment> list(
            @PathVariable UUID companyId, @RequestParam MultiValueMap<String, String> parameters) {
        return payments.list(parser.parse(parameters, AccountingListings.PAYMENTS))
                .map(p -> AccountingResponses.Payment.from(p, null, null));
    }

    @RequiresPermission(AccountingPermissions.PAYMENT_READ)
    @GetMapping("/{paymentId}")
    ResponseEntity<AccountingResponses.Payment> get(@PathVariable UUID companyId, @PathVariable UUID paymentId) {
        return AccountingResponses.Payment.entity(payments.get(paymentId));
    }

    @RequiresPermission(AccountingPermissions.PAYMENT_CREATE)
    @PostMapping
    ResponseEntity<AccountingResponses.Payment> create(
            @PathVariable UUID companyId, @Valid @RequestBody PaymentRequest request) {
        var created = payments.create(new AccountingCommands.Payment(
                request.direction(),
                request.partnerId(),
                request.bankAccountId(),
                request.paymentDate(),
                request.amount(),
                request.method(),
                request.reference(),
                request.notes(),
                request.allocations() == null
                        ? List.of()
                        : request.allocations().stream()
                                .map(a -> new AccountingCommands.Allocation(a.openItemId(), a.amount()))
                                .toList()));
        return ResponseEntity.created(URI.create(ApiPaths.V1 + "/companies/" + companyId + "/payments/"
                        + created.payment().id()))
                .eTag(EntityTags.forVersion(created.payment().version()))
                .body(AccountingResponses.Payment.from(created.payment(), created.openItem(), created.allocations()));
    }

    @RequiresPermission(AccountingPermissions.PAYMENT_CREATE)
    @PatchMapping(path = "/{paymentId}", consumes = AccountingResponses.MERGE_PATCH)
    ResponseEntity<AccountingResponses.Payment> patch(
            @PathVariable UUID companyId,
            @PathVariable UUID paymentId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch,
            @RequestBody JsonNode patch) {
        return AccountingResponses.Payment.entity(payments.patch(paymentId, ifMatch, patch));
    }

    @RequiresPermission(AccountingPermissions.PAYMENT_CREATE)
    @DeleteMapping("/{paymentId}")
    ResponseEntity<Void> delete(
            @PathVariable UUID companyId,
            @PathVariable UUID paymentId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch) {
        payments.delete(paymentId, ifMatch);
        return ResponseEntity.noContent().build();
    }

    @RequiresPermission(AccountingPermissions.PAYMENT_POST)
    @PostMapping("/{paymentId}/post")
    ResponseEntity<?> post(
            @PathVariable UUID companyId,
            @PathVariable UUID paymentId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch,
            HttpServletRequest http) {
        return idempotency.execute(
                http, null, true, () -> AccountingResponses.Payment.entity(payments.post(paymentId, ifMatch)));
    }

    @RequiresPermission(AccountingPermissions.PAYMENT_VOID)
    @PostMapping("/{paymentId}/void")
    ResponseEntity<?> voidPayment(
            @PathVariable UUID companyId,
            @PathVariable UUID paymentId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch,
            @Valid @RequestBody VoidRequest request,
            HttpServletRequest http) {
        return idempotency.execute(
                http,
                request,
                true,
                () -> AccountingResponses.Payment.entity(payments.voidPayment(paymentId, ifMatch, request.reason())));
    }

    @RequiresPermission(AccountingPermissions.PAYMENT_ALLOCATE)
    @PostMapping("/{paymentId}/allocations")
    ResponseEntity<?> allocate(
            @PathVariable UUID companyId,
            @PathVariable UUID paymentId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch,
            @Valid @RequestBody AllocationsRequest request,
            HttpServletRequest http) {
        return idempotency.execute(
                http,
                request,
                true,
                () -> AccountingResponses.Payment.entity(payments.allocate(
                        paymentId,
                        ifMatch,
                        request.allocations().stream()
                                .map(a -> new AccountingCommands.Allocation(a.openItemId(), a.amount()))
                                .toList())));
    }
}
