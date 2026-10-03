package com.erp.hr.web;

import com.erp.hr.HrPermissions;
import com.erp.hr.application.DepartmentHeadService;
import com.erp.hr.application.DepartmentHeadView;
import com.erp.hr.application.HrCommands;
import com.erp.hr.application.HrListings;
import com.erp.platform.security.RequiresPermission;
import com.erp.platform.web.ApiPaths;
import com.erp.platform.web.EntityTags;
import com.erp.platform.web.paging.ListQueryParser;
import com.erp.platform.web.paging.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.LocalDate;
import java.time.OffsetDateTime;
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

/** Department heads (effective-dated) of a company. */
@RestController
@RequestMapping(ApiPaths.V1 + "/companies/{companyId}/department-heads")
class DepartmentHeadController {

    private final DepartmentHeadService heads;
    private final ListQueryParser parser;

    DepartmentHeadController(DepartmentHeadService heads, ListQueryParser parser) {
        this.heads = heads;
        this.parser = parser;
    }

    record CreateHeadRequest(
            @NotNull UUID departmentId,
            @NotNull UUID employeeId,
            @NotNull LocalDate effectiveFrom,
            @Nullable LocalDate effectiveTo) {}

    record HeadResponse(
            UUID id,
            UUID departmentId,
            UUID employeeId,
            LocalDate effectiveFrom,
            @Nullable LocalDate effectiveTo,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            int version) {

        static HeadResponse from(DepartmentHeadView v) {
            return new HeadResponse(
                    v.id(),
                    v.departmentId(),
                    v.employeeId(),
                    v.effectiveFrom(),
                    v.effectiveTo(),
                    v.createdAt(),
                    v.updatedAt(),
                    v.version());
        }
    }

    @RequiresPermission(HrPermissions.EMPLOYEE_READ)
    @GetMapping
    PageResponse<HeadResponse> list(
            @PathVariable UUID companyId, @RequestParam MultiValueMap<String, String> parameters) {
        HrRequests.AsOf asOf = HrRequests.AsOf.from(parameters);
        return heads.list(asOf.date(), parser.parse(asOf.remaining(), HrListings.DEPARTMENT_HEADS))
                .map(HeadResponse::from);
    }

    @RequiresPermission(HrPermissions.EMPLOYEE_READ)
    @GetMapping("/{headId}")
    ResponseEntity<HeadResponse> get(@PathVariable UUID companyId, @PathVariable UUID headId) {
        return withETag(heads.get(headId));
    }

    @RequiresPermission(HrPermissions.EMPLOYEE_MANAGE)
    @PostMapping
    ResponseEntity<HeadResponse> create(@PathVariable UUID companyId, @Valid @RequestBody CreateHeadRequest request) {
        DepartmentHeadView created = heads.create(new HrCommands.DepartmentHead(
                request.departmentId(), request.employeeId(), request.effectiveFrom(), request.effectiveTo()));
        return ResponseEntity.created(
                        URI.create(ApiPaths.V1 + "/companies/" + companyId + "/department-heads/" + created.id()))
                .eTag(EntityTags.forVersion(created.version()))
                .body(HeadResponse.from(created));
    }

    @RequiresPermission(HrPermissions.EMPLOYEE_MANAGE)
    @PatchMapping(path = "/{headId}", consumes = HrRequests.MERGE_PATCH)
    ResponseEntity<HeadResponse> patch(
            @PathVariable UUID companyId,
            @PathVariable UUID headId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch,
            @RequestBody JsonNode patch) {
        return withETag(heads.patch(headId, ifMatch, patch));
    }

    private static ResponseEntity<HeadResponse> withETag(DepartmentHeadView head) {
        return ResponseEntity.ok().eTag(EntityTags.forVersion(head.version())).body(HeadResponse.from(head));
    }
}
