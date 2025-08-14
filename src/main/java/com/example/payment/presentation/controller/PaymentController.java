package com.example.payment.presentation.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.validation.annotation.Validated;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import com.example.payment.application.service.PaymentProcessingService;
import com.example.payment.presentation.dto.request.PaymentRequest;
import com.example.payment.presentation.dto.response.PaymentResponse;
import com.example.payment.infrastructure.util.RateLimiter;

/**
 * 예약 기반 결제 처리 컨트롤러
 * - 선점된 재고에 대한 결제 처리 (한정 상품 예약의 2단계)
 */
@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
@Slf4j
@Validated
public class PaymentController {

    private final PaymentProcessingService paymentProcessingService;
    private final RateLimiter rateLimiter;

    /**
     * 예약된 상품 결제 처리 (한정 상품 예약의 2단계)
     * POST /api/payments/process
     */
    @PostMapping("/process")
    public ResponseEntity<PaymentResponse> processReservationPayment(@Valid @RequestBody PaymentRequest request) {

        log.info("Payment processing request: reservationId={}, customerId={}, amount={}",
                request.getReservationId(), request.getCustomerId(), request.getAmount());

        // 1. 속도 제한 확인
        if (!rateLimiter.allowRequest(request.getCustomerId())) {
            log.warn("Rate limit exceeded for payment: customerId={}", request.getCustomerId());

            return ResponseEntity.status(429).body(
                    PaymentResponse.failed(
                            request.getPaymentId(),
                            request.getReservationId(),
                            request.getAmount(),
                            request.getCurrency(),
                            "RATE_LIMIT_EXCEEDED",
                            "너무 많은 결제 요청입니다. 잠시 후 다시 시도해주세요."
                    )
            );
        }

        // 2. 예약 기반 결제 처리
        PaymentResponse response = paymentProcessingService.processReservationPayment(request);

        // 3. 응답 처리
        if ("COMPLETED".equals(response.getStatus())) {
            log.info("Payment completed successfully: orderId={}, paymentId={}, reservationId={}",
                    response.getOrderId(), response.getPaymentId(), response.getReservationId());
            return ResponseEntity.ok(response);

        } else if ("FAILED".equals(response.getStatus())) {
            log.warn("Payment failed: reservationId={}, reason={}",
                    request.getReservationId(), response.getMessage());
            return ResponseEntity.badRequest().body(response);

        } else {
            log.error("Payment error: reservationId={}, reason={}",
                    request.getReservationId(), response.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 결제 상태 조회
     * GET /api/payments/{paymentId}
     */
    @GetMapping("/{paymentId}")
    public ResponseEntity<PaymentResponse> getPaymentStatus(@PathVariable String paymentId) {

        log.debug("Getting payment status: paymentId={}", paymentId);

        PaymentResponse response = paymentProcessingService.getPaymentStatus(paymentId);

        if (response != null) {
            return ResponseEntity.ok(response);
        } else {
            log.warn("Payment not found: paymentId={}", paymentId);
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * 예약 ID로 결제 조회 (편의 기능)
     * GET /api/payments/reservation/{reservationId}
     */
    @GetMapping("/reservation/{reservationId}")
    public ResponseEntity<String> getPaymentByReservation(@PathVariable String reservationId) {
        // TODO: 예약 ID로 결제 정보 조회 기능 구현
        log.debug("Getting payment by reservation: reservationId={}", reservationId);
        return ResponseEntity.ok("구현 예정: 예약 " + reservationId + "에 대한 결제 정보");
    }

    /**
     * 결제 재시도 (실패한 결제에 대해)
     * POST /api/payments/{paymentId}/retry
     */
    @PostMapping("/{paymentId}/retry")
    public ResponseEntity<PaymentResponse> retryPayment(@PathVariable String paymentId,
                                                        @RequestBody PaymentRequest request) {

        log.info("Payment retry requested: paymentId={}, reservationId={}",
                paymentId, request.getReservationId());

        // 기본적으로 새로운 결제 처리와 동일
        return processReservationPayment(request);
    }
}