package com.erp.platform.tx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class TransactionRetryTest {

    private final TransactionRetry retry = new TransactionRetry();

    @Test
    void retriesDeadlocksAndSerializationFailures() {
        AtomicInteger attempts = new AtomicInteger();
        String result = retry.run(() -> {
            if (attempts.incrementAndGet() < 3) {
                throw failure(attempts.get() == 1 ? "40P01" : "40001");
            }
            return "done";
        });
        assertThat(result).isEqualTo("done");
        assertThat(attempts).hasValue(3);
    }

    @Test
    void givesUpAfterThreeAttempts() {
        AtomicInteger attempts = new AtomicInteger();
        assertThatThrownBy(() -> retry.run(() -> {
                    attempts.incrementAndGet();
                    throw failure("40P01");
                }))
                .isInstanceOf(CannotAcquireLockException.class);
        assertThat(attempts).hasValue(TransactionRetry.MAX_ATTEMPTS);
    }

    @Test
    void doesNotRetryOtherFailures() {
        AtomicInteger attempts = new AtomicInteger();
        assertThatThrownBy(() -> retry.run(() -> {
                    attempts.incrementAndGet();
                    throw failure("23505");
                }))
                .isInstanceOf(CannotAcquireLockException.class);
        assertThat(attempts).hasValue(1);
        assertThat(TransactionRetry.isRetryableFailure(new IllegalStateException("no sql")))
                .isFalse();
    }

    @Test
    void mustWrapTheWholeTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> retry.run(() -> "x")).isInstanceOf(IllegalStateException.class);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    private static RuntimeException failure(String sqlState) {
        return new CannotAcquireLockException("failed", new SQLException("failed", sqlState));
    }
}
