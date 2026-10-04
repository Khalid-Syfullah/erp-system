package com.erp.accounting.web;

import com.erp.accounting.AccountingPermissions;
import com.erp.accounting.application.AccountMappingService;
import com.erp.platform.security.RequiresPermission;
import com.erp.platform.web.ApiPaths;
import com.erp.platform.web.EntityTags;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Account mappings (API.md §17.8): bulk upsert with the set's ETag, and the resolution diagnostic. */
@RestController
@RequestMapping(ApiPaths.V1 + "/companies/{companyId}/account-mappings")
class AccountMappingController {

    private final AccountMappingService mappings;

    AccountMappingController(AccountMappingService mappings) {
        this.mappings = mappings;
    }

    /** {@code accountId: null} removes a scoped mapping. */
    record MappingRequest(
            @NotBlank @Size(max = 40) String mappingKey,
            @NotBlank @Size(max = 20) String scopeType,
            @Nullable UUID scopeId,
            @Nullable UUID accountId) {}

    record MappingsRequest(
            @NotNull @Size(min = 1, max = 500) List<@Valid @NotNull MappingRequest> mappings) {}

    record Resolution(String mappingKey, UUID accountId, String accountCode) {}

    @RequiresPermission(AccountingPermissions.ACCOUNT_MAPPING_MANAGE)
    @GetMapping
    ResponseEntity<AccountingResponses.Mappings> list(@PathVariable UUID companyId) {
        var set = mappings.get();
        return ResponseEntity.ok()
                .eTag(EntityTags.forVersion(set.version()))
                .body(new AccountingResponses.Mappings(set.mappings().stream()
                        .map(AccountingResponses.Mapping::from)
                        .toList()));
    }

    @RequiresPermission(AccountingPermissions.ACCOUNT_MAPPING_MANAGE)
    @PutMapping
    ResponseEntity<AccountingResponses.Mappings> put(
            @PathVariable UUID companyId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch,
            @Valid @RequestBody MappingsRequest request) {
        var set = mappings.apply(
                ifMatch,
                request.mappings().stream()
                        .map(m -> new AccountMappingService.Change(
                                m.mappingKey(), m.scopeType(), m.scopeId(), m.accountId()))
                        .toList());
        return ResponseEntity.ok()
                .eTag(EntityTags.forVersion(set.version()))
                .body(new AccountingResponses.Mappings(set.mappings().stream()
                        .map(AccountingResponses.Mapping::from)
                        .toList()));
    }

    @RequiresPermission(AccountingPermissions.ACCOUNT_READ)
    @GetMapping("/resolve")
    Resolution resolve(
            @PathVariable UUID companyId,
            @RequestParam String key,
            @RequestParam(required = false) @Nullable String scopeType,
            @RequestParam(required = false) @Nullable UUID scopeId) {
        var r = mappings.resolve(key, scopeType, scopeId);
        return new Resolution(r.mappingKey(), r.accountId(), r.accountCode());
    }
}
