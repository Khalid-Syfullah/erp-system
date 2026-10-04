package com.erp.hr.web;

import com.erp.hr.HrPermissions;
import com.erp.hr.application.AttendanceService;
import com.erp.hr.application.HrCommands;
import com.erp.hr.application.HrListings;
import com.erp.hr.application.HrViews;
import com.erp.platform.security.RequiresPermission;
import com.erp.platform.web.ApiPaths;
import com.erp.platform.web.paging.ListQueryParser;
import com.erp.platform.web.paging.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Basic attendance kept by HR (ADR-039). */
@RestController
class AttendanceController {

    private static final String C = ApiPaths.V1 + "/companies/{companyId}";

    private final AttendanceService attendance;
    private final ListQueryParser parser;

    AttendanceController(AttendanceService attendance, ListQueryParser parser) {
        this.attendance = attendance;
        this.parser = parser;
    }

    record AttendanceRequest(
            @NotBlank String status,
            @Nullable OffsetDateTime checkIn,
            @Nullable OffsetDateTime checkOut,
            @Size(max = 500) @Nullable String note) {}

    record SummaryResponse(LocalDate from, LocalDate to, List<HrViews.AttendanceSummary> employees) {}

    @RequiresPermission(HrPermissions.ATTENDANCE_READ)
    @GetMapping(C + "/attendance")
    PageResponse<HrViews.Attendance> list(
            @PathVariable UUID companyId, @RequestParam MultiValueMap<String, String> parameters) {
        return attendance.list(parser.parse(parameters, HrListings.ATTENDANCE));
    }

    @RequiresPermission(HrPermissions.ATTENDANCE_READ)
    @GetMapping(C + "/attendance/summary")
    SummaryResponse summary(@PathVariable UUID companyId, @RequestParam LocalDate from, @RequestParam LocalDate to) {
        return new SummaryResponse(from, to, attendance.summary(from, to));
    }

    @RequiresPermission(HrPermissions.ATTENDANCE_MANAGE)
    @PutMapping(C + "/employees/{employeeId}/attendance/{workDate}")
    HrViews.Attendance record(
            @PathVariable UUID companyId,
            @PathVariable UUID employeeId,
            @PathVariable LocalDate workDate,
            @Valid @RequestBody AttendanceRequest request) {
        return attendance.record(
                employeeId,
                workDate,
                new HrCommands.AttendanceEntry(
                        request.status(), request.checkIn(), request.checkOut(), request.note()));
    }

    @RequiresPermission(HrPermissions.ATTENDANCE_MANAGE)
    @DeleteMapping(C + "/employees/{employeeId}/attendance/{workDate}")
    ResponseEntity<Void> delete(
            @PathVariable UUID companyId, @PathVariable UUID employeeId, @PathVariable LocalDate workDate) {
        attendance.delete(employeeId, workDate);
        return ResponseEntity.noContent().build();
    }
}
