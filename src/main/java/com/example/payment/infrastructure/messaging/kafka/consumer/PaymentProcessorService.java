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

    /**
     * 결제 생성 이벤트 (예약 시)
     */
    public void publishPaymentCreated(PaymentResponse payment) {
        publishPaymentEvent(PaymentEvent.PAYMENT_CREATED, payment);
    }

    /**
     * 결제 처리 완료 이벤트
     */
    public void publishPaymentProcessed(PaymentResponse payment) {
        publishPaymentEvent(PaymentEvent.PAYMENT_PROCESSED, payment);

        // 주문 완료 이벤트도 함께 발행 (비동기 후처리용)
        if (payment.getOrderId() != null && payment.getReservationId() != null) {
            publishOrderCompletedEvent(payment);
        }
    }

    /**
     * 결제 실패 이벤트
     */
    public void publishPaymentFailed(PaymentResponse payment) {
        publishPaymentEvent(PaymentEvent.PAYMENT_FAILED, payment);

        // 예약 실패 이벤트도 함께 발행
        if (payment.getReservationId() != null) {
            publishReservationFailedEvent(payment);
        }
    }

    /**
     * 결제 이벤트 발행 (공통)
     */
    private void publishPaymentEvent(String eventType, PaymentResponse payment) {
        try {
            PaymentEvent event = new PaymentEvent(eventType, payment, payment.getReservationId());

            log.info("Publishing payment event: {} for paymentId: {}, reservationId: {}",
                    eventType, payment.getPaymentId(), payment.getReservationId());

            paymentEventKafkaTemplate.send(PAYMENT_EVENTS_TOPIC, payment.getPaymentId(), event)
                    .whenComplete((result, ex) -> {
                        if (ex == null) {
                            log.debug("Payment event published successfully: eventId={}, type={}",
                                    event.getEventId(), eventType);
                        } else {
                            log.error("Payment event publishing failed: eventType={}, error={}",
                                    eventType, ex.getMessage(), ex);
                        }
                    });

        } catch (Exception e) {
            log.error("Error publishing payment event: eventType={}, paymentId={}",
                    eventType, payment.getPaymentId(), e);
        }
    }

    /**
     * 주문 완료 이벤트 발행 (비동기 후처리용)
     */
    private void publishOrderCompletedEvent(PaymentResponse payment) {
        try {
            java.util.Map<String, Object> orderEvent = java.util.Map.of(
                    "eventType", "ORDER_COMPLETED",
                    "orderId", payment.getOrderId(),
                    "paymentId", payment.getPaymentId(),
                    "reservationId", payment.getReservationId(),
                    "customerId", extractCustomerIdFromPayment(payment),
                    "amount", payment.getAmount(),
                    "currency", payment.getCurrency(),
                    "timestamp", System.currentTimeMillis()
            );

            String eventJson = new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(orderEvent);

            stringKafkaTemplate.send(ORDER_EVENTS_TOPIC, payment.getOrderId(), eventJson)
                    .whenComplete((result, ex) -> {
                        if (ex == null) {
                            log.info("Order completed event published: orderId={}", payment.getOrderId());
                        } else {
                            log.error("Order completed event publishing failed: orderId={}, error={}",
                                    payment.getOrderId(), ex.getMessage());
                        }
                    });

        } catch (Exception e) {
            log.error("Error publishing order completed event: orderId={}", payment.getOrderId(), e);
        }
    }

    /**
     * 예약 실패 이벤트 발행
     */
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

            stringKafkaTemplate.send("reservation-events", payment.getReservationId(), eventJson)
                    .whenComplete((result, ex) -> {
                        if (ex == null) {
                            log.info("Reservation failed event published: reservationId={}",
                                    payment.getReservationId());
                        } else {
                            log.error("Reservation failed event publishing failed: reservationId={}, error={}",
                                    payment.getReservationId(), ex.getMessage());
                        }
                    });

        } catch (Exception e) {
            log.error("Error publishing reservation failed event: reservationId={}",
                    payment.getReservationId(), e);
        }
    }

    /**
     * PaymentResponse에서 customerId 추출 (필요시 PaymentResponse에 customerId 필드 추가)
     */
    private String extractCustomerIdFromPayment(PaymentResponse payment) {
        // TODO: PaymentResponse에 customerId 필드가 없다면 추가하거나
        // 다른 방법으로 customerId를 가져와야 함
        return "UNKNOWN"; // 임시값
    }
}