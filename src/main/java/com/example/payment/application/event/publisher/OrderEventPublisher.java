package com.example.payment.application.event.publisher;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/**
 * 주문 이벤트 퍼블리셔
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class OrderEventPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    private static final String TOPIC = "order-events";

    /**
     * 주문 생성 이벤트
     */
    public void publishOrderCreated(String orderId, String customerId, String reservationId) {
        publishEvent("ORDER_CREATED", orderId, Map.of(
                "customerId", customerId,
                "reservationId", reservationId
        ));
    }

    /**
     * 주문 상태 변경 이벤트
     */
    public void publishOrderStatusChanged(String orderId, String oldStatus, String newStatus) {
        publishEvent("ORDER_STATUS_CHANGED", orderId, Map.of(
                "oldStatus", oldStatus,
                "newStatus", newStatus
        ));
    }

    /**
     * 주문 취소 이벤트
     */
    public void publishOrderCancelled(String orderId, String reason) {
        publishEvent("ORDER_CANCELLED", orderId, Map.of(
                "reason", reason
        ));
    }

    /**
     * 공통 이벤트 발행 메서드
     */
    private void publishEvent(String eventType, String orderId, Map<String, Object> additionalData) {
        try {
            Map<String, Object> eventData = new java.util.HashMap<>(additionalData);
            eventData.put("eventType", eventType);
            eventData.put("orderId", orderId);
            eventData.put("timestamp", System.currentTimeMillis());

            String eventJson = objectMapper.writeValueAsString(eventData);

            kafkaTemplate.send(TOPIC, orderId, eventJson)
                    .whenComplete((result, ex) -> {
                        if (ex == null) {
                            log.debug("Order event published: type={}, orderId={}", eventType, orderId);
                        } else {
                            log.error("Failed to publish order event: type={}, orderId={}, error={}",
                                    eventType, orderId, ex.getMessage());
                        }
                    });

        } catch (Exception e) {
            log.error("Error publishing order event: type={}, orderId={}", eventType, orderId, e);
        }
    }
}