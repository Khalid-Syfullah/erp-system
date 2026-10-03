package com.erp.hr.web;

import com.erp.hr.HrPermissions;
import com.erp.hr.application.EmploymentAssignmentService;
import com.erp.hr.application.HrListings;
import com.erp.platform.security.RequiresPermission;
import com.erp.platform.web.ApiPaths;
import com.erp.platform.web.paging.ListQueryParser;
import com.erp.platform.web.paging.PageResponse;
import java.util.UUID;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Company-wide view of employment assignments: who works in which branch, department and position,
 * and who reports to whom ({@code ?asOf=yyyy-MM-dd&filter[departmentId]=…}).
 */
@RestController
class EmploymentAssignmentController {

    private final EmploymentAssignmentService assignments;
    private final ListQueryParser parser;

    EmploymentAssignmentController(EmploymentAssignmentService assignments, ListQueryParser parser) {
        this.assignments = assignments;
        this.parser = parser;
    }

    @RequiresPermission(HrPermissions.EMPLOYEE_READ)
    @GetMapping(ApiPaths.V1 + "/companies/{companyId}/employment-assignments")
    PageResponse<HrRequests.AssignmentResponse> list(
            @PathVariable UUID companyId, @RequestParam MultiValueMap<String, String> parameters) {
        HrRequests.AsOf asOf = HrRequests.AsOf.from(parameters);
        return assignments
                .list(asOf.date(), parser.parse(asOf.remaining(), HrListings.ASSIGNMENTS))
                .map(HrRequests.AssignmentResponse::from);
    }
}
