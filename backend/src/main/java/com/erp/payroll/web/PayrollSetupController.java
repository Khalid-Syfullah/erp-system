package com.erp.payroll.web;

import com.erp.payroll.PayrollPermissions;
import com.erp.payroll.application.PayrollCommands;
import com.erp.payroll.application.PayrollListings;
import com.erp.payroll.application.PayrollSetupService;
import com.erp.payroll.application.PayrollViews;
import com.erp.platform.security.RequiresPermission;
import com.erp.platform.web.ApiPaths;
import com.erp.platform.web.EntityTags;
import com.erp.platform.web.paging.ListQueryParser;
import com.erp.platform.web.paging.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Payroll configuration: settings, statutory rules, components, structures, schedules, periods (API.md §17.10). */
@RestController
class PayrollSetupController {

    static final String C = ApiPaths.V1 + "/companies/{companyId}";
    static final String MERGE_PATCH = "application/merge-patch+json";

    private final PayrollSetupService setup;
    private final ListQueryParser parser;

    PayrollSetupController(PayrollSetupService setup, ListQueryParser parser) {
        this.setup = setup;
        this.parser = parser;
    }

    record SettingsRequest(
            @NotNull @Pattern(regexp = "^(CALENDAR_DAYS|WORKING_DAYS)$")
            String prorationBasis) {}

    record RuleResponse(String code, String description) {}

    record ComponentRequest(
            @NotNull @Pattern(regexp = "^[A-Z0-9_-]{1,20}$") String code,
            @NotBlank @Size(max = 100) String name,

            @NotNull @Pattern(regexp = "^(EARNING|DEDUCTION|EMPLOYER_CONTRIBUTION)$")
            String kind,

            @NotNull @Pattern(regexp = "^(FIXED|PERCENT_OF_BASE|PERCENT_OF_GROSS|INPUT|STATUTORY)$")
            String calculation,

            @DecimalMin("0") @Digits(integer = 13, fraction = 6) @Nullable BigDecimal defaultRate,

            @DecimalMin("0") @Digits(integer = 15, fraction = 4) @Nullable BigDecimal defaultAmount,

            @Nullable Boolean isTaxable,
            @Pattern(regexp = "^[A-Z0-9_]{1,40}$") @Nullable String statutoryRuleCode,
            @NotNull @Min(1) @Max(9999) Integer sequence) {}

    record StructureLineRequest(
            @NotNull UUID componentId,

            @DecimalMin("0") @Digits(integer = 13, fraction = 6) @Nullable BigDecimal rate,

            @DecimalMin("0") @Digits(integer = 15, fraction = 4) @Nullable BigDecimal amount) {

        PayrollCommands.StructureLine toCommand() {
            return new PayrollCommands.StructureLine(componentId, rate, amount);
        }
    }

    record StructureRequest(
            @NotNull @Pattern(regexp = "^[A-Z0-9_-]{1,20}$") String code,
            @NotBlank @Size(max = 100) String name,
            @Nullable Boolean isActive,
            @NotNull @Size(max = 100) List<@Valid @NotNull StructureLineRequest> components) {}

    record ScheduleRequest(
            @NotNull @Pattern(regexp = "^[A-Z0-9_-]{1,20}$") String code,
            @NotBlank @Size(max = 100) String name,

            @NotNull @Pattern(regexp = "^(MONTHLY|SEMI_MONTHLY|BIWEEKLY|WEEKLY)$")
            String frequency,

            @NotNull @Pattern(regexp = "^[A-Z]{3}$") String currencyCode,
            @Nullable LocalDate anchorDate,
            @Min(-31) @Max(31) @Nullable Integer payDayOffset) {}

    record GenerateRequest(@NotNull @Min(2000) @Max(2200) Integer year) {}

    record ListResponse<T>(List<T> data) {}

    // ------------------------------------------------------------------------------ settings

    @RequiresPermission(PayrollPermissions.CONFIGURATION_MANAGE)
    @GetMapping(C + "/settings/payroll")
    ResponseEntity<PayrollViews.Settings> settings(@PathVariable UUID companyId) {
        PayrollViews.Settings s = setup.settings();
        return ResponseEntity.ok().eTag(EntityTags.forVersion(s.version())).body(s);
    }

