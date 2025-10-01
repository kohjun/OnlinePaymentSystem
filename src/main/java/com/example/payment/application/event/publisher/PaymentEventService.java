package com.example.payment.application.event.publisher;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import com.example.payment.presentation.dto.response.PaymentResponse;
import com.example.payment.domain.event.PaymentEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@RequiredArgsConstructor
public class PaymentEventService {

    private final KafkaTemplate<String, PaymentEvent> paymentEventKafkaTemplate;
    private final KafkaTemplate<String, String> stringKafkaTemplate;

    private static final String PAYMENT_EVENTS_TOPIC = "payment-events";
    private static final String ORDER_EVENTS_TOPIC = "order-events";

    public void publishPaymentCreated(PaymentResponse payment) {
        publishPaymentEvent(PaymentEvent.PAYMENT_CREATED, payment);
    }

    public void publishPaymentProcessed(PaymentResponse payment) {
        publishPaymentEvent(PaymentEvent.PAYMENT_PROCESSED, payment);

        if (payment.getOrderId() != null && payment.getReservationId() != null) {
            publishOrderCompletedEvent(payment);
        }
    }

    public void publishPaymentFailed(PaymentResponse payment) {
        publishPaymentEvent(PaymentEvent.PAYMENT_FAILED, payment);

        if (payment.getReservationId() != null) {
            publishReservationFailedEvent(payment);
        }
    }

    private void publishPaymentEvent(String eventType, PaymentResponse payment) {
        try {
            PaymentEvent event = new PaymentEvent(eventType, payment, payment.getReservationId());

            log.info("Publishing payment event: {} for paymentId: {}, reservationId: {}",
                    eventType, payment.getPaymentId(), payment.getReservationId());

            paymentEventKafkaTemplate.send(PAYMENT_EVENTS_TOPIC, payment.getPaymentId(), event)
                    .whenComplete((result, ex) -> {
                        if (ex == null) {
                            log.debug("Payment event published successfully");
                        } else {
                            log.error("Payment event publishing failed: {}", ex.getMessage());
                        }
                    });

        } catch (Exception e) {
            log.error("Error publishing payment event: {}", e.getMessage());
        }
    }

    private void publishOrderCompletedEvent(PaymentResponse payment) {
        try {
            java.util.Map<String, Object> orderEvent = java.util.Map.of(
                    "eventType", "ORDER_COMPLETED",
                    "orderId", payment.getOrderId(),
                    "paymentId", payment.getPaymentId(),
                    "reservationId", payment.getReservationId(),
                    "amount", payment.getAmount(),
                    "currency", payment.getCurrency(),
                    "timestamp", System.currentTimeMillis()
            );

            String eventJson = new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(orderEvent);

            stringKafkaTemplate.send(ORDER_EVENTS_TOPIC, payment.getOrderId(), eventJson);

        } catch (Exception e) {
            log.error("Error publishing order completed event", e);
        }
    }

    private void publishReservationFailedEvent(PaymentResponse payment) {
        try {
            java.util.Map<String, Object> reservationEvent = java.util.Map.of(
                    "eventType", "RESERVATION_PAYMENT_FAILED",
                    "reservationId", payment.getReservationId(),
                    "paymentId", payment.getPaymentId(),
                    "reason", payment.getMessage() != null ? payment.getMessage() : "Payment failed",
                    "timestamp", System.currentTimeMillis()
            );

            String eventJson = new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(reservationEvent);

            stringKafkaTemplate.send("reservation-events", payment.getReservationId(), eventJson);

        } catch (Exception e) {
            log.error("Error publishing reservation failed event", e);
        }
    }
}