package com.erp.org.web;

import com.erp.org.OrgPermissions;
import com.erp.org.application.BranchService;
import com.erp.org.application.BranchView;
import com.erp.org.application.CompanyCommands;
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

/** Branches of a company (API.md §17.3). */
@RestController
@RequestMapping(ApiPaths.V1 + "/companies/{companyId}/branches")
class BranchController {

    private final BranchService branches;
    private final ListQueryParser parser;

    BranchController(BranchService branches, ListQueryParser parser) {
        this.branches = branches;
        this.parser = parser;
    }

    record CreateBranchRequest(
            @NotBlank @Pattern(regexp = "^[A-Z0-9_-]{1,20}$") String code,

            @NotBlank @Size(max = 100) String name,
            @Size(max = 200) @Nullable String addressLine1,
            @Size(max = 200) @Nullable String addressLine2,
            @Size(max = 100) @Nullable String city,
            @Size(max = 100) @Nullable String region,
            @Size(max = 20) @Nullable String postalCode,
            @Pattern(regexp = "^[A-Z]{2}$") @Nullable String countryCode) {}

    record BranchResponse(
            UUID id,
            String code,
            String name,
            @Nullable String addressLine1,
            @Nullable String addressLine2,
            @Nullable String city,
            @Nullable String region,
            @Nullable String postalCode,
            @Nullable String countryCode,
            boolean isActive,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            int version) {

        static BranchResponse from(BranchView v) {
            return new BranchResponse(
                    v.id(),
                    v.code(),
                    v.name(),
                    v.addressLine1(),
                    v.addressLine2(),
                    v.city(),
                    v.region(),
                    v.postalCode(),
                    v.countryCode(),
                    v.active(),
                    v.createdAt(),
                    v.updatedAt(),
                    v.version());
        }
    }

    @RequiresPermission(OrgPermissions.BRANCH_READ)
    @GetMapping
    PageResponse<BranchResponse> list(
            @PathVariable UUID companyId, @RequestParam MultiValueMap<String, String> parameters) {
        return branches.list(parser.parse(parameters, OrgListings.BRANCHES)).map(BranchResponse::from);
    }

    @RequiresPermission(OrgPermissions.BRANCH_READ)
    @GetMapping("/{branchId}")
    ResponseEntity<BranchResponse> get(@PathVariable UUID companyId, @PathVariable UUID branchId) {
        return withETag(branches.get(branchId));
    }

    @RequiresPermission(OrgPermissions.BRANCH_MANAGE)
    @PostMapping
    ResponseEntity<BranchResponse> create(
            @PathVariable UUID companyId, @Valid @RequestBody CreateBranchRequest request) {
        BranchView created = branches.create(new CompanyCommands.CreateBranch(
                request.code(),
                request.name(),
                request.addressLine1(),
                request.addressLine2(),
                request.city(),
                request.region(),
                request.postalCode(),
                request.countryCode()));
        return ResponseEntity.created(URI.create(ApiPaths.V1 + "/companies/" + companyId + "/branches/" + created.id()))
                .eTag(EntityTags.forVersion(created.version()))
                .body(BranchResponse.from(created));
    }

    @RequiresPermission(OrgPermissions.BRANCH_MANAGE)
    @PatchMapping(path = "/{branchId}", consumes = CompanyController.MERGE_PATCH)
    ResponseEntity<BranchResponse> patch(
            @PathVariable UUID companyId,
            @PathVariable UUID branchId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch,
            @RequestBody JsonNode patch) {
        return withETag(branches.patch(branchId, ifMatch, patch));
    }

    @RequiresPermission(OrgPermissions.BRANCH_MANAGE)
    @PostMapping("/{branchId}/deactivate")
    ResponseEntity<BranchResponse> deactivate(
            @PathVariable UUID companyId,
            @PathVariable UUID branchId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch) {
        return withETag(branches.setActive(branchId, ifMatch, false));
    }

    @RequiresPermission(OrgPermissions.BRANCH_MANAGE)
    @PostMapping("/{branchId}/activate")
    ResponseEntity<BranchResponse> activate(
            @PathVariable UUID companyId,
            @PathVariable UUID branchId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch) {
        return withETag(branches.setActive(branchId, ifMatch, true));
    }

    private static ResponseEntity<BranchResponse> withETag(BranchView branch) {
        return ResponseEntity.ok().eTag(EntityTags.forVersion(branch.version())).body(BranchResponse.from(branch));
    }
}
