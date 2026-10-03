package com.erp.org.persistence;

import com.erp.org.application.OrgErrorCode;
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
                "uq_branches__company_id_code", PlatformErrorCode.DUPLICATE_CODE,
                "uq_departments__company_id_code", PlatformErrorCode.DUPLICATE_CODE,
                "uq_tax_codes__company_id_code", PlatformErrorCode.DUPLICATE_CODE,
                "uq_payment_terms__company_id_code", PlatformErrorCode.DUPLICATE_CODE,
                "uq_exchange_rates__company_id_currency_code_rate_date", OrgErrorCode.DUPLICATE_EXCHANGE_RATE);
    }
}
