package com.erp.hr.web;

import com.erp.hr.HrPermissions;
import com.erp.hr.application.HrCommands;
import com.erp.hr.application.HrListings;
import com.erp.hr.application.PositionService;
import com.erp.hr.application.PositionView;
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

/** Positions (designations / job titles) of a company (API.md §17.9). */
@RestController
@RequestMapping(ApiPaths.V1 + "/companies/{companyId}/positions")
class PositionController {

    private final PositionService positions;
    private final ListQueryParser parser;

    PositionController(PositionService positions, ListQueryParser parser) {
        this.positions = positions;
        this.parser = parser;
    }

    record CreatePositionRequest(
            @NotBlank @Pattern(regexp = "^[A-Z0-9_-]{1,20}$") String code,

            @NotBlank @Size(max = 100) String title,
            @Nullable UUID departmentId,
            @Size(min = 1, max = 20) @Nullable String grade) {}

    record PositionResponse(
            UUID id,
            String code,
            String title,
            @Nullable UUID departmentId,
            @Nullable String grade,
            boolean isActive,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            int version) {

        static PositionResponse from(PositionView v) {
            return new PositionResponse(
                    v.id(),
                    v.code(),
                    v.title(),
                    v.departmentId(),
                    v.grade(),
                    v.active(),
                    v.createdAt(),
                    v.updatedAt(),
                    v.version());
        }
    }

    @RequiresPermission(HrPermissions.EMPLOYEE_READ)
    @GetMapping
    PageResponse<PositionResponse> list(
            @PathVariable UUID companyId, @RequestParam MultiValueMap<String, String> parameters) {
        return positions.list(parser.parse(parameters, HrListings.POSITIONS)).map(PositionResponse::from);
    }

    @RequiresPermission(HrPermissions.EMPLOYEE_READ)
    @GetMapping("/{positionId}")
    ResponseEntity<PositionResponse> get(@PathVariable UUID companyId, @PathVariable UUID positionId) {
        return withETag(positions.get(positionId));
    }

    @RequiresPermission(HrPermissions.POSITION_MANAGE)
    @PostMapping
    ResponseEntity<PositionResponse> create(
            @PathVariable UUID companyId, @Valid @RequestBody CreatePositionRequest request) {
        PositionView created = positions.create(new HrCommands.Position(
                request.code(), request.title().strip(), request.departmentId(), request.grade(), true));
        return ResponseEntity.created(
                        URI.create(ApiPaths.V1 + "/companies/" + companyId + "/positions/" + created.id()))
                .eTag(EntityTags.forVersion(created.version()))
                .body(PositionResponse.from(created));
    }

    @RequiresPermission(HrPermissions.POSITION_MANAGE)
    @PatchMapping(path = "/{positionId}", consumes = HrRequests.MERGE_PATCH)
    ResponseEntity<PositionResponse> patch(
            @PathVariable UUID companyId,
            @PathVariable UUID positionId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch,
            @RequestBody JsonNode patch) {
        return withETag(positions.patch(positionId, ifMatch, patch));
    }

    @RequiresPermission(HrPermissions.POSITION_MANAGE)
    @PostMapping("/{positionId}/deactivate")
    ResponseEntity<PositionResponse> deactivate(
            @PathVariable UUID companyId,
            @PathVariable UUID positionId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch) {
        return withETag(positions.setActive(positionId, ifMatch, false));
    }

    @RequiresPermission(HrPermissions.POSITION_MANAGE)
    @PostMapping("/{positionId}/activate")
    ResponseEntity<PositionResponse> activate(
            @PathVariable UUID companyId,
            @PathVariable UUID positionId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch) {
        return withETag(positions.setActive(positionId, ifMatch, true));
    }

    private static ResponseEntity<PositionResponse> withETag(PositionView position) {
        return ResponseEntity.ok()
                .eTag(EntityTags.forVersion(position.version()))
                .body(PositionResponse.from(position));
    }
}
