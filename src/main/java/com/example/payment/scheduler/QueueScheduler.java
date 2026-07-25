package com.example.payment.scheduler;

import com.example.payment.application.service.StandbyQueueService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "app.queue.enabled", havingValue = "true")
public class QueueScheduler {

    private final StandbyQueueService standbyQueueService;

    @Scheduled(fixedDelayString = "${app.queue.promotion-fixed-delay-ms:1000}")
    public void promoteQueueUsers() {
        try {
            int promoted = standbyQueueService.promoteAllTenants();
            if (promoted > 0) {
                log.info("Promoted {} users from standby queues.", promoted);
            }
        } catch (Exception e) {
            log.error("Failed to promote users from standby queue", e);
        }
    }
}
