package com.erp.platform.idempotency;

import com.erp.platform.context.CurrentContext;
import com.erp.platform.context.RequestContext;
import com.erp.platform.idempotency.persistence.IdempotencyKeyRepository;
import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.Schedules;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.support.TransactionTemplate;

/** Purges expired idempotency keys (DATABASE.md §10: kept 24 h). Runs on the worker. */
@Configuration(proxyBeanMethods = false)
public class IdempotencyMaintenance {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyMaintenance.class);

    @Bean
    RecurringTask<Void> idempotencyKeyPurgeTask(IdempotencyKeyRepository keys, TransactionTemplate tx, Clock clock) {
        return Tasks.recurring("platform-idempotency-purge", Schedules.fixedDelay(Duration.ofHours(1)))
                .execute((instance, context) -> purge(keys, tx, clock));
    }

    static void purge(IdempotencyKeyRepository keys, TransactionTemplate tx, Clock clock) {
        Integer purged = CurrentContext.callWith(
                RequestContext.forRequest("job-idempotency-purge-" + UUID.randomUUID()),
                () -> tx.execute(status -> keys.purgeExpired(OffsetDateTime.now(clock))));
        if (purged != null && purged > 0) {
            log.info("Purged {} expired idempotency keys", purged);
        }
    }
}
