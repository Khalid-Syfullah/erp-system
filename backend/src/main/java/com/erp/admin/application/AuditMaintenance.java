package com.erp.admin.application;

import com.erp.admin.persistence.AuditLogRepository;
import com.erp.platform.context.CurrentContext;
import com.erp.platform.context.RequestContext;
import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.Schedules;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.support.TransactionTemplate;

/** Keeps twelve months of audit partitions ahead of time (DATABASE.md §10). Runs on the worker. */
@Configuration(proxyBeanMethods = false)
public class AuditMaintenance {

    private static final Logger log = LoggerFactory.getLogger(AuditMaintenance.class);
    static final int MONTHS_AHEAD = 12;

    @Bean
    RecurringTask<Void> auditPartitionMaintenanceTask(AuditLogRepository repository, TransactionTemplate tx) {
        return Tasks.recurring("admin-audit-partitions", Schedules.fixedDelay(Duration.ofHours(6)))
                .execute((instance, context) -> ensurePartitions(repository, tx));
    }

    static void ensurePartitions(AuditLogRepository repository, TransactionTemplate tx) {
        Integer created = CurrentContext.callWith(
                RequestContext.forRequest("job-audit-partitions-" + UUID.randomUUID()),
                () -> tx.execute(status -> repository.ensurePartitions(MONTHS_AHEAD)));
        if (created != null && created > 0) {
            log.info("Created {} audit log partitions", created);
        }
    }
}
