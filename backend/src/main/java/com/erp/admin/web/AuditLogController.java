package com.erp.admin.web;

import com.erp.admin.AdminPermissions;
import com.erp.admin.application.AuditEntryView;
import com.erp.admin.application.AuditListings;
import com.erp.admin.application.AuditQueries;
import com.erp.platform.security.GlobalAccess;
import com.erp.platform.security.RequiresPermission;
import com.erp.platform.web.ApiPaths;
import com.erp.platform.web.paging.ListQueryParser;
import com.erp.platform.web.paging.PageResponse;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Audit log queries (API.md §17.2, §17.3). Reading the log is not itself audited; exports will be. */
@RestController
class AuditLogController {

    private final AuditQueries queries;
    private final ListQueryParser parser;
    private final JsonMapper jsonMapper;

    AuditLogController(AuditQueries queries, ListQueryParser parser, JsonMapper jsonMapper) {
        this.queries = queries;
        this.parser = parser;
        this.jsonMapper = jsonMapper;
    }

    @RequiresPermission(AdminPermissions.AUDIT_READ)
    @GetMapping(ApiPaths.V1 + "/companies/{companyId}/audit-log")
    PageResponse<AuditEntryResponse> companyAuditLog(
            @PathVariable UUID companyId, @RequestParam MultiValueMap<String, String> parameters) {
        parameters.remove("companyId");
        return queries.listForCompany(companyId, parser.parse(parameters, AuditListings.AUDIT_LOG))
                .map(this::toResponse);
    }

    @GlobalAccess
    @RequiresPermission(AdminPermissions.AUDIT_READ_GLOBAL)
    @GetMapping(ApiPaths.V1 + "/admin/audit-log")
    PageResponse<AuditEntryResponse> globalAuditLog(@RequestParam MultiValueMap<String, String> parameters) {
        return queries.listGlobal(parser.parse(parameters, AuditListings.AUDIT_LOG))
                .map(this::toResponse);
    }

    private AuditEntryResponse toResponse(AuditEntryView view) {
        return new AuditEntryResponse(
                view.id(),
                view.occurredAt(),
                view.companyId(),
                view.actorUserId(),
                view.actorType(),
                view.apiTokenId(),
                view.action(),
                view.module(),
                view.entityType(),
                view.entityId(),
                view.entityLabel(),
                view.fromState(),
                view.toState(),
                view.changesJson() == null ? null : jsonMapper.readTree(view.changesJson()),
                view.requestId());
    }

    record AuditEntryResponse(
            UUID id,
            OffsetDateTime occurredAt,
            @Nullable UUID companyId,
            @Nullable UUID actorUserId,
            String actorType,
            @Nullable UUID apiTokenId,
            String action,
            String module,
            @Nullable String entityType,
            @Nullable UUID entityId,
            @Nullable String entityLabel,
            @Nullable String fromState,
            @Nullable String toState,
            @Nullable JsonNode changes,
            @Nullable String requestId) {}
}
