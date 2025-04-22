package com.example.payment.service;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;

import com.example.payment.dto.PaymentRequest;
import com.example.payment.dto.PaymentResponse;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;

@Service
@Slf4j
@RequiredArgsConstructor
public class PaymentProcessorService {

    private final CacheService cacheService;
    private final PaymentEventService eventService;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "payment-requests", groupId = "payment-group")
    public void processPayment(String requestJson, Acknowledgment ack) {
        log.info("Payment request received: {}", requestJson);

        try {
            // JSON 파싱
            PaymentRequest request = objectMapper.readValue(requestJson, PaymentRequest.class);

            // 처리 캐시 키
            String cacheKey = "payment:" + (request.getIdempotencyKey() != null ?
                    request.getIdempotencyKey() : request.getPaymentId());

            // 결제 처리 로직 시뮬레이션
            Thread.sleep(100); // 결제 처리 시간 시뮬레이션

            // 처리 완료 응답 생성
            PaymentResponse response = PaymentResponse.builder()
                    .paymentId(request.getPaymentId())
                    .orderId(request.getOrderId())
                    .amount(request.getAmount())
                    .currency(request.getCurrency())
                    .status("COMPLETED")
                    .message("Payment processed successfully")
                    .createdAt(request.getRequestTime())
                    .updatedAt(LocalDateTime.now())
                    .build();

            // 처리 완료 후 캐시 업데이트
            cacheService.cacheData(cacheKey, "COMPLETED", 300);

            // 결제 처리 완료 이벤트 발행
            eventService.publishPaymentProcessed(response);

            log.info("Payment processed successfully: {}", request.getPaymentId());

            // 메시지 처리 완료 확인
            ack.acknowledge();

        } catch (Exception e) {
            log.error("Error processing payment: {}", e.getMessage(), e);

            // 예외 처리 - 실제 구현에서는 오류 타입에 따라 재시도 여부 결정 필요
            try {
                PaymentRequest request = objectMapper.readValue(requestJson, PaymentRequest.class);
                String cacheKey = "payment:" + (request.getIdempotencyKey() != null ?
                        request.getIdempotencyKey() : request.getPaymentId());

                // 실패 상태 캐싱
                cacheService.cacheData(cacheKey, "FAILED", 300);

                // 실패 이벤트 발행
                PaymentResponse failedResponse = PaymentResponse.builder()
                        .paymentId(request.getPaymentId())
                        .orderId(request.getOrderId())
                        .amount(request.getAmount())
                        .currency(request.getCurrency())
                        .status("FAILED")
                        .message("Payment processing failed: " + e.getMessage())
                        .updatedAt(LocalDateTime.now())
                        .build();

                eventService.publishPaymentFailed(failedResponse);
            } catch (Exception ex) {
                log.error("Error handling payment failure: {}", ex.getMessage());
            }

            // 에러 시에도 메시지 처리 완료로 표시 (DLQ 사용 고려)
            ack.acknowledge();
        }
    }
}