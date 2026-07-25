package com.example.payment.scheduler;

import com.example.payment.application.service.SellerPayoutTransferCoordinator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "app.payout.transfer.reconciliation.enabled",
        havingValue = "true"
)
public class SellerPayoutTransferReconciliationJob {

    private final SellerPayoutTransferCoordinator coordinator;

    @Value("${app.payout.transfer.reconciliation.stale-seconds:300}")
    private long staleSeconds;

    @Value("${app.payout.transfer.reconciliation.batch-size:50}")
    private int batchSize;

    @Scheduled(
            fixedDelayString = "${app.payout.transfer.reconciliation.fixed-delay-ms:60000}",
            initialDelayString = "${app.payout.transfer.reconciliation.initial-delay-ms:30000}"
    )
    public void reconcile() {
        try {
            int reconciled = coordinator.reconcileStaleTransfers(Duration.ofSeconds(staleSeconds), batchSize);
            if (reconciled > 0) {
                log.info("Reconciled {} stale seller payout transfers", reconciled);
            }
        } catch (RuntimeException exception) {
            log.error("Seller payout transfer reconciliation failed", exception);
        }
    }
}
