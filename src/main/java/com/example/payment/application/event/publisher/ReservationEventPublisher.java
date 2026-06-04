package com.example.payment.application.event.publisher;

import com.example.payment.domain.model.reservation.InventoryReservation;
import com.example.payment.infrastructure.messaging.outbox.OutboxEventService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class ReservationEventPublisher {

    private static final String TOPIC = "reservation-events";

    private final OutboxEventService outboxEventService;

    public void publishReservationCreated(InventoryReservation reservation) {
        Map<String, Object> payload = basePayload("RESERVATION_CREATED", reservation.getReservationId());
        payload.put("productId", reservation.getProductId());
        payload.put("quantity", reservation.getQuantity());
        payload.put("status", reservation.getStatus());
        record("RESERVATION_CREATED", reservation.getReservationId(), payload);
    }

    public void publishReservationConfirmed(String reservationId, String orderId, String paymentId) {
        Map<String, Object> payload = basePayload("RESERVATION_CONFIRMED", reservationId);
        payload.put("orderId", orderId);
        payload.put("paymentId", paymentId);
        record("RESERVATION_CONFIRMED", reservationId, payload);
    }

    public void publishReservationCancelled(String reservationId, String reason) {
        Map<String, Object> payload = basePayload("RESERVATION_CANCELLED", reservationId);
        payload.put("reason", reason);
        record("RESERVATION_CANCELLED", reservationId, payload);
    }

    public void publishReservationExpired(String reservationId) {
        Map<String, Object> payload = basePayload("RESERVATION_EXPIRED", reservationId);
        record("RESERVATION_EXPIRED", reservationId, payload);
    }

    private void record(String eventType, String reservationId, Map<String, Object> payload) {
        try {
            outboxEventService.record("RESERVATION", reservationId, eventType, TOPIC, reservationId, payload);
            log.debug("Reservation event recorded in outbox: type={}, reservationId={}", eventType, reservationId);
        } catch (Exception e) {
            log.error("Error recording reservation event: type={}, reservationId={}", eventType, reservationId, e);
        }
    }

    private Map<String, Object> basePayload(String eventType, String reservationId) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("eventType", eventType);
        payload.put("reservationId", reservationId);
        payload.put("timestamp", System.currentTimeMillis());
        return payload;
    }
}
