package com.example.payment.presentation.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.validation.annotation.Validated;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import com.example.payment.application.service.PaymentProcessingService;
import com.example.payment.presentation.dto.request.PaymentProcessRequest;
import com.example.payment.presentation.dto.response.PaymentResponse;
import com.example.payment.infrastructure.util.RateLimiter;

/**
 * 결제 컨트롤러

 * 제공하는 기능:
 * - 단독 결제 처리 (POST /process) - 이미 예약된 상품에 대한 결제만
 * - 결제 상태 조회 (GET /{paymentId})
 * - 결제 환불 (POST /{paymentId}/refund)
 * - 결제 재시도 (POST /{paymentId}/retry)
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
     *
     * ⚠️ 레거시 API: 새로운 시스템에서는 /api/reservations/complete 사용 권장
     */
    @PostMapping("/process")
    public ResponseEntity<PaymentResponse> processReservationPayment(
            @Valid @RequestBody PaymentProcessRequest request) {

        log.info("Payment processing request: reservationId={}, customerId={}, amount={}",
                request.getReservationId(), request.getCustomerId(), request.getAmount());

        // 속도 제한 확인
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

        // 예약 기반 결제 처리 - PaymentProcessRequest 직접 사용
        PaymentResponse response = paymentProcessingService.processReservationPayment(request);

        // 응답 처리
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
    public ResponseEntity<PaymentResponse> getPaymentByReservation(@PathVariable String reservationId) {
        log.debug("Getting payment by reservation: reservationId={}", reservationId);

        try {
            // 캐시에서 예약 정보 조회하여 paymentId 찾기
            // TODO: 실제 구현에서는 DB 조회 필요

            log.info("Payment lookup by reservation not fully implemented yet");

            return ResponseEntity.ok(
                    PaymentResponse.builder()
                            .reservationId(reservationId)
                            .status("INFO")
                            .message("예약 ID로 결제 조회 기능은 구현 예정입니다. " +
                                    "현재는 paymentId를 직접 사용해주세요.")
                            .build()
            );

        } catch (Exception e) {
            log.error("Error getting payment by reservation: reservationId={}", reservationId, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * 결제 재시도 (실패한 결제에 대해)
     * POST /api/payments/{paymentId}/retry
     */
    @PostMapping("/{paymentId}/retry")
    public ResponseEntity<PaymentResponse> retryPayment(@PathVariable String paymentId,
                                                        @Valid @RequestBody PaymentProcessRequest request) {

        log.info("Payment retry requested: paymentId={}, reservationId={}",
                paymentId, request.getReservationId());

        // 기존 결제 상태 확인
        PaymentResponse existingPayment = paymentProcessingService.getPaymentStatus(paymentId);

        if (existingPayment != null && "COMPLETED".equals(existingPayment.getStatus())) {
            log.warn("Payment already completed, cannot retry: paymentId={}", paymentId);
            return ResponseEntity.badRequest().body(
                    PaymentResponse.builder()
                            .paymentId(paymentId)
                            .status("ERROR")
                            .message("이미 완료된 결제는 재시도할 수 없습니다.")
                            .build()
            );
        }

        // 새로운 결제 처리 (재시도)
        return processReservationPayment(request);
    }

    /**
     * 결제 환불
     * POST /api/payments/{paymentId}/refund
     */
    @PostMapping("/{paymentId}/refund")
    public ResponseEntity<String> refundPayment(@PathVariable String paymentId,
                                                @RequestParam String customerId,
                                                @RequestParam(required = false) String reason) {

        log.info("Payment refund requested: paymentId={}, customerId={}, reason={}",
                paymentId, customerId, reason);

        try {
            boolean refunded = paymentProcessingService.refundPayment(paymentId);

            if (refunded) {
                log.info("Payment refunded successfully: paymentId={}", paymentId);
                return ResponseEntity.ok("결제가 환불되었습니다.");
            } else {
                log.warn("Failed to refund payment: paymentId={}", paymentId);
                return ResponseEntity.badRequest().body("결제 환불에 실패했습니다. 이미 환불되었거나 환불 불가능한 상태입니다.");
            }

        } catch (Exception e) {
            log.error("Error refunding payment: paymentId={}", paymentId, e);
            return ResponseEntity.internalServerError().body("시스템 오류가 발생했습니다.");
        }
    }

    /**
     * 결제 시스템 헬스체크
     * GET /api/payments/health
     */
    @GetMapping("/health")
    public ResponseEntity<java.util.Map<String, Object>> healthCheck() {
        try {
            boolean healthy = paymentProcessingService.isPaymentGatewayHealthy();

            java.util.Map<String, Object> health = java.util.Map.of(
                    "status", healthy ? "UP" : "DOWN",
                    "service", "PaymentProcessingService",
                    "gateway_healthy", healthy,
                    "timestamp", System.currentTimeMillis()
            );

            return ResponseEntity.ok(health);

        } catch (Exception e) {
            log.error("Payment health check failed", e);
            return ResponseEntity.internalServerError().body(
                    java.util.Map.of(
                            "status", "DOWN",
                            "error", e.getMessage(),
                            "timestamp", System.currentTimeMillis()
                    )
            );
        }
    }
}