package com.erp.platform.tx;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Runs side effects (emails, notifications) only after the surrounding transaction committed, so no
 * remote call happens inside a transaction and nothing is sent for rolled-back work (ARCHITECTURE.md
 * §6.2). Without an active transaction the action runs immediately. Failures are logged, never
 * propagated: the business change has already committed.
 */
public final class AfterCommit {

    private static final Logger log = LoggerFactory.getLogger(AfterCommit.class);

    private AfterCommit() {}

    public static void run(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    safely(action);
                }
            });
        } else {
            safely(action);
        }
    }

    private static void safely(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            log.error("After-commit action failed", e);
        }
    }
}
