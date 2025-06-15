package com.example.payment.application.event.handler;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.example.payment.domain.event.PaymentEvent;
import com.example.payment.application.service.OrderService;
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
    public void handlePaymentEvent(PaymentEvent event) {
        try {
            log.info("Received payment event: {}", event.getEventType());

            if (PaymentEvent.PAYMENT_PROCESSED.equals(event.getEventType())) {
                orderService.completePayment(event.getPayload().getOrderId());
            } else if (PaymentEvent.PAYMENT_FAILED.equals(event.getEventType())) {
                orderService.failPayment(
                        event.getPayload().getOrderId(),
                        event.getPayload().getMessage()
                );
            }
        } catch (Exception e) {
            log.error("Error processing payment event: {}", e.getMessage(), e);
        }
    }
}