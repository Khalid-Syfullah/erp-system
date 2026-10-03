package com.erp.auth.persistence;

import com.erp.auth.application.AuthErrorCode;
import com.erp.platform.web.ConstraintErrorMapping;
import com.erp.platform.web.ErrorCode;
import com.erp.platform.web.PlatformErrorCode;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Specific error codes for the Auth module's unique constraints. */
@Component
class AuthConstraintErrors implements ConstraintErrorMapping {

    @Override
    public Map<String, ErrorCode> constraintErrors() {
        return Map.of(
                "uq_users__email", AuthErrorCode.DUPLICATE_EMAIL,
                "uq_roles__code", PlatformErrorCode.DUPLICATE_CODE,
                "uq_role_assignments__user_id_role_id_company_id", AuthErrorCode.DUPLICATE_ASSIGNMENT);
    }
}
