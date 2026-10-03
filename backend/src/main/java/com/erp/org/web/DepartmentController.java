package com.erp.org.web;

import com.erp.org.OrgPermissions;
import com.erp.org.application.DepartmentCommands;
import com.erp.org.application.DepartmentService;
import com.erp.org.application.DepartmentView;
import com.erp.org.application.OrgListings;
import com.erp.platform.security.RequiresPermission;
import com.erp.platform.web.ApiPaths;
import com.erp.platform.web.EntityTags;
import com.erp.platform.web.paging.ListQueryParser;
import com.erp.platform.web.paging.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
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

/** Departments of a company and their hierarchy (API.md §17.3). */
@RestController
@RequestMapping(ApiPaths.V1 + "/companies/{companyId}/departments")
class DepartmentController {

    private final DepartmentService departments;
    private final ListQueryParser parser;

    DepartmentController(DepartmentService departments, ListQueryParser parser) {
        this.departments = departments;
        this.parser = parser;
    }

    record CreateDepartmentRequest(
            @NotBlank @Pattern(regexp = "^[A-Z0-9_-]{1,20}$") String code,

            @NotBlank @Size(max = 100) String name,
            @Nullable UUID parentId,
            @Nullable UUID branchId) {}

    record DepartmentResponse(
            UUID id,
            String code,
            String name,
            @Nullable UUID parentId,
            @Nullable UUID branchId,
            boolean isActive,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            int version) {

        static DepartmentResponse from(DepartmentView v) {
            return new DepartmentResponse(
                    v.id(),
                    v.code(),
                    v.name(),
                    v.parentId(),
                    v.branchId(),
                    v.active(),
                    v.createdAt(),
                    v.updatedAt(),
                    v.version());
        }
    }

    record TreeNodeResponse(
            UUID id,
            String code,
            String name,
            @Nullable UUID branchId,
            boolean isActive,
            List<TreeNodeResponse> children) {

        static TreeNodeResponse from(DepartmentService.TreeNode node) {
            DepartmentView d = node.department();
            return new TreeNodeResponse(
                    d.id(),
                    d.code(),
                    d.name(),
                    d.branchId(),
                    d.active(),
                    node.children().stream().map(TreeNodeResponse::from).toList());
        }
    }

    record TreeResponse(List<TreeNodeResponse> data) {}

    @RequiresPermission(OrgPermissions.DEPARTMENT_READ)
    @GetMapping
    PageResponse<DepartmentResponse> list(
            @PathVariable UUID companyId, @RequestParam MultiValueMap<String, String> parameters) {
        return departments
                .list(parser.parse(parameters, OrgListings.DEPARTMENTS))
                .map(DepartmentResponse::from);
    }

    /** The whole hierarchy, nested; inactive departments only on request. */
    @RequiresPermission(OrgPermissions.DEPARTMENT_READ)
    @GetMapping("/tree")
    TreeResponse tree(@PathVariable UUID companyId, @RequestParam(defaultValue = "false") boolean includeInactive) {
        return new TreeResponse(departments.tree(includeInactive).stream()
                .map(TreeNodeResponse::from)
                .toList());
    }

    @RequiresPermission(OrgPermissions.DEPARTMENT_READ)
    @GetMapping("/{departmentId}")
    ResponseEntity<DepartmentResponse> get(@PathVariable UUID companyId, @PathVariable UUID departmentId) {
        return withETag(departments.get(departmentId));
    }

    @RequiresPermission(OrgPermissions.DEPARTMENT_MANAGE)
    @PostMapping
    ResponseEntity<DepartmentResponse> create(
            @PathVariable UUID companyId, @Valid @RequestBody CreateDepartmentRequest request) {
        DepartmentView created = departments.create(
                new DepartmentCommands.Create(request.code(), request.name(), request.parentId(), request.branchId()));
        return ResponseEntity.created(
                        URI.create(ApiPaths.V1 + "/companies/" + companyId + "/departments/" + created.id()))
                .eTag(EntityTags.forVersion(created.version()))
                .body(DepartmentResponse.from(created));
    }

    @RequiresPermission(OrgPermissions.DEPARTMENT_MANAGE)
    @PatchMapping(path = "/{departmentId}", consumes = CompanyController.MERGE_PATCH)
    ResponseEntity<DepartmentResponse> patch(
            @PathVariable UUID companyId,
            @PathVariable UUID departmentId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch,
            @RequestBody JsonNode patch) {
        return withETag(departments.patch(departmentId, ifMatch, patch));
    }

    @RequiresPermission(OrgPermissions.DEPARTMENT_MANAGE)
    @PostMapping("/{departmentId}/deactivate")
    ResponseEntity<DepartmentResponse> deactivate(
            @PathVariable UUID companyId,
            @PathVariable UUID departmentId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch) {
        return withETag(departments.setActive(departmentId, ifMatch, false));
    }

    @RequiresPermission(OrgPermissions.DEPARTMENT_MANAGE)
    @PostMapping("/{departmentId}/activate")
    ResponseEntity<DepartmentResponse> activate(
            @PathVariable UUID companyId,
            @PathVariable UUID departmentId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch) {
        return withETag(departments.setActive(departmentId, ifMatch, true));
    }

    private static ResponseEntity<DepartmentResponse> withETag(DepartmentView department) {
        return ResponseEntity.ok()
                .eTag(EntityTags.forVersion(department.version()))
                .body(DepartmentResponse.from(department));
    }
}
