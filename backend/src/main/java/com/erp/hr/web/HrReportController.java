package com.erp.hr.web;

import com.erp.hr.HrPermissions;
import com.erp.hr.application.HrReportService;
import com.erp.hr.application.HrViews;
import com.erp.platform.security.RequiresPermission;
import com.erp.platform.web.ApiPaths;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** HR reports as JSON. */
@RestController
class HrReportController {

    private final HrReportService reports;

    HrReportController(HrReportService reports) {
        this.reports = reports;
    }

    @RequiresPermission(HrPermissions.EMPLOYEE_READ)
    @GetMapping(ApiPaths.V1 + "/companies/{companyId}/hr-reports/headcount")
    HrViews.Headcount headcount(@PathVariable UUID companyId, @RequestParam LocalDate asOf) {
        return reports.headcount(asOf);
    }
}
