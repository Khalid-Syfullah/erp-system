package com.erp.org.web;

import com.erp.org.OrgPermissions;
import com.erp.org.application.ExchangeRateService;
import com.erp.org.application.ExchangeRateView;
import com.erp.org.application.OrgListings;
import com.erp.platform.security.RequiresPermission;
import com.erp.platform.web.ApiException;
import com.erp.platform.web.ApiPaths;
import com.erp.platform.web.EntityTags;
import com.erp.platform.web.FieldViolation;
import com.erp.platform.web.paging.ListQueryParser;
import com.erp.platform.web.paging.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.time.OffsetDateTime;
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

/** Exchange rates of a company and the rate lookup (API.md §17.3). Rates are decimal strings. */
@RestController
@RequestMapping(ApiPaths.V1 + "/companies/{companyId}/exchange-rates")
class ExchangeRateController {

    private static final java.util.regex.Pattern CURRENCY = java.util.regex.Pattern.compile("^[A-Z]{3}$");

    private final ExchangeRateService rates;
    private final ListQueryParser parser;

    ExchangeRateController(ExchangeRateService rates, ListQueryParser parser) {
        this.rates = rates;
        this.parser = parser;
    }

    record CreateRateRequest(
            @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String currencyCode,
            @NotNull LocalDate rateDate,

            @NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax("999999999.9999999999") BigDecimal rate) {}

    record RateResponse(
            UUID id,
            String currencyCode,
            LocalDate rateDate,
            BigDecimal rate,
            String source,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            int version) {

        static RateResponse from(ExchangeRateView v) {
            return new RateResponse(
                    v.id(),
                    v.currencyCode(),
                    v.rateDate(),
                    v.rate(),
                    v.source(),
                    v.createdAt(),
                    v.updatedAt(),
                    v.version());
        }
    }

    record QuoteResponse(
            String currencyCode,
            LocalDate date,
            BigDecimal rate,
            @Nullable LocalDate rateDate) {}

    @RequiresPermission(OrgPermissions.EXCHANGE_RATE_READ)
    @GetMapping
    PageResponse<RateResponse> list(
            @PathVariable UUID companyId, @RequestParam MultiValueMap<String, String> parameters) {
        return rates.list(parser.parse(parameters, OrgListings.EXCHANGE_RATES)).map(RateResponse::from);
    }

    /** The rate applicable on {@code date}: the latest on or before it (base currency: 1). */
    @RequiresPermission(OrgPermissions.EXCHANGE_RATE_READ)
    @GetMapping("/lookup")
    QuoteResponse lookup(
            @PathVariable UUID companyId, @RequestParam String currencyCode, @RequestParam LocalDate date) {
        if (!CURRENCY.matcher(currencyCode).matches()) {
            throw ApiException.badRequest(
                    "Invalid currency code.",
                    List.of(FieldViolation.atParameter("currencyCode", "INVALID_VALUE", "must be an ISO 4217 code")));
        }
        ExchangeRateService.Quote quote = rates.lookup(currencyCode, date);
        return new QuoteResponse(quote.currencyCode(), quote.date(), quote.rate(), quote.rateDate());
    }

    @RequiresPermission(OrgPermissions.EXCHANGE_RATE_READ)
    @GetMapping("/{rateId}")
    ResponseEntity<RateResponse> get(@PathVariable UUID companyId, @PathVariable UUID rateId) {
        return withETag(rates.get(rateId));
    }

    @RequiresPermission(OrgPermissions.EXCHANGE_RATE_MANAGE)
    @PostMapping
    ResponseEntity<RateResponse> create(@PathVariable UUID companyId, @Valid @RequestBody CreateRateRequest request) {
        ExchangeRateView created = rates.create(request.currencyCode(), request.rateDate(), request.rate());
        return ResponseEntity.created(
                        URI.create(ApiPaths.V1 + "/companies/" + companyId + "/exchange-rates/" + created.id()))
                .eTag(EntityTags.forVersion(created.version()))
                .body(RateResponse.from(created));
    }

    @RequiresPermission(OrgPermissions.EXCHANGE_RATE_MANAGE)
    @PatchMapping(path = "/{rateId}", consumes = CompanyController.MERGE_PATCH)
    ResponseEntity<RateResponse> patch(
            @PathVariable UUID companyId,
            @PathVariable UUID rateId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch,
            @RequestBody JsonNode patch) {
        return withETag(rates.patch(rateId, ifMatch, patch));
    }

    @RequiresPermission(OrgPermissions.EXCHANGE_RATE_MANAGE)
    @DeleteMapping("/{rateId}")
    ResponseEntity<Void> delete(
            @PathVariable UUID companyId,
            @PathVariable UUID rateId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch) {
        rates.delete(rateId, ifMatch);
        return ResponseEntity.noContent().build();
    }

    private static ResponseEntity<RateResponse> withETag(ExchangeRateView rate) {
        return ResponseEntity.ok().eTag(EntityTags.forVersion(rate.version())).body(RateResponse.from(rate));
    }
}
