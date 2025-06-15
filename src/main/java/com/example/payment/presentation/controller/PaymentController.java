package com.example.payment.presentation.controller;

import com.example.payment.presentation.dto.request.PaymentRequest;
import com.example.payment.presentation.dto.response.PaymentResponse;
import com.example.payment.infrastructure.persistance.redis.repository.CacheService;
import com.example.payment.application.service.PaymentService;
import com.example.payment.infrastructure.util.RateLimiter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/payment")
@RequiredArgsConstructor
@Slf4j
public class PaymentController {

    private final PaymentService paymentService;
    private final RateLimiter rateLimiter;
    private final CacheService cacheService;

    @PostMapping("/process")
    public ResponseEntity<PaymentResponse> processPayment(@RequestBody PaymentRequest request) {
        // 클라이언트 ID 추출
        String clientId = request.getClientId();

        // 속도 제한 적용
        if (!rateLimiter.allowRequest(clientId)) {
            return ResponseEntity.status(429).body(
                    PaymentResponse.builder()
                            .paymentId(request.getPaymentId())
                            .status("REJECTED")
                            .message("Too many requests")
                            .build()
            );
        }

        // 중복 결제 방지
        String cacheKey = "payment:" + (request.getIdempotencyKey() != null ?
                request.getIdempotencyKey() : request.getPaymentId());
        if (cacheService.hasKey(cacheKey)) {
            return ResponseEntity.ok(
                    PaymentResponse.builder()
                            .paymentId(request.getPaymentId())
                            .status("DUPLICATE")
                            .message("Payment already processed")
                            .build()
            );
        }

        // 결제 처리 시작
        PaymentResponse response = paymentService.initiatePayment(request);

        return ResponseEntity.accepted().body(response);
    }

    @GetMapping("/{paymentId}")
    public ResponseEntity<PaymentResponse> getPaymentStatus(@PathVariable String paymentId) {
        PaymentResponse response = paymentService.getPaymentStatus(paymentId);

        if (response == null) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(response);
    }
}