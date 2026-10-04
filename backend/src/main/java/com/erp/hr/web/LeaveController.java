package com.erp.hr.web;

import com.erp.hr.HrPermissions;
import com.erp.hr.application.HrCommands;
import com.erp.hr.application.HrListings;
import com.erp.hr.application.HrViews;
import com.erp.hr.application.LeaveAccrualService;
import com.erp.hr.application.LeaveService;
import com.erp.hr.application.LeaveService.Access;
import com.erp.platform.idempotency.IdempotencyExecutor;
import com.erp.platform.security.RequiresPermission;
import com.erp.platform.web.ApiPaths;
import com.erp.platform.web.EntityTags;
import com.erp.platform.web.paging.ListQueryParser;
import com.erp.platform.web.paging.PageResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Leave requests, balances and the leave ledger, as HR sees them (API.md §17.9). */
@RestController
class LeaveController {

    private static final String C = ApiPaths.V1 + "/companies/{companyId}";

    private final LeaveService leave;
    private final LeaveAccrualService accruals;
    private final ListQueryParser parser;
    private final IdempotencyExecutor idempotency;

    LeaveController(
            LeaveService leave, LeaveAccrualService accruals, ListQueryParser parser, IdempotencyExecutor idempotency) {
        this.leave = leave;
        this.accruals = accruals;
        this.parser = parser;
        this.idempotency = idempotency;
    }

    record LeaveRequestBody(
            @Nullable UUID employeeId,
            @NotNull UUID leaveTypeId,
            @NotNull LocalDate startDate,
            @NotNull LocalDate endDate,
            @Nullable Boolean halfDay,
            @Size(max = 500) @Nullable String reason) {

        HrCommands.LeaveRequest toCommand(@Nullable UUID employee) {
            return new HrCommands.LeaveRequest(
                    employee, leaveTypeId, startDate, endDate, Boolean.TRUE.equals(halfDay), reason);
        }
    }

    record DecisionBody(@Size(max = 500) @Nullable String note) {}

    record AdjustmentBody(
            @NotNull UUID employeeId,
            @NotNull UUID leaveTypeId,
            @NotNull @Min(2000) @Max(2200) Integer year,
            @NotNull @Digits(integer = 3, fraction = 2) BigDecimal days,
            @NotBlank @Size(max = 500) String note) {}

    record AccrualBody(@NotNull LocalDate asOf) {}

    record ListResponse<T>(List<T> data) {}

    // ---------------------------------------------------------------------------- requests

    @RequiresPermission(HrPermissions.LEAVE_READ)
    @GetMapping(C + "/leave-requests")
    PageResponse<HrViews.LeaveRequest> list(
            @PathVariable UUID companyId, @RequestParam MultiValueMap<String, String> parameters) {
        return leave.list(Access.HR, parser.parse(parameters, HrListings.LEAVE_REQUESTS));
    }

    @RequiresPermission(HrPermissions.LEAVE_READ)
    @GetMapping(C + "/leave-requests/{requestId}")
    ResponseEntity<HrViews.LeaveRequest> get(@PathVariable UUID companyId, @PathVariable UUID requestId) {
        return HrResponses.withETag(leave.get(Access.HR, requestId));
    }

    /** A request entered by HR on the employee's behalf (a draft). */
    @RequiresPermission(HrPermissions.LEAVE_APPROVE)
    @PostMapping(C + "/leave-requests")
    ResponseEntity<HrViews.LeaveRequest> create(
            @PathVariable UUID companyId, @Valid @RequestBody LeaveRequestBody body) {
        if (body.employeeId() == null) {
            throw HrResponses.required("/employeeId");
        }
        HrViews.LeaveRequest created = leave.create(Access.HR, body.toCommand(body.employeeId()));
        return ResponseEntity.created(
                        URI.create(ApiPaths.V1 + "/companies/" + companyId + "/leave-requests/" + created.id()))
                .eTag(EntityTags.forVersion(created.version()))
                .body(created);
    }

    @RequiresPermission(HrPermissions.LEAVE_APPROVE)
    @PutMapping(C + "/leave-requests/{requestId}")
    ResponseEntity<HrViews.LeaveRequest> update(
            @PathVariable UUID companyId,
            @PathVariable UUID requestId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch,
            @Valid @RequestBody LeaveRequestBody body) {
        return HrResponses.withETag(leave.update(Access.HR, requestId, ifMatch, body.toCommand(null)));
    }

