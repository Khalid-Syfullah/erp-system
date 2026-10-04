package com.erp.hr.web;

import com.erp.hr.application.AttendanceService;
import com.erp.hr.application.EmployeeService;
import com.erp.hr.application.HrListings;
import com.erp.hr.application.HrViews;
import com.erp.hr.application.LeaveService;
import com.erp.hr.application.LeaveService.Access;
import com.erp.hr.application.TeamService;
import com.erp.platform.security.AuthenticatedEndpoint;
import com.erp.platform.web.ApiPaths;
import com.erp.platform.web.EntityTags;
import com.erp.platform.web.paging.ListQueryParser;
import com.erp.platform.web.paging.PageResponse;
import jakarta.validation.Valid;
import java.net.URI;
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

/**
 * Self-service (HR-4): the caller's own employee record, leave and attendance, and for managers
 * their team and its leave requests (HR-3). Every endpoint works on the employee linked to the
 * calling user and answers {@code 404 NOT_AN_EMPLOYEE} for users without one.
 */
@RestController
class SelfServiceController {

    private static final String ME = ApiPaths.V1 + "/companies/{companyId}/me";

    private final EmployeeService employees;
    private final LeaveService leave;
    private final AttendanceService attendance;
    private final TeamService team;
    private final ListQueryParser parser;

    SelfServiceController(
            EmployeeService employees,
            LeaveService leave,
            AttendanceService attendance,
            TeamService team,
            ListQueryParser parser) {
        this.employees = employees;
        this.leave = leave;
        this.attendance = attendance;
        this.team = team;
        this.parser = parser;
    }

    record ListResponse<T>(List<T> data) {}

    // -------------------------------------------------------------------------- own record

    @AuthenticatedEndpoint
    @GetMapping(ME + "/employee")
    ResponseEntity<EmployeeController.EmployeeResponse> employee(@PathVariable UUID companyId) {
        EmployeeService.Detail detail = employees.own();
        return ResponseEntity.ok()
                .eTag(EntityTags.forVersion(detail.employee().version()))
                .body(EmployeeController.EmployeeResponse.from(detail));
    }

    /** Preferred name, personal email, phone and address. */
    @AuthenticatedEndpoint
    @PatchMapping(path = ME + "/employee", consumes = HrRequests.MERGE_PATCH)
    ResponseEntity<EmployeeController.EmployeeResponse> patchEmployee(
            @PathVariable UUID companyId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch,
            @RequestBody JsonNode patch) {
        EmployeeService.Detail detail =
                employees.patchOwn(employees.own().employee().id(), ifMatch, patch);
        return ResponseEntity.ok()
                .eTag(EntityTags.forVersion(detail.employee().version()))
                .body(EmployeeController.EmployeeResponse.from(detail));
    }

    // ------------------------------------------------------------------------------- leave

    @AuthenticatedEndpoint
    @GetMapping(ME + "/leave-requests")
    PageResponse<HrViews.LeaveRequest> leaveRequests(
            @PathVariable UUID companyId, @RequestParam MultiValueMap<String, String> parameters) {
        return leave.list(Access.OWN, parser.parse(parameters, HrListings.LEAVE_REQUESTS));
    }

    @AuthenticatedEndpoint
    @PostMapping(ME + "/leave-requests")
    ResponseEntity<HrViews.LeaveRequest> createLeaveRequest(
            @PathVariable UUID companyId, @Valid @RequestBody LeaveController.LeaveRequestBody body) {
        HrViews.LeaveRequest created = leave.create(Access.OWN, body.toCommand(null));
        return ResponseEntity.created(
                        URI.create(ME.replace("{companyId}", companyId.toString()) + "/leave-requests/" + created.id()))
                .eTag(EntityTags.forVersion(created.version()))
                .body(created);
    }

    @AuthenticatedEndpoint
    @PutMapping(ME + "/leave-requests/{requestId}")
    ResponseEntity<HrViews.LeaveRequest> updateLeaveRequest(
            @PathVariable UUID companyId,
            @PathVariable UUID requestId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch,
            @Valid @RequestBody LeaveController.LeaveRequestBody body) {
        return HrResponses.withETag(leave.update(Access.OWN, requestId, ifMatch, body.toCommand(null)));
    }

