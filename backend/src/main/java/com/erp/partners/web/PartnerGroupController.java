package com.erp.partners.web;

import com.erp.partners.PartnersPermissions;
import com.erp.partners.application.PartnerCommands;
import com.erp.partners.application.PartnerGroupService;
import com.erp.partners.application.PartnerListings;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Partner groups (API.md §17.4). */
@RestController
class PartnerGroupController {

    private static final String C = ApiPaths.V1 + "/companies/{companyId}";

    private final PartnerGroupService groups;
    private final ListQueryParser parser;

    PartnerGroupController(PartnerGroupService groups, ListQueryParser parser) {
        this.groups = groups;
        this.parser = parser;
    }

    record GroupRequest(
            @NotBlank @Pattern(regexp = "^[A-Z0-9_-]{1,20}$") String code,

            @NotBlank @Size(max = 100) String name,

            @NotBlank @Pattern(regexp = "^(CUSTOMER|SUPPLIER)$")
            String appliesTo) {}

    @RequiresPermission(PartnersPermissions.PARTNER_READ)
    @GetMapping(C + "/partner-groups")
    PageResponse<PartnersResponses.Group> list(
            @PathVariable UUID companyId, @RequestParam MultiValueMap<String, String> parameters) {
        return groups.list(parser.parse(parameters, PartnerListings.GROUPS)).map(PartnersResponses.Group::from);
    }

    @RequiresPermission(PartnersPermissions.PARTNER_READ)
    @GetMapping(C + "/partner-groups/{groupId}")
    ResponseEntity<PartnersResponses.Group> get(@PathVariable UUID companyId, @PathVariable UUID groupId) {
        return PartnersResponses.Group.entity(groups.get(groupId));
    }

    @RequiresPermission(PartnersPermissions.PARTNER_MANAGE)
    @PostMapping(C + "/partner-groups")
    ResponseEntity<PartnersResponses.Group> create(
            @PathVariable UUID companyId, @Valid @RequestBody GroupRequest request) {
        var created = groups.create(
                new PartnerCommands.Group(request.code(), request.name().strip(), request.appliesTo()));
        return ResponseEntity.created(
                        URI.create(ApiPaths.V1 + "/companies/" + companyId + "/partner-groups/" + created.id()))
                .eTag(EntityTags.forVersion(created.version()))
                .body(PartnersResponses.Group.from(created));
    }

    @RequiresPermission(PartnersPermissions.PARTNER_MANAGE)
    @PatchMapping(path = C + "/partner-groups/{groupId}", consumes = PartnersResponses.MERGE_PATCH)
    ResponseEntity<PartnersResponses.Group> patch(
            @PathVariable UUID companyId,
            @PathVariable UUID groupId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch,
            @RequestBody JsonNode patch) {
        return PartnersResponses.Group.entity(groups.patch(groupId, ifMatch, patch));
    }
}
