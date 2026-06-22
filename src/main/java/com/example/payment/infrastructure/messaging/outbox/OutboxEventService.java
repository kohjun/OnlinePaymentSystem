package com.example.payment.infrastructure.messaging.outbox;

import com.example.payment.domain.entity.OutboxEvent;
import com.example.payment.domain.repository.OutboxEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxEventService {

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    @Value("${app.outbox.enabled:true}")
    private boolean outboxEnabled;

    @Transactional
    public void record(String aggregateType, String aggregateId, String eventType,
                       String topic, String eventKey, Map<String, Object> payload) {
        if (!outboxEnabled) {
            return;
        }

        String eventId = deterministicEventId(aggregateType, aggregateId, eventType);
        if (outboxEventRepository.existsById(eventId)) {
            log.debug("Outbox event already exists: eventId={}", eventId);
            return;
        }

        try {
            LocalDateTime occurredAt = LocalDateTime.now();
            outboxEventRepository.save(OutboxEvent.builder()
                    .eventId(eventId)
                    .aggregateType(aggregateType)
                    .aggregateId(aggregateId)
                    .eventType(eventType)
                    .topic(topic)
                    .eventKey(eventKey)
                    .payload(objectMapper.writeValueAsString(envelope(
                            eventId,
                            aggregateType,
                            aggregateId,
                            eventType,
                            payload,
                            occurredAt
                    )))
                    .status("PENDING")
                    .retryCount(0)
                    .createdAt(occurredAt)
                    .nextAttemptAt(occurredAt)
                    .build());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to record outbox event: " + eventId, e);
        }
    }

    private Map<String, Object> envelope(String eventId, String aggregateType, String aggregateId,
                                         String eventType, Map<String, Object> payload,
                                         LocalDateTime occurredAt) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", eventId);
        envelope.put("eventType", eventType);
        envelope.put("schemaVersion", 1);
        envelope.put("aggregateType", aggregateType);
        envelope.put("aggregateId", aggregateId);
        envelope.put("occurredAt", occurredAt.toString());
        envelope.put("payload", payload);
        return envelope;
    }

    private String deterministicEventId(String aggregateType, String aggregateId, String eventType) {
        String source = aggregateType + ":" + aggregateId + ":" + eventType;
        return UUID.nameUUIDFromBytes(source.getBytes(StandardCharsets.UTF_8)).toString();
    }
}
