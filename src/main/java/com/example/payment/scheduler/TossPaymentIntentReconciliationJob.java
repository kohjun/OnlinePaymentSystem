package com.example.payment.scheduler;

import com.example.payment.application.service.TossPaymentIntentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "payment.toss.reconciliation.enabled", havingValue = "true", matchIfMissing = true)
public class TossPaymentIntentReconciliationJob {

    private final TossPaymentIntentService tossPaymentIntentService;

    @Value("${payment.toss.reconciliation.stale-seconds:300}")
    private long staleSeconds;

    @Scheduled(fixedDelayString = "${payment.toss.reconciliation.fixed-delay-ms:60000}")
    public void reconcileTossPaymentIntents() {
        LocalDateTime cutoff = LocalDateTime.now().minusSeconds(staleSeconds);
        int recovered = tossPaymentIntentService.reconcileRecoverableIntents(cutoff);
        if (recovered > 0) {
            log.info("Recovered Toss payment intents: count={}, cutoff={}", recovered, cutoff);
        }
    }

}
