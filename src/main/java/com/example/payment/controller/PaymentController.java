package com.example.payment.controller;

import com.example.payment.service.CacheService;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.payment.util.RateLimiter;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/payment")
@RequiredArgsConstructor
public class PaymentController {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final RateLimiter rateLimiter;
    private final CacheService cacheService;

    @PostMapping("/process")
    public ResponseEntity<?> processPayment(@RequestBody String request) {
        // 클라이언트 ID 추출 (실제로는 인증 정보에서 가져올 수 있음)
        String clientId = "test-client";

        // 속도 제한 적용
        if (!rateLimiter.allowRequest(clientId)) {
            return ResponseEntity.status(429).body("Too many requests");
        }

        // 캐시 확인 - 중복 결제 방지 로직 등에 활용 가능
        String cacheKey = "payment:" + request.hashCode();
        if (cacheService.hasKey(cacheKey)) {
            return ResponseEntity.ok("Payment already processed");
        }

        // Kafka에 결제 요청 발행
        kafkaTemplate.send("payment-requests", request);

        // 결제 요청 처리 중 상태를 캐시에 저장
        cacheService.cacheData(cacheKey, "PROCESSING", 60);

        return ResponseEntity.accepted().body("Payment request accepted");
    }
}