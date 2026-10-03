package com.erp.hr.web;

import com.erp.hr.HrPermissions;
import com.erp.hr.application.AssignmentView;
import com.erp.hr.application.EmployeeService;
import com.erp.hr.application.EmployeeView;
import com.erp.hr.application.EmploymentAssignmentService;
import com.erp.hr.application.HrCommands;
import com.erp.hr.application.HrListings;
import com.erp.platform.security.RequiresPermission;
import com.erp.platform.web.ApiPaths;
import com.erp.platform.web.EntityTags;
import com.erp.platform.web.paging.ListQueryParser;
import com.erp.platform.web.paging.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
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

/** Employees and their employment assignments (API.md §17.9). */
@RestController
@RequestMapping(ApiPaths.V1 + "/companies/{companyId}/employees")
class EmployeeController {

    private final EmployeeService employees;
    private final EmploymentAssignmentService assignments;
    private final ListQueryParser parser;

    EmployeeController(EmployeeService employees, EmploymentAssignmentService assignments, ListQueryParser parser) {
        this.employees = employees;
        this.assignments = assignments;
        this.parser = parser;
    }

    record CreateEmployeeRequest(
            @NotBlank @Pattern(regexp = "^[A-Z0-9_-]{1,30}$") String employeeNumber,

            @NotBlank @Size(max = 100) String firstName,

            @NotBlank @Size(max = 100) String lastName,

            @Size(max = 100) @Nullable String preferredName,

            @Size(max = 254) @Nullable String workEmail,
            @NotNull LocalDate hireDate,
            HrRequests.@Valid @Nullable AssignmentRequest initialAssignment) {}

    record TerminateRequest(
            @NotNull LocalDate terminationDate,
            @Size(max = 500) @Nullable String reason) {}

    record EmployeeResponse(
            UUID id,
            String employeeNumber,
            String firstName,
            String lastName,
            @Nullable String preferredName,
            @Nullable String workEmail,
            LocalDate hireDate,
            @Nullable LocalDate terminationDate,
            @Nullable String terminationReason,
            String status,
            HrRequests.@Nullable AssignmentResponse currentAssignment,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            int version) {

        static EmployeeResponse from(EmployeeService.Detail detail) {
            EmployeeView v = detail.employee();
            return new EmployeeResponse(
                    v.id(),
                    v.employeeNumber(),
                    v.firstName(),
                    v.lastName(),
                    v.preferredName(),
                    v.workEmail(),
                    v.hireDate(),
                    v.terminationDate(),
                    v.terminationReason(),
                    v.status().name(),
                    HrRequests.AssignmentResponse.from(detail.currentAssignment()),
                    v.createdAt(),
                    v.updatedAt(),
                    v.version());
        }
    }

    record AssignmentList(List<HrRequests.AssignmentResponse> data) {}

    @RequiresPermission(HrPermissions.EMPLOYEE_READ)
    @GetMapping
    PageResponse<EmployeeResponse> list(
            @PathVariable UUID companyId, @RequestParam MultiValueMap<String, String> parameters) {
        return employees.list(parser.parse(parameters, HrListings.EMPLOYEES)).map(EmployeeResponse::from);
    }

    @RequiresPermission(HrPermissions.EMPLOYEE_READ)
    @GetMapping("/{employeeId}")
    ResponseEntity<EmployeeResponse> get(@PathVariable UUID companyId, @PathVariable UUID employeeId) {
        return withETag(employees.get(employeeId));
    }

    @RequiresPermission(HrPermissions.EMPLOYEE_MANAGE)
    @PostMapping
    ResponseEntity<EmployeeResponse> create(
            @PathVariable UUID companyId, @Valid @RequestBody CreateEmployeeRequest request) {
        EmployeeService.Detail created = employees.create(
                new HrCommands.Employee(
                        request.employeeNumber(),
                        request.firstName(),
                        request.lastName(),
                        request.preferredName(),
                        request.workEmail(),
                        request.hireDate()),
                request.initialAssignment() == null
                        ? null
                        : request.initialAssignment().toCommand());
        return ResponseEntity.created(URI.create(ApiPaths.V1 + "/companies/" + companyId + "/employees/"
                        + created.employee().id()))
                .eTag(EntityTags.forVersion(created.employee().version()))
                .body(EmployeeResponse.from(created));
    }

