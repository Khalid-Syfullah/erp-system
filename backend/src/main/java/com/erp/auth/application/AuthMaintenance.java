package com.erp.auth.application;

import com.erp.auth.persistence.LoginProtectionRepository;
import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.Schedules;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Hourly purge of expired sessions, challenges, one-time tokens, throttle counters and login attempts
 * older than the retention (DATABASE.md §10). Runs on the worker (db-scheduler).
 */
@Configuration(proxyBeanMethods = false)
public class AuthMaintenance {

    private static final Logger log = LoggerFactory.getLogger(AuthMaintenance.class);

    @Bean
    RecurringTask<Void> authSecurityRecordPurgeTask(LoginProtectionRepository repository, AuthProperties properties) {
        return Tasks.recurring("auth-security-record-purge", Schedules.fixedDelay(Duration.ofHours(1)))
                .execute((instance, context) -> {
                    int deleted = repository.purge(
                            (int) properties.login().attemptRetention().toDays());
                    if (deleted > 0) {
                        log.info("Purged {} expired security records", deleted);
                    }
                });
    }
}
