package com.example.payment.infrastructure.messaging.outbox;

import com.example.payment.domain.entity.OutboxEvent;
import com.example.payment.domain.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.outbox.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxPublisher {

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final PlatformTransactionManager transactionManager;

    @Value("${app.outbox.batch-size:50}")
    private int batchSize;

    @Value("${app.outbox.max-retry:5}")
    private int maxRetry;

    @Value("${app.outbox.retry-backoff-seconds:5}")
    private long retryBackoffSeconds;

    @Scheduled(fixedDelayString = "${app.outbox.publish-fixed-delay-ms:1000}")
    public void publishPendingEvents() {
        List<OutboxEvent> events = transactionTemplate().execute(status ->
                outboxEventRepository.findTop50ByStatusInOrderByCreatedAtAsc(List.of("PENDING")));
        if (events == null || events.isEmpty()) {
            return;
        }

        events.stream()
                .filter(this::isReadyToPublish)
                .limit(batchSize)
                .forEach(event -> {
                    OutboxEvent claimed = claim(event.getEventId());
                    if (claimed != null) {
                        publishClaimedEvent(claimed);
                    }
                });
    }

    private void publishClaimedEvent(OutboxEvent event) {
        try {
            kafkaTemplate.send(event.getTopic(), event.getEventKey(), event.getPayload()).get();
            markPublished(event.getEventId());
        } catch (Exception e) {
            markPublishFailed(event.getEventId(), e);
            log.warn("Outbox publish failed: eventId={}, topic={}", event.getEventId(), event.getTopic(), e);
        }
    }

    private OutboxEvent claim(String eventId) {
        return transactionTemplate().execute(status -> outboxEventRepository.findForUpdateByEventId(eventId)
                .filter(this::isReadyToPublish)
                .map(event -> {
                    event.setStatus("IN_PROGRESS");
                    event.setLockedAt(LocalDateTime.now());
                    return outboxEventRepository.save(event);
                })
                .orElse(null));
    }

    private void markPublished(String eventId) {
        transactionTemplate().executeWithoutResult(status ->
                outboxEventRepository.findForUpdateByEventId(eventId).ifPresent(event -> {
                    event.setStatus("PUBLISHED");
                    event.setPublishedAt(LocalDateTime.now());
                    event.setLockedAt(null);
                    event.setLastError(null);
                    outboxEventRepository.save(event);
                }));
    }

    private void markPublishFailed(String eventId, Exception exception) {
        transactionTemplate().executeWithoutResult(status ->
                outboxEventRepository.findForUpdateByEventId(eventId).ifPresent(event -> {
                    int nextRetryCount = (event.getRetryCount() != null ? event.getRetryCount() : 0) + 1;
                    event.setRetryCount(nextRetryCount);
                    event.setLockedAt(null);
                    event.setLastError(exception.getMessage());

                    if (nextRetryCount >= maxRetry) {
                        event.setStatus("FAILED");
                        event.setNextAttemptAt(null);
                    } else {
                        event.setStatus("PENDING");
                        event.setNextAttemptAt(LocalDateTime.now().plusSeconds(retryBackoffSeconds * nextRetryCount));
                    }
                    outboxEventRepository.save(event);
                }));
    }

    private boolean isReadyToPublish(OutboxEvent event) {
        if (!"PENDING".equals(event.getStatus())) {
            return false;
        }
        if (event.getRetryCount() != null && event.getRetryCount() >= maxRetry) {
            return false;
        }
        return event.getNextAttemptAt() == null || !event.getNextAttemptAt().isAfter(LocalDateTime.now());
    }

    private TransactionTemplate transactionTemplate() {
        return new TransactionTemplate(transactionManager);
    }
}