    @AuthenticatedEndpoint
    @DeleteMapping(ME + "/leave-requests/{requestId}")
    ResponseEntity<Void> deleteLeaveRequest(
            @PathVariable UUID companyId,
            @PathVariable UUID requestId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch) {
        leave.delete(Access.OWN, requestId, ifMatch);
        return ResponseEntity.noContent().build();
    }

    @AuthenticatedEndpoint
    @PostMapping(ME + "/leave-requests/{requestId}/submit")
    ResponseEntity<HrViews.LeaveRequest> submitLeaveRequest(
            @PathVariable UUID companyId,
            @PathVariable UUID requestId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch) {
        return HrResponses.withETag(leave.submit(Access.OWN, requestId, ifMatch));
    }

    @AuthenticatedEndpoint
    @PostMapping(ME + "/leave-requests/{requestId}/cancel")
    ResponseEntity<HrViews.LeaveRequest> cancelLeaveRequest(
            @PathVariable UUID companyId,
            @PathVariable UUID requestId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch,
            @Valid @RequestBody(required = false) LeaveController.@Nullable DecisionBody body) {
        return HrResponses.withETag(leave.cancel(Access.OWN, requestId, ifMatch, body == null ? null : body.note()));
    }

    @AuthenticatedEndpoint
    @GetMapping(ME + "/leave-balances")
    ListResponse<HrViews.Balance> leaveBalances(@PathVariable UUID companyId, @RequestParam int year) {
        return new ListResponse<>(leave.balances(Access.OWN, null, year));
    }

    @AuthenticatedEndpoint
    @GetMapping(ME + "/leave-ledger")
    PageResponse<HrViews.LedgerEntry> leaveLedger(
            @PathVariable UUID companyId, @RequestParam MultiValueMap<String, String> parameters) {
        return leave.ledger(Access.OWN, null, parser.parse(parameters, HrListings.LEAVE_LEDGER));
    }

    // -------------------------------------------------------------------------- attendance

    @AuthenticatedEndpoint
    @GetMapping(ME + "/attendance")
    PageResponse<HrViews.Attendance> attendance(
            @PathVariable UUID companyId, @RequestParam MultiValueMap<String, String> parameters) {
        return attendance.listOwn(parser.parse(parameters, HrListings.ATTENDANCE));
    }

    @AuthenticatedEndpoint
    @PostMapping(ME + "/attendance/clock-in")
    HrViews.Attendance clockIn(@PathVariable UUID companyId) {
        return attendance.clockIn();
    }

    @AuthenticatedEndpoint
    @PostMapping(ME + "/attendance/clock-out")
    HrViews.Attendance clockOut(@PathVariable UUID companyId) {
        return attendance.clockOut();
    }

    // -------------------------------------------------------------------------------- team

    /** The caller's direct and indirect reports: basic data only (HR-3). */
    @AuthenticatedEndpoint
    @GetMapping(ME + "/team")
    ListResponse<HrViews.TeamMember> team(@PathVariable UUID companyId) {
        return new ListResponse<>(team.team());
    }

    @AuthenticatedEndpoint
    @GetMapping(ME + "/team/leave-requests")
    PageResponse<HrViews.LeaveRequest> teamLeaveRequests(
            @PathVariable UUID companyId, @RequestParam MultiValueMap<String, String> parameters) {
        return leave.list(Access.TEAM, parser.parse(parameters, HrListings.LEAVE_REQUESTS));
    }

    @AuthenticatedEndpoint
    @PostMapping(ME + "/team/leave-requests/{requestId}/approve")
    ResponseEntity<HrViews.LeaveRequest> approveTeamRequest(
            @PathVariable UUID companyId,
            @PathVariable UUID requestId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch,
            @Valid @RequestBody(required = false) LeaveController.@Nullable DecisionBody body) {
        return HrResponses.withETag(leave.approve(Access.TEAM, requestId, ifMatch, body == null ? null : body.note()));
    }

    @AuthenticatedEndpoint
    @PostMapping(ME + "/team/leave-requests/{requestId}/reject")
    ResponseEntity<HrViews.LeaveRequest> rejectTeamRequest(
            @PathVariable UUID companyId,
            @PathVariable UUID requestId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch,
            @Valid @RequestBody(required = false) LeaveController.@Nullable DecisionBody body) {
        return HrResponses.withETag(leave.reject(Access.TEAM, requestId, ifMatch, body == null ? null : body.note()));
    }
}
