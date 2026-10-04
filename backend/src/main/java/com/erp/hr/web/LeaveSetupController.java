package com.erp.hr.web;

import com.erp.hr.HrPermissions;
import com.erp.hr.application.HrCommands;
import com.erp.hr.application.HrListings;
import com.erp.hr.application.HrSettingsService;
import com.erp.hr.application.HrViews;
import com.erp.hr.application.LeaveTypeService;
import com.erp.hr.application.PublicHolidayService;
import com.erp.platform.security.RequiresPermission;
import com.erp.platform.web.ApiPaths;
import com.erp.platform.web.EntityTags;
import com.erp.platform.web.paging.ListQueryParser;
import com.erp.platform.web.paging.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
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
import org.springframework.web.bind.annotation.DeleteMapping;
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

/** Leave types, public holidays and the HR settings (API.md §17.9). */
@RestController
class LeaveSetupController {

    private static final String C = ApiPaths.V1 + "/companies/{companyId}";

    private final LeaveTypeService types;
    private final PublicHolidayService holidays;
    private final HrSettingsService settings;
    private final ListQueryParser parser;

    LeaveSetupController(
            LeaveTypeService types, PublicHolidayService holidays, HrSettingsService settings, ListQueryParser parser) {
        this.types = types;
        this.holidays = holidays;
        this.settings = settings;
        this.parser = parser;
    }

    record LeaveTypeRequest(
            @NotNull @Pattern(regexp = "^[A-Z0-9_-]{1,20}$") String code,
            @NotBlank @Size(max = 100) String name,
            @Nullable Boolean isPaid,

            @NotNull @DecimalMin("0") @DecimalMax("366") @Digits(integer = 3, fraction = 2) BigDecimal annualEntitlementDays,

            @Pattern(regexp = "^(ANNUAL|MONTHLY)$") @Nullable String accrualMethod,

            @DecimalMin("0") @DecimalMax("366") @Digits(integer = 3, fraction = 2) @Nullable BigDecimal maxCarryForwardDays,

            @Nullable Boolean allowNegativeBalance) {}

    record HolidayRequest(
            @Nullable UUID branchId,
            @NotNull LocalDate date,
            @NotBlank @Size(max = 100) String name) {}

    record SettingsRequest(
            @NotNull @Size(max = 6) List<@NotNull @Min(1) @Max(7) Integer> weekendDays,
            @NotNull @Min(60) @Max(1440) Integer standardWorkMinutes) {}

    // -------------------------------------------------------------------------- leave types

    @RequiresPermission(HrPermissions.LEAVE_READ)
    @GetMapping(C + "/leave-types")
    PageResponse<HrViews.LeaveType> listTypes(
            @PathVariable UUID companyId, @RequestParam MultiValueMap<String, String> parameters) {
        return types.list(parser.parse(parameters, HrListings.LEAVE_TYPES));
    }

    @RequiresPermission(HrPermissions.LEAVE_READ)
    @GetMapping(C + "/leave-types/{typeId}")
    ResponseEntity<HrViews.LeaveType> getType(@PathVariable UUID companyId, @PathVariable UUID typeId) {
        HrViews.LeaveType type = types.get(typeId);
        return ResponseEntity.ok().eTag(EntityTags.forVersion(type.version())).body(type);
    }

    @RequiresPermission(HrPermissions.LEAVE_CONFIGURE)
    @PostMapping(C + "/leave-types")
    ResponseEntity<HrViews.LeaveType> createType(
            @PathVariable UUID companyId, @Valid @RequestBody LeaveTypeRequest request) {
        HrViews.LeaveType created = types.create(new HrCommands.LeaveType(
                request.code(),
                request.name().strip(),
                !Boolean.FALSE.equals(request.isPaid()),
                request.annualEntitlementDays(),
                request.accrualMethod() == null ? "ANNUAL" : request.accrualMethod(),
                request.maxCarryForwardDays() == null ? BigDecimal.ZERO : request.maxCarryForwardDays(),
                Boolean.TRUE.equals(request.allowNegativeBalance()),
                true));
        return ResponseEntity.created(
                        URI.create(ApiPaths.V1 + "/companies/" + companyId + "/leave-types/" + created.id()))
                .eTag(EntityTags.forVersion(created.version()))
                .body(created);
    }

