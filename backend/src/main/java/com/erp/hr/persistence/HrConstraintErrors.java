package com.erp.hr.persistence;

import com.erp.hr.application.HrErrorCode;
import com.erp.platform.web.ConstraintErrorMapping;
import com.erp.platform.web.ErrorCode;
import com.erp.platform.web.PlatformErrorCode;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Specific error codes for the HR module's unique and exclusion constraints. */
@Component
class HrConstraintErrors implements ConstraintErrorMapping {

    @Override
    public Map<String, ErrorCode> constraintErrors() {
        return Map.of(
                "uq_positions__company_id_code", PlatformErrorCode.DUPLICATE_CODE,
                "uq_employees__company_id_employee_number", PlatformErrorCode.DUPLICATE_CODE,
                "uq_employees__company_id_work_email", HrErrorCode.DUPLICATE_WORK_EMAIL,
                "ex_employment_assignments__no_overlap", HrErrorCode.ASSIGNMENT_OVERLAP,
                "ex_department_heads__no_overlap", HrErrorCode.DEPARTMENT_HEAD_OVERLAP,
                "uq_employees__company_id_user_id", HrErrorCode.USER_ALREADY_LINKED,
                "ex_leave_requests__no_overlap", HrErrorCode.LEAVE_OVERLAP,
                "uq_leave_types__company_id_code", PlatformErrorCode.DUPLICATE_CODE,
                "uq_public_holidays__company_id_branch_id_holiday_date", PlatformErrorCode.CONFLICT,
                "uq_attendance_records__company_id_employee_id_work_date", HrErrorCode.ATTENDANCE_STATE);
    }
}
