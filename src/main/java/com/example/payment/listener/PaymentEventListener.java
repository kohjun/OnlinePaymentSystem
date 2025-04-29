package com.example.payment.listener;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.example.payment.dto.PaymentResponse;
import com.example.payment.event.PaymentEvent;
import com.example.payment.service.OrderService;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 결제 이벤트 리스너
 * - 결제 이벤트를 수신하여 주문 상태 업데이트
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class PaymentEventListener {

    private final OrderService orderService;
    private final ObjectMapper objectMapper;

    /**
     * 결제 이벤트 처리
     */
    @KafkaListener(topics = "payment-events", groupId = "order-group")
    public void handlePaymentEvent(String eventJson) {
        try {
            PaymentEvent event = objectMapper.readValue(eventJson, PaymentEvent.class);
            PaymentResponse payment = event.getPayload();
            String orderId = payment.getOrderId();

            log.info("Received payment event: {} for order: {}", event.getEventType(), orderId);

            switch (event.getEventType()) {
                case PaymentEvent.PAYMENT_PROCESSED:
                    orderService.completePayment(orderId);
                    break;

                case PaymentEvent.PAYMENT_FAILED:
                    orderService.failPayment(orderId, payment.getMessage());
                    break;

                default:
                    log.info("Ignoring payment event: {}", event.getEventType());
            }

        } catch (Exception e) {
            log.error("Error processing payment event: {}", e.getMessage(), e);
        }
    }
}