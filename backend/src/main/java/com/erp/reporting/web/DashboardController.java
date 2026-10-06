package com.erp.reporting.web;

import com.erp.platform.security.AuthenticatedEndpoint;
import com.erp.reporting.application.DashboardService;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Role dashboards (API.md §17.11): widgets the caller may not see are omitted. */
@RestController
class DashboardController {

    private static final String C = ReportingController.C;

    private final DashboardService dashboards;

    DashboardController(DashboardService dashboards) {
        this.dashboards = dashboards;
    }

    @AuthenticatedEndpoint
    @GetMapping(C + "/dashboards")
    List<DashboardService.DashboardSummary> list(@PathVariable UUID companyId) {
        return dashboards.list();
    }

    @AuthenticatedEndpoint
    @GetMapping(C + "/dashboards/{dashboardCode}")
    DashboardService.Dashboard get(@PathVariable UUID companyId, @PathVariable String dashboardCode) {
        return dashboards.get(dashboardCode);
    }
}
