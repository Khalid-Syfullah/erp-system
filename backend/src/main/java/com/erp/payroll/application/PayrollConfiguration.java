package com.erp.payroll.application;

import com.erp.org.api.OrgFacade;
import com.erp.payroll.domain.PayrollCalculator;
import com.erp.payroll.domain.statutory.FlatPercentageRule;
import com.erp.payroll.domain.statutory.NoStatutoryRule;
import com.erp.payroll.domain.statutory.StatutoryRule;
import com.erp.payroll.domain.statutory.StatutoryRules;
import com.erp.platform.numbering.DocumentType;
import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.Schedules;
import java.time.Duration;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Payroll wiring: the statutory rules (every {@link StatutoryRule} bean — a country pack adds its own
 * beans), the calculator, run numbering and the worker's recurring tasks.
 */
@Configuration(proxyBeanMethods = false)
class PayrollConfiguration {

    @Bean
    NoStatutoryRule noStatutoryRule() {
        return new NoStatutoryRule();
    }

    @Bean
    FlatPercentageRule flatPercentageRule() {
        return new FlatPercentageRule();
    }

    @Bean
    StatutoryRules statutoryRules(List<StatutoryRule> rules) {
        return new StatutoryRules(rules);
    }

    @Bean
    PayrollCalculator payrollCalculator(StatutoryRules rules) {
        return new PayrollCalculator(rules);
    }

    @Bean
    DocumentType payrollRunDocumentType() {
        return new DocumentType(RunService.DOCUMENT_TYPE, "PR-{FY}-", 6);
    }

    @Bean
    RecurringTask<Void> payrollCalculationTask(PayrollJobs jobs, OrgFacade org) {
        return Tasks.recurring("payroll-calculation", Schedules.fixedDelay(Duration.ofSeconds(15)))
                .execute((instance, context) -> org.allCompanyIds().forEach(jobs::calculateQueued));
    }

    @Bean
    RecurringTask<Void> payslipPdfTask(PayrollJobs jobs, OrgFacade org) {
        return Tasks.recurring("payroll-payslip-pdfs", Schedules.fixedDelay(Duration.ofMinutes(1)))
                .execute((instance, context) -> org.allCompanyIds().forEach(jobs::renderPendingPayslips));
    }
}
