package com.erp.payroll.web;

import com.erp.payroll.PayrollPermissions;
import com.erp.payroll.application.CompensationService;
import com.erp.payroll.application.InputService;
import com.erp.payroll.application.PayrollCommands;
import com.erp.payroll.application.PayrollViews;
import com.erp.platform.security.RequiresPermission;
import com.erp.platform.web.ApiException;
import com.erp.platform.web.ApiPaths;
import com.erp.platform.web.EntityTags;
import com.erp.platform.web.MergePatch;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Employee compensations and period inputs (API.md §17.10). */
@RestController
class CompensationController {

    private static final String C = PayrollSetupController.C;

    private final CompensationService compensations;
    private final InputService inputs;

    CompensationController(CompensationService compensations, InputService inputs) {
        this.compensations = compensations;
        this.inputs = inputs;
    }

    record CompensationRequest(
            @NotNull UUID payScheduleId,
            @NotNull UUID salaryStructureId,

            @NotNull @DecimalMin("0") @Digits(integer = 15, fraction = 4) BigDecimal baseAmount,

            @NotNull LocalDate effectiveFrom,
            @Nullable LocalDate effectiveTo,
            @Size(max = 100) @Nullable List<PayrollSetupController.@Valid StructureLineRequest> overrides) {}

    record InputRequest(
            @NotNull UUID employeeId,
            @NotNull UUID componentId,
            @Nullable UUID runId,

            @DecimalMin(value = "0", inclusive = false) @Digits(integer = 12, fraction = 6) @Nullable BigDecimal quantity,

            @DecimalMin(value = "0", inclusive = false) @Digits(integer = 15, fraction = 4) @Nullable BigDecimal amount,

            @Size(max = 500) @Nullable String note) {}

    record ListResponse<T>(List<T> data) {}

    // -------------------------------------------------------------------------- compensations

    @RequiresPermission(PayrollPermissions.COMPENSATION_READ)
    @GetMapping(C + "/employees/{employeeId}/compensations")
    ListResponse<PayrollViews.Compensation> list(@PathVariable UUID companyId, @PathVariable UUID employeeId) {
        return new ListResponse<>(compensations.forEmployee(employeeId));
    }

    @RequiresPermission(PayrollPermissions.COMPENSATION_MANAGE)
    @PostMapping(C + "/employees/{employeeId}/compensations")
    ResponseEntity<PayrollViews.Compensation> create(
            @PathVariable UUID companyId, @PathVariable UUID employeeId, @Valid @RequestBody CompensationRequest r) {
        PayrollViews.Compensation created = compensations.create(new PayrollCommands.Compensation(
                employeeId,
                r.payScheduleId(),
                r.salaryStructureId(),
                r.baseAmount(),
                r.effectiveFrom(),
                r.effectiveTo(),
                r.overrides() == null
                        ? List.of()
                        : r.overrides().stream()
                                .map(PayrollSetupController.StructureLineRequest::toCommand)
                                .toList()));
        return ResponseEntity.created(URI.create(ApiPaths.V1 + "/companies/" + companyId + "/employees/" + employeeId
                        + "/compensations/" + created.id()))
                .eTag(EntityTags.forVersion(created.version()))
                .body(created);
    }

    /** Only {@code effectiveTo} changes; a new rate is a new compensation. */
    @RequiresPermission(PayrollPermissions.COMPENSATION_MANAGE)
    @PatchMapping(
            path = C + "/employees/{employeeId}/compensations/{compensationId}",
            consumes = PayrollSetupController.MERGE_PATCH)
    ResponseEntity<PayrollViews.Compensation> patch(
            @PathVariable UUID companyId,
            @PathVariable UUID employeeId,
            @PathVariable UUID compensationId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch,
            @RequestBody JsonNode body) {
        MergePatch patch = MergePatch.of(body, Set.of("effectiveTo"));
        var end = patch.date("effectiveTo", false);
        patch.throwIfInvalid();
        if (!end.present()) {
            throw ApiException.badRequest("Nothing to change.", List.of());
        }
        PayrollViews.Compensation updated = compensations.end(employeeId, compensationId, ifMatch, end.value());
        return ResponseEntity.ok()
                .eTag(EntityTags.forVersion(updated.version()))
                .body(updated);
    }

    @RequiresPermission(PayrollPermissions.COMPENSATION_MANAGE)
    @DeleteMapping(C + "/employees/{employeeId}/compensations/{compensationId}")
    ResponseEntity<Void> delete(
            @PathVariable UUID companyId,
            @PathVariable UUID employeeId,
            @PathVariable UUID compensationId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch) {
        compensations.delete(employeeId, compensationId, ifMatch);
        return ResponseEntity.noContent().build();
    }

    // --------------------------------------------------------------------------------- inputs

    @RequiresPermission(PayrollPermissions.RUN_PREPARE)
    @GetMapping(C + "/payroll-periods/{periodId}/inputs")
    ListResponse<PayrollViews.Input> inputs(@PathVariable UUID companyId, @PathVariable UUID periodId) {
        return new ListResponse<>(inputs.list(periodId));
    }

    @RequiresPermission(PayrollPermissions.RUN_PREPARE)
    @PostMapping(C + "/payroll-periods/{periodId}/inputs")
    ResponseEntity<PayrollViews.Input> createInput(
            @PathVariable UUID companyId, @PathVariable UUID periodId, @Valid @RequestBody InputRequest r) {
        PayrollViews.Input created = inputs.create(
                periodId,
                new PayrollCommands.Input(
                        r.employeeId(), r.componentId(), r.runId(), r.quantity(), r.amount(), r.note()));
        return ResponseEntity.created(URI.create(ApiPaths.V1 + "/companies/" + companyId + "/payroll-periods/"
                        + periodId + "/inputs/" + created.id()))
                .eTag(EntityTags.forVersion(created.version()))
                .body(created);
    }

    /** Replaces the quantity or amount and the note. */
    @RequiresPermission(PayrollPermissions.RUN_PREPARE)
    @org.springframework.web.bind.annotation.PutMapping(C + "/payroll-periods/{periodId}/inputs/{inputId}")
    ResponseEntity<PayrollViews.Input> updateInput(
            @PathVariable UUID companyId,
            @PathVariable UUID periodId,
            @PathVariable UUID inputId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch,
            @Valid @RequestBody InputRequest r) {
        PayrollViews.Input updated = inputs.update(periodId, inputId, ifMatch, r.quantity(), r.amount(), r.note());
        return ResponseEntity.ok()
                .eTag(EntityTags.forVersion(updated.version()))
                .body(updated);
    }

    @RequiresPermission(PayrollPermissions.RUN_PREPARE)
    @DeleteMapping(C + "/payroll-periods/{periodId}/inputs/{inputId}")
    ResponseEntity<Void> deleteInput(
            @PathVariable UUID companyId, @PathVariable UUID periodId, @PathVariable UUID inputId) {
        inputs.delete(periodId, inputId);
        return ResponseEntity.noContent().build();
    }
}