    @RequiresPermission(PayrollPermissions.CONFIGURATION_MANAGE)
    @PutMapping(C + "/settings/payroll")
    ResponseEntity<PayrollViews.Settings> putSettings(
            @PathVariable UUID companyId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch,
            @Valid @RequestBody SettingsRequest request) {
        PayrollViews.Settings s = setup.replaceSettings(ifMatch, request.prorationBasis());
        return ResponseEntity.ok().eTag(EntityTags.forVersion(s.version())).body(s);
    }

    @RequiresPermission(PayrollPermissions.CONFIGURATION_MANAGE)
    @GetMapping(C + "/statutory-rules")
    ListResponse<RuleResponse> rules(@PathVariable UUID companyId) {
        return new ListResponse<>(setup.statutoryRules().stream()
                .map(r -> new RuleResponse(r.code(), r.description()))
                .toList());
    }

    // ---------------------------------------------------------------------------- components

    @RequiresPermission(PayrollPermissions.CONFIGURATION_MANAGE)
    @GetMapping(C + "/pay-components")
    PageResponse<PayrollViews.Component> components(
            @PathVariable UUID companyId, @RequestParam MultiValueMap<String, String> parameters) {
        return setup.components(parser.parse(parameters, PayrollListings.COMPONENTS));
    }

    @RequiresPermission(PayrollPermissions.CONFIGURATION_MANAGE)
    @GetMapping(C + "/pay-components/{componentId}")
    ResponseEntity<PayrollViews.Component> component(@PathVariable UUID companyId, @PathVariable UUID componentId) {
        PayrollViews.Component c = setup.component(componentId);
        return ResponseEntity.ok().eTag(EntityTags.forVersion(c.version())).body(c);
    }

    @RequiresPermission(PayrollPermissions.CONFIGURATION_MANAGE)
    @PostMapping(C + "/pay-components")
    ResponseEntity<PayrollViews.Component> createComponent(
            @PathVariable UUID companyId, @Valid @RequestBody ComponentRequest request) {
        PayrollViews.Component c = setup.createComponent(new PayrollCommands.Component(
                request.code(),
                request.name().strip(),
                request.kind(),
                request.calculation(),
                request.defaultRate(),
                request.defaultAmount(),
                !Boolean.FALSE.equals(request.isTaxable()),
                request.statutoryRuleCode(),
                request.sequence(),
                true));
        return ResponseEntity.created(URI.create(ApiPaths.V1 + "/companies/" + companyId + "/pay-components/" + c.id()))
                .eTag(EntityTags.forVersion(c.version()))
                .body(c);
    }

    @RequiresPermission(PayrollPermissions.CONFIGURATION_MANAGE)
    @PatchMapping(path = C + "/pay-components/{componentId}", consumes = MERGE_PATCH)
    ResponseEntity<PayrollViews.Component> patchComponent(
            @PathVariable UUID companyId,
            @PathVariable UUID componentId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch,
            @RequestBody JsonNode patch) {
        PayrollViews.Component c = setup.patchComponent(componentId, ifMatch, patch);
        return ResponseEntity.ok().eTag(EntityTags.forVersion(c.version())).body(c);
    }

    // ---------------------------------------------------------------------------- structures

    @RequiresPermission(PayrollPermissions.CONFIGURATION_MANAGE)
    @GetMapping(C + "/salary-structures")
    PageResponse<PayrollViews.Structure> structures(
            @PathVariable UUID companyId, @RequestParam MultiValueMap<String, String> parameters) {
        return setup.structures(parser.parse(parameters, PayrollListings.STRUCTURES));
    }

    @RequiresPermission(PayrollPermissions.CONFIGURATION_MANAGE)
    @GetMapping(C + "/salary-structures/{structureId}")
    ResponseEntity<PayrollViews.Structure> structure(@PathVariable UUID companyId, @PathVariable UUID structureId) {
        PayrollViews.Structure s = setup.structure(structureId);
        return ResponseEntity.ok().eTag(EntityTags.forVersion(s.version())).body(s);
    }

