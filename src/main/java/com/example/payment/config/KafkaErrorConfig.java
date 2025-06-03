package com.example.payment.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import com.fasterxml.jackson.core.JsonParseException;
import com.example.payment.dto.PaymentRequest;
import com.fasterxml.jackson.databind.ObjectMapper;

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
                    // 실패한 메시지 로깅
                    log.error("Error processing Kafka message: {}", exception.getMessage());
                    log.error("Failed record: {}", record.value());

                    // 필요한 경우 추가 복구 로직 구현
                    try {
                        // 메시지 내용에 따라 적절한 로직 수행
                        // 예: 결제 메시지인 경우 결제 상태 업데이트
                        if (record.topic().equals("payment-requests")) {
                            try {
                                PaymentRequest request = objectMapper.readValue(
                                        record.value().toString(), PaymentRequest.class);

                                log.warn("Failed payment request: ID={}, Amount={}",
                                        request.getPaymentId(), request.getAmount());

                                // 여기에 필요한 복구 로직 추가
                                // 예: cacheService.cacheData("payment:" + request.getPaymentId(), "FAILED", 300);
                            } catch (Exception e) {
                                log.error("Error parsing payment request", e);
                            }
                        }
                    } catch (Exception e) {
                        log.error("Error in error handler recovery logic", e);
                    }
                },
                new FixedBackOff(1000L, 3) // 1초 간격으로 3번 재시도
        );

        // 재시도하지 않을 예외 설정
        errorHandler.addNotRetryableExceptions(
                IllegalArgumentException.class,
                JsonParseException.class
        );

        // 재시도 리스너 추가
        errorHandler.setRetryListeners(
                (record, ex, deliveryAttempt) -> {
                    log.warn("Retry {} for record: {}",
                            deliveryAttempt, record.value());
                }
        );

        return errorHandler;
    }
}