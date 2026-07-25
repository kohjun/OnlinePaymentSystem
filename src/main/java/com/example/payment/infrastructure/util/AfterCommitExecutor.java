package com.example.payment.infrastructure.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Slf4j
public final class AfterCommitExecutor {

    private AfterCommitExecutor() {
    }

    public static void run(Runnable action) {
        Runnable safeAction = () -> {
            try {
                action.run();
            } catch (RuntimeException exception) {
                log.warn("After-commit side effect failed; authoritative state remains in the database.", exception);
            }
        };
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            safeAction.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                safeAction.run();
            }
        });
    }
}
