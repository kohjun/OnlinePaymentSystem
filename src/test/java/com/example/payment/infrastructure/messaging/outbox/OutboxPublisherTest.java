package com.example.payment.infrastructure.messaging.outbox;

import com.example.payment.domain.entity.OutboxEvent;
import com.example.payment.domain.repository.OutboxEventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class OutboxPublisherTest {

    private final OutboxEventRepository repository = mock(OutboxEventRepository.class);
    private final KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
    private final OutboxPublisher publisher = new OutboxPublisher(repository, kafkaTemplate, transactionManager());

    @Test
    void publishPendingEvents_marksPublishedWhenKafkaSendSucceeds() {
        OutboxEvent event = event(0);
        CompletableFuture<SendResult<String, String>> future = CompletableFuture.completedFuture(null);

        when(repository.resetStaleInProgressEvents(any(), any(), any())).thenReturn(0);
        when(repository.findByStatusInOrderByCreatedAtAsc(anyCollection(), any())).thenReturn(List.of(event));
        when(repository.findForUpdateByEventId(event.getEventId())).thenReturn(Optional.of(event));
        when(repository.save(event)).thenReturn(event);
        when(kafkaTemplate.send(event.getTopic(), event.getEventKey(), event.getPayload())).thenReturn(future);

        configurePublisher();
        publisher.publishPendingEvents();

        assertEquals("PUBLISHED", event.getStatus());
        assertNotNull(event.getPublishedAt());
        assertEquals(0, event.getRetryCount());
    }

    @Test
    void publishPendingEvents_retriesFailedKafkaSend() {
        OutboxEvent event = event(0);

        when(repository.resetStaleInProgressEvents(any(), any(), any())).thenReturn(0);
        when(repository.findByStatusInOrderByCreatedAtAsc(anyCollection(), any())).thenReturn(List.of(event));
        when(repository.findForUpdateByEventId(event.getEventId())).thenReturn(Optional.of(event));
        when(repository.save(event)).thenReturn(event);
        when(kafkaTemplate.send(event.getTopic(), event.getEventKey(), event.getPayload()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));

        configurePublisher();
        publisher.publishPendingEvents();

        assertEquals("PENDING", event.getStatus());
        assertEquals(1, event.getRetryCount());
        assertNotNull(event.getLastError());
        assertNotNull(event.getNextAttemptAt());
    }

    @Test
    void publishPendingEvents_marksFailedAtMaxRetry() {
        OutboxEvent event = event(4);

        when(repository.resetStaleInProgressEvents(any(), any(), any())).thenReturn(0);
        when(repository.findByStatusInOrderByCreatedAtAsc(anyCollection(), any())).thenReturn(List.of(event));
        when(repository.findForUpdateByEventId(event.getEventId())).thenReturn(Optional.of(event));
        when(repository.save(event)).thenReturn(event);
        when(kafkaTemplate.send(event.getTopic(), event.getEventKey(), event.getPayload()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));

        configurePublisher();
        publisher.publishPendingEvents();

        assertEquals("FAILED", event.getStatus());
        assertEquals(5, event.getRetryCount());
    }

    private void configurePublisher() {
        ReflectionTestUtils.setField(publisher, "batchSize", 50);
        ReflectionTestUtils.setField(publisher, "maxRetry", 5);
        ReflectionTestUtils.setField(publisher, "retryBackoffSeconds", 5L);
        ReflectionTestUtils.setField(publisher, "staleLockSeconds", 60L);
    }

    private OutboxEvent event(int retryCount) {
        return OutboxEvent.builder()
                .eventId("EVT-1")
                .aggregateType("RESERVATION")
                .aggregateId("RES-1")
                .eventType("RESERVATION_CREATED")
                .topic("reservation-events")
                .eventKey("RES-1")
                .payload("{}")
                .status("PENDING")
                .retryCount(retryCount)
                .createdAt(LocalDateTime.now().minusSeconds(1))
                .build();
    }

    private PlatformTransactionManager transactionManager() {
        return new AbstractPlatformTransactionManager() {
            @Override
            protected Object doGetTransaction() {
                return new Object();
            }

            @Override
            protected void doBegin(Object transaction, TransactionDefinition definition) {
            }

            @Override
            protected void doCommit(DefaultTransactionStatus status) {
            }

            @Override
            protected void doRollback(DefaultTransactionStatus status) {
            }
        };
    }
}
