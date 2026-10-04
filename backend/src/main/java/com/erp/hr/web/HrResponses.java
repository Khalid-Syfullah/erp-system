package com.erp.hr.web;

import com.erp.hr.application.HrViews;
import com.erp.platform.web.ApiException;
import com.erp.platform.web.EntityTags;
import com.erp.platform.web.FieldViolation;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;

/** Helpers shared by the HR controllers. */
final class HrResponses {

    static ResponseEntity<HrViews.LeaveRequest> withETag(HrViews.LeaveRequest request) {
        return ResponseEntity.ok()
                .eTag(EntityTags.forVersion(request.version()))
                .body(request);
    }

    static ApiException required(String pointer) {
        return ApiException.validationFailed(
                "A required field is missing.", List.of(FieldViolation.atPointer(pointer, "REQUIRED", "is required")));
    }

    static UUID uuid(String parameter, String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(
                    "Invalid parameter.",
                    List.of(FieldViolation.atParameter(parameter, "INVALID_VALUE", "must be a UUID")));
        }
    }

    private HrResponses() {}
}
