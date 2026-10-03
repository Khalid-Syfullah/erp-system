package com.erp.partners.persistence;

import com.erp.platform.web.ConstraintErrorMapping;
import com.erp.platform.web.ErrorCode;
import com.erp.platform.web.PlatformErrorCode;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Error codes for the Partners module's constraints. */
@Component
class PartnersConstraintErrors implements ConstraintErrorMapping {

    @Override
    public Map<String, ErrorCode> constraintErrors() {
        return Map.ofEntries(
                Map.entry("uq_partners__company_id_code", PlatformErrorCode.DUPLICATE_CODE),
                Map.entry("uq_partner_groups__company_id_applies_to_code", PlatformErrorCode.DUPLICATE_CODE),
                Map.entry("pk_suppliers", PlatformErrorCode.CONFLICT),
                Map.entry("pk_customers", PlatformErrorCode.CONFLICT),
                Map.entry("uq_partner_addresses__default", PlatformErrorCode.CONFLICT),
                Map.entry("uq_partner_contacts__primary", PlatformErrorCode.CONFLICT),
                Map.entry("uq_partner_bank_accounts__default", PlatformErrorCode.CONFLICT));
    }
}
