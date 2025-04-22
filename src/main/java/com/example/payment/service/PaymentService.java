package com.example.payment.service;

import java.time.LocalDateTime;
import java.util.UUID;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import com.example.payment.dto.PaymentRequest;
import com.example.payment.dto.PaymentResponse;
import com.example.payment.service.PaymentEventService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@RequiredArgsConstructor
public class PaymentService {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final CacheService cacheService;
    private final PaymentEventService eventService;
    private final ObjectMapper objectMapper;

    // 결제 처리 시작
    public PaymentResponse initiatePayment(PaymentRequest request) {
        // 결제 ID 생성 (없는 경우)
        if (request.getPaymentId() == null) {
            request.setPaymentId(UUID.randomUUID().toString());
        }

        log.info("Initiating payment process for payment ID: {}", request.getPaymentId());

        // 중복 결제 방지를 위한 캐시 키
        String cacheKey = "payment:" + (request.getIdempotencyKey() != null ?
                request.getIdempotencyKey() : request.getPaymentId());

        // 결제 응답 생성
        PaymentResponse response = PaymentResponse.builder()
                .paymentId(request.getPaymentId())
                .orderId(request.getOrderId())
                .amount(request.getAmount())
                .currency(request.getCurrency())
                .status("PROCESSING")
                .message("Payment request is being processed")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        // 진행 중 상태를 캐시에 저장 (30초 동안 유지)
        cacheService.cacheData(cacheKey, "PROCESSING", 30);

        try {
            // JSON으로 직렬화
            String payload = objectMapper.writeValueAsString(request);

            // Kafka에 결제 요청 발행
            kafkaTemplate.send("payment-requests", request.getPaymentId(), payload);

            // 결제 생성 이벤트 발행
            eventService.publishPaymentCreated(response);

        } catch (JsonProcessingException e) {
            log.error("Error serializing payment request: {}", e.getMessage());
            response.setStatus("FAILED");
            response.setMessage("Payment processing error: " + e.getMessage());

            // 실패 이벤트 발행
            eventService.publishPaymentFailed(response);
        }

        return response;
    }

    // 결제 상태 조회
    public PaymentResponse getPaymentStatus(String paymentId) {
        // 캐시에서 상태 확인
        String cacheKey = "payment:" + paymentId;
        Object cachedStatus = cacheService.getCachedData(cacheKey);

        if (cachedStatus == null) {
            return null;
        }

        // 이 예제에서는 간단히 구현. 실제로는 DB에서 조회 필요
        return PaymentResponse.builder()
                .paymentId(paymentId)
                .status(cachedStatus.toString())
                .updatedAt(LocalDateTime.now())
                .build();
    }
}