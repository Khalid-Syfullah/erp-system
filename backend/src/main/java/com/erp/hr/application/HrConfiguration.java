package com.erp.hr.application;

import com.erp.org.api.OrgFacade;
import com.erp.platform.context.CurrentContext;
import com.erp.platform.context.RequestContext;
import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.Schedules;
import java.time.LocalTime;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** HR background jobs (on the worker): the daily leave accrual and the leave status update. */
@Configuration(proxyBeanMethods = false)
class HrConfiguration {

    private static final Logger log = LoggerFactory.getLogger(HrConfiguration.class);

    @Bean
    RecurringTask<Void> leaveDailyTask(
            LeaveAccrualService accruals, LeaveStatusSync status, HrCalendar calendar, OrgFacade org) {
        return Tasks.recurring("hr-leave-daily", Schedules.daily(LocalTime.of(0, 30)))
                .execute((instance, context) -> org.allCompanyIds().forEach(companyId -> {
                    try {
                        CurrentContext.callWith(
                                RequestContext.forRequest("job-hr-leave-" + UUID.randomUUID())
                                        .withCompany(companyId),
                                () -> {
                                    var today = calendar.today(companyId);
                                    accruals.accrue(today);
                                    return status.syncCompany(companyId, today);
                                });
                    } catch (RuntimeException e) {
                        log.error("Daily leave run failed for company {}", companyId, e);
                    }
                }));
    }
}
