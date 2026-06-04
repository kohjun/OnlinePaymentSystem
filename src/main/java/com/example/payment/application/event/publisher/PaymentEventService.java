package com.example.payment.application.event.publisher;

import com.example.payment.domain.event.PaymentEvent;
import com.example.payment.domain.model.payment.Payment;
import com.example.payment.infrastructure.messaging.outbox.OutboxEventService;
import com.example.payment.presentation.dto.response.PaymentResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class PaymentEventService {

    private static final String PAYMENT_EVENTS_TOPIC = "payment-events";
    private static final String ORDER_EVENTS_TOPIC = "order-events";
    private static final String RESERVATION_EVENTS_TOPIC = "reservation-events";

    private final OutboxEventService outboxEventService;

    public void publishPaymentCreated(PaymentResponse payment) {
        publishPaymentEvent(PaymentEvent.PAYMENT_CREATED, payment);
    }

    public void publishPaymentProcessed(Payment payment) {
        PaymentResponse paymentResponse = convertToPaymentResponse(payment);
        publishPaymentEvent(PaymentEvent.PAYMENT_PROCESSED, paymentResponse);

        if (payment.isCompleted() && payment.getOrderId() != null) {
            publishOrderCompletedEvent(paymentResponse);
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
            Map<String, Object> payload = paymentPayload(eventType, payment);
            outboxEventService.record("PAYMENT", payment.getPaymentId(), eventType,
                    PAYMENT_EVENTS_TOPIC, payment.getPaymentId(), payload);
            log.debug("Payment event recorded in outbox: type={}, paymentId={}", eventType, payment.getPaymentId());
        } catch (Exception e) {
            log.error("Error recording payment event: type={}, paymentId={}",
                    eventType, payment != null ? payment.getPaymentId() : null, e);
        }
    }

    private PaymentResponse convertToPaymentResponse(Payment payment) {
        return PaymentResponse.builder()
                .paymentId(payment.getPaymentId())
                .reservationId(payment.getReservationId())
                .orderId(payment.getOrderId())
                .amount(payment.getAmount().getAmount())
                .currency(payment.getAmount().getCurrency())
                .status(payment.getStatus().name())
                .transactionId(payment.getTransactionId())
                .approvalNumber(payment.getApprovalNumber())
                .gatewayName(payment.getGatewayName())
                .processedAt(payment.getProcessedAt())
                .build();
    }

    private void publishOrderCompletedEvent(PaymentResponse payment) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("eventType", "ORDER_COMPLETED");
            payload.put("orderId", payment.getOrderId());
            payload.put("paymentId", payment.getPaymentId());
            payload.put("reservationId", payment.getReservationId());
            payload.put("amount", payment.getAmount());
            payload.put("currency", payment.getCurrency());
            payload.put("timestamp", System.currentTimeMillis());

            outboxEventService.record("ORDER", payment.getOrderId(), "ORDER_COMPLETED",
                    ORDER_EVENTS_TOPIC, payment.getOrderId(), payload);
        } catch (Exception e) {
            log.error("Error recording order completed event", e);
        }
    }

    private void publishReservationFailedEvent(PaymentResponse payment) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("eventType", "RESERVATION_PAYMENT_FAILED");
            payload.put("reservationId", payment.getReservationId());
            payload.put("paymentId", payment.getPaymentId());
            payload.put("reason", payment.getMessage() != null ? payment.getMessage() : "Payment failed");
            payload.put("timestamp", System.currentTimeMillis());

            outboxEventService.record("RESERVATION", payment.getReservationId(), "RESERVATION_PAYMENT_FAILED",
                    RESERVATION_EVENTS_TOPIC, payment.getReservationId(), payload);
        } catch (Exception e) {
            log.error("Error recording reservation payment failed event", e);
        }
    }

    private Map<String, Object> paymentPayload(String eventType, PaymentResponse payment) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("eventType", eventType);
        payload.put("paymentId", payment.getPaymentId());
        payload.put("reservationId", payment.getReservationId());
        payload.put("orderId", payment.getOrderId());
        payload.put("amount", payment.getAmount());
        payload.put("currency", payment.getCurrency());
        payload.put("status", payment.getStatus());
        payload.put("transactionId", payment.getTransactionId());
        payload.put("approvalNumber", payment.getApprovalNumber());
        payload.put("gatewayName", payment.getGatewayName());
        payload.put("message", payment.getMessage());
        payload.put("processedAt", payment.getProcessedAt());
        payload.put("timestamp", System.currentTimeMillis());
        return payload;
    }
}
