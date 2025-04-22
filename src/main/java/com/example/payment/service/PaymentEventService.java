package com.example.payment.service;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;
import org.springframework.util.concurrent.ListenableFuture;
import org.springframework.util.concurrent.ListenableFutureCallback;

import com.example.payment.dto.PaymentResponse;
import com.example.payment.event.PaymentEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@RequiredArgsConstructor
public class PaymentEventService {

    private final KafkaTemplate<String, PaymentEvent> kafkaTemplate;
    private static final String TOPIC = "payment-events";

    public void publishPaymentCreated(PaymentResponse payment) {
        publishEvent(PaymentEvent.PAYMENT_CREATED, payment);
    }

    public void publishPaymentProcessed(PaymentResponse payment) {
        publishEvent(PaymentEvent.PAYMENT_PROCESSED, payment);
    }

    public void publishPaymentFailed(PaymentResponse payment) {
        publishEvent(PaymentEvent.PAYMENT_FAILED, payment);
    }

    private void publishEvent(String eventType, PaymentResponse payment) {
        PaymentEvent event = new PaymentEvent(eventType, payment);

        log.info("Publishing payment event: {} with id: {}", eventType, payment.getPaymentId());

        // Spring Boot 2.6+ 및 Spring Kafka 최신 버전용
        kafkaTemplate.send(TOPIC, payment.getPaymentId(), event)
                .whenComplete((result, ex) -> {
                    if (ex == null) {
                        log.debug("Event published successfully: {}", event.getEventId());
                    } else {
                        log.error("Event publishing failed: {}", ex.getMessage());
                    }
                });
    }
}