    @RequiresPermission(HrPermissions.EMPLOYEE_MANAGE)
    @PatchMapping(path = "/{employeeId}", consumes = HrRequests.MERGE_PATCH)
    ResponseEntity<EmployeeResponse> patch(
            @PathVariable UUID companyId,
            @PathVariable UUID employeeId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch,
            @RequestBody JsonNode patch) {
        return withETag(employees.patch(employeeId, ifMatch, patch));
    }

    @RequiresPermission(HrPermissions.EMPLOYEE_MANAGE)
    @PostMapping("/{employeeId}/activate")
    ResponseEntity<EmployeeResponse> activate(
            @PathVariable UUID companyId,
            @PathVariable UUID employeeId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch) {
        return withETag(employees.activate(employeeId, ifMatch));
    }

    @RequiresPermission(HrPermissions.EMPLOYEE_TERMINATE)
    @PostMapping("/{employeeId}/terminate")
    ResponseEntity<EmployeeResponse> terminate(
            @PathVariable UUID companyId,
            @PathVariable UUID employeeId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch,
            @Valid @RequestBody TerminateRequest request) {
        String reason = request.reason() == null || request.reason().isBlank()
                ? null
                : request.reason().strip();
        return withETag(employees.terminate(employeeId, ifMatch, request.terminationDate(), reason));
    }

    @RequiresPermission(HrPermissions.EMPLOYEE_READ)
    @GetMapping("/{employeeId}/assignments")
    AssignmentList assignments(@PathVariable UUID companyId, @PathVariable UUID employeeId) {
        return new AssignmentList(assignments.forEmployee(employeeId).stream()
                .map(HrRequests.AssignmentResponse::from)
                .toList());
    }

    @RequiresPermission(HrPermissions.EMPLOYEE_MANAGE)
    @PostMapping("/{employeeId}/assignments")
    ResponseEntity<HrRequests.AssignmentResponse> createAssignment(
            @PathVariable UUID companyId,
            @PathVariable UUID employeeId,
            @Valid @RequestBody HrRequests.AssignmentRequest request) {
        AssignmentView created = assignments.create(employeeId, request.toCommand());
        return ResponseEntity.created(URI.create(ApiPaths.V1 + "/companies/" + companyId + "/employees/" + employeeId
                        + "/assignments/" + created.id()))
                .eTag(EntityTags.forVersion(created.version()))
                .body(HrRequests.AssignmentResponse.from(created));
    }

    @RequiresPermission(HrPermissions.EMPLOYEE_MANAGE)
    @PatchMapping(path = "/{employeeId}/assignments/{assignmentId}", consumes = HrRequests.MERGE_PATCH)
    ResponseEntity<HrRequests.AssignmentResponse> patchAssignment(
            @PathVariable UUID companyId,
            @PathVariable UUID employeeId,
            @PathVariable UUID assignmentId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch,
            @RequestBody JsonNode patch) {
        AssignmentView updated = assignments.patch(employeeId, assignmentId, ifMatch, patch);
        return ResponseEntity.ok()
                .eTag(EntityTags.forVersion(updated.version()))
                .body(HrRequests.AssignmentResponse.from(updated));
    }

    /** Deletes an assignment that has not started yet. */
    @RequiresPermission(HrPermissions.EMPLOYEE_MANAGE)
    @DeleteMapping("/{employeeId}/assignments/{assignmentId}")
    ResponseEntity<Void> deleteAssignment(
            @PathVariable UUID companyId,
            @PathVariable UUID employeeId,
            @PathVariable UUID assignmentId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch) {
        assignments.delete(employeeId, assignmentId, ifMatch);
        return ResponseEntity.noContent().build();
    }

    private static ResponseEntity<EmployeeResponse> withETag(EmployeeService.Detail detail) {
        return ResponseEntity.ok()
                .eTag(EntityTags.forVersion(detail.employee().version()))
                .body(EmployeeResponse.from(detail));
    }
}
