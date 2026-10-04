package com.erp.accounting.web;

import com.erp.accounting.AccountingPermissions;
import com.erp.accounting.application.AccountingListings;
import com.erp.accounting.application.SubledgerService;
import com.erp.platform.idempotency.IdempotencyExecutor;
import com.erp.platform.security.RequiresPermission;
import com.erp.platform.web.ApiPaths;
import com.erp.platform.web.paging.ListQueryParser;
import com.erp.platform.web.paging.PageResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * AR and AP open items with their allocations (API.md §17.8), netting and unallocation. Receivables
 * and payables are separate resources so that each carries its own read permission (ADR-038).
 */
@RestController
class SubledgerController {

    private static final String C = ApiPaths.V1 + "/companies/{companyId}";

    private final SubledgerService subledger;
    private final ListQueryParser parser;
    private final IdempotencyExecutor idempotency;

    SubledgerController(SubledgerService subledger, ListQueryParser parser, IdempotencyExecutor idempotency) {
        this.subledger = subledger;
        this.parser = parser;
        this.idempotency = idempotency;
    }

    record OpenItemDetail(AccountingResponses.OpenItem item, List<AccountingResponses.Allocation> allocations) {}

    record NetRequest(
            @NotNull UUID debitItemId,
            @NotNull UUID creditItemId,

            @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 15, fraction = 4) BigDecimal amount) {}

    @RequiresPermission(AccountingPermissions.AR_READ)
    @GetMapping(C + "/receivables")
    PageResponse<AccountingResponses.OpenItem> receivables(
            @PathVariable UUID companyId, @RequestParam MultiValueMap<String, String> parameters) {
        return list("RECEIVABLE", parameters);
    }

    @RequiresPermission(AccountingPermissions.AR_READ)
    @GetMapping(C + "/receivables/{itemId}")
    OpenItemDetail receivable(@PathVariable UUID companyId, @PathVariable UUID itemId) {
        return detail("RECEIVABLE", itemId);
    }

    @RequiresPermission(AccountingPermissions.AP_READ)
    @GetMapping(C + "/payables")
    PageResponse<AccountingResponses.OpenItem> payables(
            @PathVariable UUID companyId, @RequestParam MultiValueMap<String, String> parameters) {
        return list("PAYABLE", parameters);
    }

    @RequiresPermission(AccountingPermissions.AP_READ)
    @GetMapping(C + "/payables/{itemId}")
    OpenItemDetail payable(@PathVariable UUID companyId, @PathVariable UUID itemId) {
        return detail("PAYABLE", itemId);
    }

    /** Nets a credit note, debit note or on-account remainder against an invoice or bill. */
    @RequiresPermission(AccountingPermissions.PAYMENT_ALLOCATE)
    @PostMapping(C + "/open-items/net")
    ResponseEntity<?> net(
            @PathVariable UUID companyId, @Valid @RequestBody NetRequest request, HttpServletRequest http) {
        return idempotency.execute(
                http,
                request,
                true,
                () -> ResponseEntity.status(201)
                        .body(AccountingResponses.Allocation.from(
                                subledger.net(request.debitItemId(), request.creditItemId(), request.amount()))));
    }

    @RequiresPermission(AccountingPermissions.PAYMENT_UNALLOCATE)
    @DeleteMapping(C + "/payment-allocations/{allocationId}")
    ResponseEntity<?> unallocate(
            @PathVariable UUID companyId, @PathVariable UUID allocationId, HttpServletRequest http) {
        return idempotency.execute(http, null, true, () -> {
            subledger.unallocate(allocationId);
            return ResponseEntity.noContent().build();
        });
    }

    private PageResponse<AccountingResponses.OpenItem> list(String kind, MultiValueMap<String, String> parameters) {
        MultiValueMap<String, String> query = new LinkedMultiValueMap<>(parameters);
        query.remove("filter[kind]");
        return subledger
                .list(kind, parser.parse(query, AccountingListings.OPEN_ITEMS))
                .map(AccountingResponses.OpenItem::from);
    }

    private OpenItemDetail detail(String kind, UUID itemId) {
        return new OpenItemDetail(
                AccountingResponses.OpenItem.from(subledger.get(kind, itemId)),
                subledger.allocations(kind, itemId).stream()
                        .map(AccountingResponses.Allocation::from)
                        .toList());
    }
}