    @RequiresPermission(PayrollPermissions.CONFIGURATION_MANAGE)
    @PostMapping(C + "/salary-structures")
    ResponseEntity<PayrollViews.Structure> createStructure(
            @PathVariable UUID companyId, @Valid @RequestBody StructureRequest request) {
        PayrollViews.Structure s = setup.createStructure(
                request.code(),
                request.name(),
                request.components().stream()
                        .map(StructureLineRequest::toCommand)
                        .toList());
        return ResponseEntity.created(
                        URI.create(ApiPaths.V1 + "/companies/" + companyId + "/salary-structures/" + s.id()))
                .eTag(EntityTags.forVersion(s.version()))
                .body(s);
    }

    /** Replaces name, activity and components (the code stays). */
    @RequiresPermission(PayrollPermissions.CONFIGURATION_MANAGE)
    @PutMapping(C + "/salary-structures/{structureId}")
    ResponseEntity<PayrollViews.Structure> updateStructure(
            @PathVariable UUID companyId,
            @PathVariable UUID structureId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch,
            @Valid @RequestBody StructureRequest request) {
        PayrollViews.Structure s = setup.updateStructure(
                structureId,
                ifMatch,
                request.name(),
                !Boolean.FALSE.equals(request.isActive()),
                request.components().stream()
                        .map(StructureLineRequest::toCommand)
                        .toList());
        return ResponseEntity.ok().eTag(EntityTags.forVersion(s.version())).body(s);
    }

    // ------------------------------------------------------------------------------ schedules

    @RequiresPermission(PayrollPermissions.CONFIGURATION_MANAGE)
    @GetMapping(C + "/pay-schedules")
    PageResponse<PayrollViews.Schedule> schedules(
            @PathVariable UUID companyId, @RequestParam MultiValueMap<String, String> parameters) {
        return setup.schedules(parser.parse(parameters, PayrollListings.SCHEDULES));
    }

    @RequiresPermission(PayrollPermissions.CONFIGURATION_MANAGE)
    @PostMapping(C + "/pay-schedules")
    ResponseEntity<PayrollViews.Schedule> createSchedule(
            @PathVariable UUID companyId, @Valid @RequestBody ScheduleRequest request) {
        PayrollViews.Schedule s = setup.createSchedule(new PayrollCommands.Schedule(
                request.code(),
                request.name().strip(),
                request.frequency(),
                request.currencyCode(),
                request.anchorDate(),
                request.payDayOffset() == null ? 0 : request.payDayOffset()));
        return ResponseEntity.created(URI.create(ApiPaths.V1 + "/companies/" + companyId + "/pay-schedules/" + s.id()))
                .eTag(EntityTags.forVersion(s.version()))
                .body(s);
    }

    @RequiresPermission(PayrollPermissions.CONFIGURATION_MANAGE)
    @PatchMapping(path = C + "/pay-schedules/{scheduleId}", consumes = MERGE_PATCH)
    ResponseEntity<PayrollViews.Schedule> patchSchedule(
            @PathVariable UUID companyId,
            @PathVariable UUID scheduleId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch,
            @RequestBody JsonNode patch) {
        PayrollViews.Schedule s = setup.patchSchedule(scheduleId, ifMatch, patch);
        return ResponseEntity.ok().eTag(EntityTags.forVersion(s.version())).body(s);
    }

    /** Creates the schedule's missing periods of a year. */
    @RequiresPermission(PayrollPermissions.CONFIGURATION_MANAGE)
    @PostMapping(C + "/pay-schedules/{scheduleId}/periods")
    ListResponse<PayrollViews.Period> generatePeriods(
            @PathVariable UUID companyId, @PathVariable UUID scheduleId, @Valid @RequestBody GenerateRequest request) {
        return new ListResponse<>(setup.generatePeriods(scheduleId, request.year()));
    }

    @RequiresPermission(PayrollPermissions.RUN_READ)
    @GetMapping(C + "/payroll-periods")
    PageResponse<PayrollViews.Period> periods(
            @PathVariable UUID companyId, @RequestParam MultiValueMap<String, String> parameters) {
        return setup.periods(parser.parse(parameters, PayrollListings.PERIODS));
    }

    @RequiresPermission(PayrollPermissions.RUN_READ)
    @GetMapping(C + "/payroll-periods/{periodId}")
    PayrollViews.Period period(@PathVariable UUID companyId, @PathVariable UUID periodId) {
        return setup.period(periodId);
    }
}