    @RequiresPermission(HrPermissions.LEAVE_APPROVE)
    @DeleteMapping(C + "/leave-requests/{requestId}")
    ResponseEntity<Void> delete(
            @PathVariable UUID companyId,
            @PathVariable UUID requestId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch) {
        leave.delete(Access.HR, requestId, ifMatch);
        return ResponseEntity.noContent().build();
    }

    @RequiresPermission(HrPermissions.LEAVE_APPROVE)
    @PostMapping(C + "/leave-requests/{requestId}/submit")
    ResponseEntity<HrViews.LeaveRequest> submit(
            @PathVariable UUID companyId,
            @PathVariable UUID requestId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch) {
        return HrResponses.withETag(leave.submit(Access.HR, requestId, ifMatch));
    }

    @RequiresPermission(HrPermissions.LEAVE_APPROVE)
    @PostMapping(C + "/leave-requests/{requestId}/approve")
    ResponseEntity<HrViews.LeaveRequest> approve(
            @PathVariable UUID companyId,
            @PathVariable UUID requestId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch,
            @Valid @RequestBody(required = false) @Nullable DecisionBody body) {
        return HrResponses.withETag(leave.approve(Access.HR, requestId, ifMatch, body == null ? null : body.note()));
    }

    @RequiresPermission(HrPermissions.LEAVE_APPROVE)
    @PostMapping(C + "/leave-requests/{requestId}/reject")
    ResponseEntity<HrViews.LeaveRequest> reject(
            @PathVariable UUID companyId,
            @PathVariable UUID requestId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch,
            @Valid @RequestBody(required = false) @Nullable DecisionBody body) {
        return HrResponses.withETag(leave.reject(Access.HR, requestId, ifMatch, body == null ? null : body.note()));
    }

    @RequiresPermission(HrPermissions.LEAVE_APPROVE)
    @PostMapping(C + "/leave-requests/{requestId}/cancel")
    ResponseEntity<HrViews.LeaveRequest> cancel(
            @PathVariable UUID companyId,
            @PathVariable UUID requestId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch,
            @Valid @RequestBody(required = false) @Nullable DecisionBody body) {
        return HrResponses.withETag(leave.cancel(Access.HR, requestId, ifMatch, body == null ? null : body.note()));
    }

    // ------------------------------------------------------------------- balances and ledger

    @RequiresPermission(HrPermissions.LEAVE_READ)
    @GetMapping(C + "/leave-balances")
    ListResponse<HrViews.Balance> balances(
            @PathVariable UUID companyId, @RequestParam UUID employeeId, @RequestParam int year) {
        return new ListResponse<>(leave.balances(Access.HR, employeeId, year));
    }

    @RequiresPermission(HrPermissions.LEAVE_READ)
    @GetMapping(C + "/leave-ledger")
    PageResponse<HrViews.LedgerEntry> ledger(
            @PathVariable UUID companyId, @RequestParam MultiValueMap<String, String> parameters) {
        var query = new org.springframework.util.LinkedMultiValueMap<>(parameters);
        String employee = query.getFirst("filter[employeeId]");
        UUID employeeId = employee == null ? null : HrResponses.uuid("filter[employeeId]", employee);
        return leave.ledger(Access.HR, employeeId, parser.parse(query, HrListings.LEAVE_LEDGER));
    }

    @RequiresPermission(HrPermissions.LEAVE_ADJUST)
    @PostMapping(C + "/leave-ledger/adjustments")
    ResponseEntity<?> adjust(
            @PathVariable UUID companyId, @Valid @RequestBody AdjustmentBody body, HttpServletRequest http) {
        return idempotency.execute(
                http,
                body,
                true,
                () -> ResponseEntity.status(201)
                        .body(leave.adjust(
                                body.employeeId(), body.leaveTypeId(), body.year(), body.days(), body.note())));
    }

    /** Runs the (idempotent) accrual for a date; the daily job does the same. */
    @RequiresPermission(HrPermissions.LEAVE_ADJUST)
    @PostMapping(C + "/leave-accruals")
    LeaveAccrualService.Result accrue(@PathVariable UUID companyId, @Valid @RequestBody AccrualBody body) {
        return accruals.accrue(body.asOf());
    }
}
