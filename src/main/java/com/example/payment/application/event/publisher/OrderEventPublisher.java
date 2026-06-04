package com.example.payment.application.event.publisher;

import com.example.payment.infrastructure.messaging.outbox.OutboxEventService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class OrderEventPublisher {

    private static final String TOPIC = "order-events";

    private final OutboxEventService outboxEventService;

    public void publishOrderCreated(String orderId, String customerId, String reservationId) {
        Map<String, Object> payload = basePayload("ORDER_CREATED", orderId);
        payload.put("customerId", customerId);
        payload.put("reservationId", reservationId);
        record("ORDER_CREATED", orderId, payload);
    }

    public void publishOrderStatusChanged(String orderId, String oldStatus, String newStatus) {
        Map<String, Object> payload = basePayload("ORDER_STATUS_CHANGED", orderId);
        payload.put("oldStatus", oldStatus);
        payload.put("newStatus", newStatus);
        record("ORDER_STATUS_CHANGED", orderId, payload);
    }

    public void publishOrderCancelled(String orderId, String reason) {
        Map<String, Object> payload = basePayload("ORDER_CANCELLED", orderId);
        payload.put("reason", reason);
        record("ORDER_CANCELLED", orderId, payload);
    }

    private void record(String eventType, String orderId, Map<String, Object> payload) {
        try {
            outboxEventService.record("ORDER", orderId, eventType, TOPIC, orderId, payload);
            log.debug("Order event recorded in outbox: type={}, orderId={}", eventType, orderId);
        } catch (Exception e) {
            log.error("Error recording order event: type={}, orderId={}", eventType, orderId, e);
        }
    }

    private Map<String, Object> basePayload(String eventType, String orderId) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("eventType", eventType);
        payload.put("orderId", orderId);
        payload.put("timestamp", System.currentTimeMillis());
        return payload;
    }
}
