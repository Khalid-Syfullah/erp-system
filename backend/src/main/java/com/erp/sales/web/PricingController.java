package com.erp.sales.web;

import com.erp.platform.security.RequiresPermission;
import com.erp.platform.web.ApiPaths;
import com.erp.sales.SalesPermissions;
import com.erp.sales.application.PriceListService;
import com.erp.sales.application.SalesCommands;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** The price quote (API.md §17.7): prices and taxes for a customer, nothing stored. */
@RestController
class PricingController {

    private final PriceListService lists;

    PricingController(PriceListService lists) {
        this.lists = lists;
    }

    record QuoteLine(
            @NotNull UUID variantId,

            @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 12, fraction = 6) BigDecimal quantity,

            @NotNull UUID uomId,
            @Nullable UUID taxCodeId) {}

    record QuoteRequest(
            @NotNull UUID customerId,
            @Pattern(regexp = "^[A-Z]{3}$") @Nullable String currencyCode,
            @Nullable LocalDate date,
            @Nullable UUID priceListId,
            @NotNull @Size(min = 1, max = 500) List<@Valid @NotNull QuoteLine> lines) {}

    @RequiresPermission(SalesPermissions.ORDER_CREATE)
    @PostMapping(ApiPaths.V1 + "/companies/{companyId}/pricing/quote")
    SalesResponses.PriceQuote quote(@PathVariable UUID companyId, @Valid @RequestBody QuoteRequest request) {
        return SalesResponses.PriceQuote.from(lists.quote(new SalesCommands.Quote(
                request.customerId(),
                request.currencyCode(),
                request.date(),
                request.priceListId(),
                request.lines().stream()
                        .map(l -> new SalesCommands.PricedLine(
                                l.variantId(), null, l.quantity(), l.uomId(), null, BigDecimal.ZERO, l.taxCodeId()))
                        .toList())));
    }
}
