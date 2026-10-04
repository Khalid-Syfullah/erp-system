package com.erp.payroll.persistence;

import com.erp.payroll.application.PayrollErrorCode;
import com.erp.platform.web.ConstraintErrorMapping;
import com.erp.platform.web.ErrorCode;
import com.erp.platform.web.PlatformErrorCode;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Specific error codes for the Payroll module's unique and exclusion constraints. */
@Component
class PayrollConstraintErrors implements ConstraintErrorMapping {

    @Override
    public Map<String, ErrorCode> constraintErrors() {
        return Map.of(
                "uq_pay_components__company_id_code", PlatformErrorCode.DUPLICATE_CODE,
                "uq_salary_structures__company_id_code", PlatformErrorCode.DUPLICATE_CODE,
                "uq_pay_schedules__company_id_code", PlatformErrorCode.DUPLICATE_CODE,
                "ex_employee_compensations__no_overlap", PayrollErrorCode.COMPENSATION_OVERLAP,
                "uq_payroll_runs__regular", PayrollErrorCode.REGULAR_RUN_EXISTS,
                "uq_payroll_inputs__entry", PlatformErrorCode.CONFLICT,
                "ck_payroll_runs__frozen", PlatformErrorCode.INVALID_STATE,
                "ck_payslips__frozen", PlatformErrorCode.INVALID_STATE,
                "ck_payslip_lines__frozen", PlatformErrorCode.INVALID_STATE);
    }
}
