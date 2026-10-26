package com.example.payment.scheduler;

import com.example.payment.application.service.TossPaymentIntentService;
import com.example.payment.domain.repository.TossPaymentIntentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(
        name = "payment.toss.intent-expiry-enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class TossIntentExpiryJob {

    private final TossPaymentIntentRepository repository;
    private final TossPaymentIntentService intentService;

    @Scheduled(fixedDelayString = "${payment.toss.intent-expiry-fixed-delay-ms:5000}")
    public void releaseExpiredReadyIntents() {
        repository.findByStatusAndExpiresAtBefore("READY", LocalDateTime.now())
                .forEach(intent -> {
                    try {
                        intentService.cancelReadyIntent(intent.getIntentId());
                    } catch (RuntimeException failure) {
                        log.error("Expired Toss intent release failed: intentId={}", intent.getIntentId(), failure);
                    }
                });
    }
}
