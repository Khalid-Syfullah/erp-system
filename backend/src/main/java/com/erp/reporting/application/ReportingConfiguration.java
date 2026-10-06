package com.erp.reporting.application;

import com.erp.org.api.OrgFacade;
import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.Schedules;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The export worker's recurring tasks (run on the worker instance; tests call {@link ExportJobs} directly). */
@Configuration(proxyBeanMethods = false)
class ReportingConfiguration {

    @Bean
    RecurringTask<Void> reportExportTask(ExportJobs jobs, OrgFacade org) {
        return Tasks.recurring("reporting-exports", Schedules.fixedDelay(Duration.ofSeconds(10)))
                .execute((instance, context) -> org.allCompanyIds().forEach(jobs::processQueued));
    }

    @Bean
    RecurringTask<Void> reportExportExpiryTask(ExportJobs jobs, OrgFacade org) {
        return Tasks.recurring("reporting-export-expiry", Schedules.fixedDelay(Duration.ofHours(1)))
                .execute((instance, context) -> org.allCompanyIds().forEach(jobs::expire));
    }
}
