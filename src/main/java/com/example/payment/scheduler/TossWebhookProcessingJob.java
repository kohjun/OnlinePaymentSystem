package com.example.payment.scheduler;

import com.example.payment.application.service.TossWebhookService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "payment.toss.webhook.enabled", havingValue = "true")
public class TossWebhookProcessingJob {

    private final TossWebhookService tossWebhookService;

    @Scheduled(fixedDelayString = "${payment.toss.webhook.retry-fixed-delay-ms:30000}")
    public void processPendingWebhookEvents() {
        int processed = tossWebhookService.processPendingEvents();
        if (processed > 0) {
            log.info("Processed Toss webhook events: count={}", processed);
        }
    }
}
