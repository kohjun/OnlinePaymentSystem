package com.example.payment.application.event.publisher;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import com.example.payment.presentation.dto.response.ReservationStatusResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/**
 * 예약 이벤트 퍼블리셔
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ReservationEventPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    private static final String TOPIC = "reservation-events";

    /**
     * 예약 생성 이벤트
     */
    public void publishReservationCreated(ReservationStatusResponse reservation) {
        publishEvent("RESERVATION_CREATED", reservation);
    }

    /**
     * 예약 취소 이벤트
     */
    public void publishReservationCancelled(String reservationId, String reason) {
        try {
            Map<String, Object> eventData = Map.of(
                    "eventType", "RESERVATION_CANCELLED",
                    "reservationId", reservationId,
                    "reason", reason,
                    "timestamp", System.currentTimeMillis()
            );

            String eventJson = objectMapper.writeValueAsString(eventData);

            kafkaTemplate.send(TOPIC, reservationId, eventJson)
                    .whenComplete((result, ex) -> {
                        if (ex == null) {
                            log.debug("Reservation cancelled event published: {}", reservationId);
                        } else {
                            log.error("Failed to publish reservation cancelled event: {}", ex.getMessage());
                        }
                    });

        } catch (Exception e) {
            log.error("Error publishing reservation cancelled event: reservationId={}", reservationId, e);
        }
    }

    /**
     * 예약 만료 이벤트
     */
    public void publishReservationExpired(String reservationId) {
        try {
            Map<String, Object> eventData = Map.of(
                    "eventType", "RESERVATION_EXPIRED",
                    "reservationId", reservationId,
                    "timestamp", System.currentTimeMillis()
            );

            String eventJson = objectMapper.writeValueAsString(eventData);

            kafkaTemplate.send(TOPIC, reservationId, eventJson);
            log.info("Reservation expired event published: {}", reservationId);

        } catch (Exception e) {
            log.error("Error publishing reservation expired event: reservationId={}", reservationId, e);
        }
    }

    /**
     * 공통 이벤트 발행 메서드
     */
    private void publishEvent(String eventType, ReservationStatusResponse reservation) {
        try {
            Map<String, Object> eventData = Map.of(
                    "eventType", eventType,
                    "reservationId", reservation.getReservationId(),
                    "productId", reservation.getProductId(),
                    "quantity", reservation.getQuantity(),
                    "status", reservation.getStatus(),
                    "timestamp", System.currentTimeMillis()
            );

            String eventJson = objectMapper.writeValueAsString(eventData);

            kafkaTemplate.send(TOPIC, reservation.getReservationId(), eventJson)
                    .whenComplete((result, ex) -> {
                        if (ex == null) {
                            log.debug("Reservation event published: type={}, id={}",
                                    eventType, reservation.getReservationId());
                        } else {
                            log.error("Failed to publish reservation event: type={}, error={}",
                                    eventType, ex.getMessage());
                        }
                    });

        } catch (Exception e) {
            log.error("Error publishing reservation event: type={}, reservationId={}",
                    eventType, reservation.getReservationId(), e);
        }
    }
}
