package com.erp.accounting.web;

import com.erp.accounting.AccountingPermissions;
import com.erp.accounting.application.AccountingListings;
import com.erp.accounting.application.FiscalYearService;
import com.erp.accounting.application.PeriodService;
import com.erp.platform.idempotency.IdempotencyExecutor;
import com.erp.platform.security.RequiresPermission;
import com.erp.platform.web.ApiPaths;
import com.erp.platform.web.EntityTags;
import com.erp.platform.web.paging.ListQueryParser;
import com.erp.platform.web.paging.PageResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Fiscal years and accounting periods (API.md §17.8, PRODUCT_SPEC.md §8.5). */
@RestController
class CalendarController {

    private static final String C = ApiPaths.V1 + "/companies/{companyId}";

    private final FiscalYearService years;
    private final PeriodService periods;
    private final ListQueryParser parser;
    private final IdempotencyExecutor idempotency;

    CalendarController(
            FiscalYearService years, PeriodService periods, ListQueryParser parser, IdempotencyExecutor idempotency) {
        this.years = years;
        this.periods = periods;
        this.parser = parser;
        this.idempotency = idempotency;
    }

    record FiscalYearRequest(@NotNull LocalDate startDate) {}

    record ReasonRequest(@NotBlank @Size(max = 500) String reason) {}

    @RequiresPermission(AccountingPermissions.PERIOD_READ)
    @GetMapping(C + "/fiscal-years")
    PageResponse<AccountingResponses.FiscalYear> years(
            @PathVariable UUID companyId, @RequestParam MultiValueMap<String, String> parameters) {
        return years.list(parser.parse(parameters, AccountingListings.FISCAL_YEARS))
                .map(y -> AccountingResponses.FiscalYear.from(y, null));
    }

    @RequiresPermission(AccountingPermissions.PERIOD_READ)
    @GetMapping(C + "/fiscal-years/{yearId}")
    ResponseEntity<AccountingResponses.FiscalYear> year(@PathVariable UUID companyId, @PathVariable UUID yearId) {
        return AccountingResponses.FiscalYear.entity(years.get(yearId));
    }

    @RequiresPermission(AccountingPermissions.FISCAL_YEAR_MANAGE)
    @PostMapping(C + "/fiscal-years")
    ResponseEntity<AccountingResponses.FiscalYear> create(
            @PathVariable UUID companyId, @Valid @RequestBody FiscalYearRequest request) {
        var created = years.create(request.startDate());
        return ResponseEntity.created(URI.create(ApiPaths.V1 + "/companies/" + companyId + "/fiscal-years/"
                        + created.year().id()))
                .eTag(EntityTags.forVersion(created.year().version()))
                .body(AccountingResponses.FiscalYear.from(created.year(), created.periods()));
    }

    /** The year-end close, synchronous (ADR-038). */
    @RequiresPermission(AccountingPermissions.FISCAL_YEAR_CLOSE)
    @PostMapping(C + "/fiscal-years/{yearId}/close")
    ResponseEntity<?> closeYear(
            @PathVariable UUID companyId,
            @PathVariable UUID yearId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch,
            HttpServletRequest http) {
        return idempotency.execute(
                http, null, true, () -> AccountingResponses.FiscalYear.entity(years.close(yearId, ifMatch)));
    }

    @RequiresPermission(AccountingPermissions.PERIOD_READ)
    @GetMapping(C + "/periods")
    PageResponse<AccountingResponses.Period> periods(
            @PathVariable UUID companyId, @RequestParam MultiValueMap<String, String> parameters) {
        return periods.list(parser.parse(parameters, AccountingListings.PERIODS))
                .map(AccountingResponses.Period::from);
    }

    @RequiresPermission(AccountingPermissions.PERIOD_READ)
    @GetMapping(C + "/periods/{periodId}")
    ResponseEntity<AccountingResponses.Period> period(@PathVariable UUID companyId, @PathVariable UUID periodId) {
        return AccountingResponses.Period.entity(periods.get(periodId));
    }

    @RequiresPermission(AccountingPermissions.PERIOD_SOFT_CLOSE)
    @PostMapping(C + "/periods/{periodId}/soft-close")
    ResponseEntity<?> softClose(
            @PathVariable UUID companyId,
            @PathVariable UUID periodId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch,
            HttpServletRequest http) {
        return idempotency.execute(
                http, null, true, () -> AccountingResponses.Period.entity(periods.softClose(periodId, ifMatch)));
    }

    @RequiresPermission(AccountingPermissions.PERIOD_CLOSE)
    @PostMapping(C + "/periods/{periodId}/close")
    ResponseEntity<?> closePeriod(
            @PathVariable UUID companyId,
            @PathVariable UUID periodId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch,
            HttpServletRequest http) {
        return idempotency.execute(
                http, null, true, () -> AccountingResponses.Period.entity(periods.close(periodId, ifMatch)));
    }

    @RequiresPermission(AccountingPermissions.PERIOD_REOPEN)
    @PostMapping(C + "/periods/{periodId}/reopen")
    ResponseEntity<?> reopen(
            @PathVariable UUID companyId,
            @PathVariable UUID periodId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch,
            @Valid @RequestBody ReasonRequest request,
            HttpServletRequest http) {
        return idempotency.execute(
                http,
                request,
                true,
                () -> AccountingResponses.Period.entity(periods.reopen(periodId, ifMatch, request.reason())));
    }
}
