package com.example.payment.infrastructure.messaging.kafka.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import tools.jackson.core.exc.StreamReadException;
// PaymentRequest 제거 - 필요없음
import tools.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Configuration
@Slf4j
@RequiredArgsConstructor
public class KafkaErrorConfig {

    private final ObjectMapper objectMapper;

    @Bean
    public CommonErrorHandler kafkaErrorHandler() {
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(
                (record, exception) -> {
                    log.error("Error processing Kafka message: {}", exception.getMessage());
                    log.error("Failed record: {}", record.value());
                },
                new FixedBackOff(1000L, 3)
        );

        errorHandler.addNotRetryableExceptions(
                IllegalArgumentException.class,
                StreamReadException.class
        );

        errorHandler.setRetryListeners(
                (record, ex, deliveryAttempt) -> {
                    log.warn("Retry {} for record: {}",
                            deliveryAttempt, record.value());
                }
        );

        return errorHandler;
    }
}