    @RequiresPermission(HrPermissions.LEAVE_CONFIGURE)
    @PatchMapping(path = C + "/leave-types/{typeId}", consumes = HrRequests.MERGE_PATCH)
    ResponseEntity<HrViews.LeaveType> patchType(
            @PathVariable UUID companyId,
            @PathVariable UUID typeId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch,
            @RequestBody JsonNode patch) {
        HrViews.LeaveType type = types.patch(typeId, ifMatch, patch);
        return ResponseEntity.ok().eTag(EntityTags.forVersion(type.version())).body(type);
    }

    // ---------------------------------------------------------------------- public holidays

    @RequiresPermission(HrPermissions.LEAVE_READ)
    @GetMapping(C + "/public-holidays")
    PageResponse<HrViews.Holiday> listHolidays(
            @PathVariable UUID companyId, @RequestParam MultiValueMap<String, String> parameters) {
        return holidays.list(parser.parse(parameters, HrListings.HOLIDAYS));
    }

    @RequiresPermission(HrPermissions.LEAVE_CONFIGURE)
    @PostMapping(C + "/public-holidays")
    ResponseEntity<HrViews.Holiday> createHoliday(
            @PathVariable UUID companyId, @Valid @RequestBody HolidayRequest request) {
        HrViews.Holiday created = holidays.create(request.branchId(), request.date(), request.name());
        return ResponseEntity.created(
                        URI.create(ApiPaths.V1 + "/companies/" + companyId + "/public-holidays/" + created.id()))
                .eTag(EntityTags.forVersion(created.version()))
                .body(created);
    }

    @RequiresPermission(HrPermissions.LEAVE_CONFIGURE)
    @PatchMapping(path = C + "/public-holidays/{holidayId}", consumes = HrRequests.MERGE_PATCH)
    ResponseEntity<HrViews.Holiday> patchHoliday(
            @PathVariable UUID companyId,
            @PathVariable UUID holidayId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch,
            @RequestBody JsonNode patch) {
        HrViews.Holiday holiday = holidays.patch(holidayId, ifMatch, patch);
        return ResponseEntity.ok()
                .eTag(EntityTags.forVersion(holiday.version()))
                .body(holiday);
    }

    @RequiresPermission(HrPermissions.LEAVE_CONFIGURE)
    @DeleteMapping(C + "/public-holidays/{holidayId}")
    ResponseEntity<Void> deleteHoliday(@PathVariable UUID companyId, @PathVariable UUID holidayId) {
        holidays.delete(holidayId);
        return ResponseEntity.noContent().build();
    }

    // ----------------------------------------------------------------------------- settings

    @RequiresPermission(HrPermissions.LEAVE_CONFIGURE)
    @GetMapping(C + "/settings/hr")
    ResponseEntity<HrViews.Settings> getSettings(@PathVariable UUID companyId) {
        HrViews.Settings current = settings.get();
        return ResponseEntity.ok()
                .eTag(EntityTags.forVersion(current.version()))
                .body(current);
    }

    @RequiresPermission(HrPermissions.LEAVE_CONFIGURE)
    @PutMapping(C + "/settings/hr")
    ResponseEntity<HrViews.Settings> putSettings(
            @PathVariable UUID companyId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch,
            @Valid @RequestBody SettingsRequest request) {
        HrViews.Settings updated = settings.replace(ifMatch, request.weekendDays(), request.standardWorkMinutes());
        return ResponseEntity.ok()
                .eTag(EntityTags.forVersion(updated.version()))
                .body(updated);
    }
}
