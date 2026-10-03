package com.erp.org.web;

import com.erp.org.OrgPermissions;
import com.erp.platform.numbering.DocumentNumberService;
import com.erp.platform.numbering.NumberFormat;
import com.erp.platform.security.RequiresPermission;
import com.erp.platform.web.ApiException;
import com.erp.platform.web.ApiPaths;
import com.erp.platform.web.EntityTags;
import com.erp.platform.web.FieldViolation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Document number formats of a company (API.md §17.3, PRODUCT_SPEC.md G-6). */
@RestController
class NumberingSettingsController {

    private final DocumentNumberService numbering;

    NumberingSettingsController(DocumentNumberService numbering) {
        this.numbering = numbering;
    }

    record FormatRequest(
            @NotNull @Size(max = 30) String prefix, @NotNull Integer padding) {}

    record SettingsRequest(@NotNull @Size(max = 50) Map<String, @Valid FormatRequest> formats) {}

    record FormatResponse(String documentType, String prefix, int padding, boolean isDefault, String example) {}

    record SettingsResponse(List<FormatResponse> formats) {}

    @RequiresPermission(OrgPermissions.COMPANY_MANAGE)
    @GetMapping(ApiPaths.V1 + "/companies/{companyId}/settings/numbering")
    ResponseEntity<SettingsResponse> get(@PathVariable UUID companyId) {
        return respond(numbering.formats());
    }

    /** Replaces the configured formats; document types left out use their defaults. */
    @RequiresPermission(OrgPermissions.COMPANY_MANAGE)
    @PutMapping(ApiPaths.V1 + "/companies/{companyId}/settings/numbering")
    ResponseEntity<SettingsResponse> put(
            @PathVariable UUID companyId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch,
            @Valid @RequestBody SettingsRequest request) {
        Map<String, NumberFormat> formats = new LinkedHashMap<>();
        List<FieldViolation> violations = new ArrayList<>();
        request.formats().forEach((type, f) -> {
            try {
                formats.put(type, new NumberFormat(f.prefix(), f.padding()));
            } catch (IllegalArgumentException e) {
                violations.add(FieldViolation.atPointer("/formats/" + type, "INVALID_VALUE", e.getMessage()));
            }
        });
        if (!violations.isEmpty()) {
            throw ApiException.validationFailed("The numbering settings are invalid.", violations);
        }
        return respond(numbering.replace(ifMatch, formats));
    }

    private static ResponseEntity<SettingsResponse> respond(DocumentNumberService.FormatsView view) {
        return ResponseEntity.ok()
                .eTag(EntityTags.forVersion(view.version()))
                .body(new SettingsResponse(view.formats().stream()
                        .map(f -> new FormatResponse(
                                f.documentType(),
                                f.format().prefix(),
                                f.format().padding(),
                                !f.companyDefined(),
                                f.format().render("2026", 1)))
                        .toList()));
    }
}
