package com.erp.platform.tx;

import java.sql.SQLException;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Retries a whole transaction when PostgreSQL aborted it with a serialization failure ({@code 40001})
 * or a deadlock ({@code 40P01}) (ARCHITECTURE.md §6.2): up to three attempts with jittered backoff.
 * The work must start its own transaction and be idempotent within it, so it can only be used where
 * no transaction is active yet (typically by the web layer around a service call).
 */
@Component
public class TransactionRetry {

    public static final int MAX_ATTEMPTS = 3;
    private static final Set<String> RETRYABLE = Set.of("40001", "40P01");
    private static final Logger log = LoggerFactory.getLogger(TransactionRetry.class);

    public <T> T run(Supplier<T> work) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("TransactionRetry must wrap the whole transaction");
        }
        for (int attempt = 1; ; attempt++) {
            try {
                return work.get();
            } catch (RuntimeException e) {
                if (attempt >= MAX_ATTEMPTS || !isRetryableFailure(e)) {
                    throw e;
                }
                log.info("Retrying transaction after {} (attempt {} of {})", sqlState(e), attempt + 1, MAX_ATTEMPTS);
                pause(attempt);
            }
        }
    }

    /** Whether the failure is a serialization failure or deadlock that a retry may resolve. */
    public static boolean isRetryableFailure(Throwable failure) {
        String state = sqlState(failure);
        return state != null && RETRYABLE.contains(state);
    }

    private static String sqlState(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
        }
        return null;
    }

    private static void pause(int attempt) {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(10, 50) * attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
