package com.erp.org.persistence;

import com.erp.platform.web.ConstraintErrorMapping;
import com.erp.platform.web.ErrorCode;
import com.erp.platform.web.PlatformErrorCode;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Specific error codes for the Organization module's unique constraints. */
@Component
class OrgConstraintErrors implements ConstraintErrorMapping {

    @Override
    public Map<String, ErrorCode> constraintErrors() {
        return Map.of(
                "uq_companies__code", PlatformErrorCode.DUPLICATE_CODE,
                "uq_branches__company_id_code", PlatformErrorCode.DUPLICATE_CODE);
    }
}
