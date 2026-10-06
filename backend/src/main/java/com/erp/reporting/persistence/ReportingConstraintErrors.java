package com.erp.reporting.persistence;

import com.erp.platform.web.ConstraintErrorMapping;
import com.erp.platform.web.ErrorCode;
import com.erp.reporting.application.ReportingErrorCode;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Specific error codes for the Reporting module's unique constraints. */
@Component
class ReportingConstraintErrors implements ConstraintErrorMapping {

    @Override
    public Map<String, ErrorCode> constraintErrors() {
        return Map.of("uq_saved_reports__company_id_user_id_name", ReportingErrorCode.DUPLICATE_SAVED_REPORT);
    }
}
