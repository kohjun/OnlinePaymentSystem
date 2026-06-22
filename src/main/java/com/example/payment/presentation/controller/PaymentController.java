package com.example.payment.presentation.controller;

import com.example.payment.application.service.PaymentProcessingService;
import com.example.payment.infrastructure.gateway.TossPaymentsProperties;
import com.example.payment.infrastructure.security.AuthorizationGuard;
import com.example.payment.infrastructure.util.RateLimiter;
import com.example.payment.presentation.dto.request.PaymentProcessRequest;
import com.example.payment.presentation.dto.response.PaymentResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
@Slf4j
@Validated
public class PaymentController {

    private final PaymentProcessingService paymentProcessingService;
    private final RateLimiter rateLimiter;
    private final TossPaymentsProperties tossPaymentsProperties;
    private final AuthorizationGuard authorizationGuard;

    @Value("${payment.legacy-api.enabled:false}")
    private boolean legacyApiEnabled;

    @PostMapping("/process")
    public ResponseEntity<PaymentResponse> processReservationPayment(
            @Valid @RequestBody PaymentProcessRequest request) {

        if (!legacyApiEnabled) {
            return legacyPaymentGone(request.getPaymentId(), request.getReservationId(), request.getAmount(), request.getCurrency());
        }
        authorizationGuard.requireCustomerAccess(request.getCustomerId());

        log.info("Payment processing request: reservationId={}, customerId={}, amount={}",
                request.getReservationId(), request.getCustomerId(), request.getAmount());

        if (!rateLimiter.allowRequest(request.getCustomerId())) {
            log.warn("Rate limit exceeded for payment: customerId={}", request.getCustomerId());
            return ResponseEntity.status(429).body(
                    PaymentResponse.failed(
                            request.getPaymentId(),
                            request.getReservationId(),
                            request.getAmount(),
                            request.getCurrency(),
                            "RATE_LIMIT_EXCEEDED",
                            "Too many payment requests. Please retry later."
                    )
            );
        }

        PaymentResponse response = paymentProcessingService.processReservationPayment(request);
        if ("COMPLETED".equals(response.getStatus())) {
            log.info("Payment completed successfully: orderId={}, paymentId={}, reservationId={}",
                    response.getOrderId(), response.getPaymentId(), response.getReservationId());
            return ResponseEntity.ok(response);
        }
        if ("FAILED".equals(response.getStatus())) {
            log.warn("Payment failed: reservationId={}, reason={}",
                    request.getReservationId(), response.getMessage());
            return ResponseEntity.badRequest().body(response);
        }

        log.error("Payment error: reservationId={}, reason={}",
                request.getReservationId(), response.getMessage());
        return ResponseEntity.internalServerError().body(response);
    }

    @GetMapping("/{paymentId}")
    public ResponseEntity<PaymentResponse> getPaymentStatus(@PathVariable String paymentId) {
        authorizationGuard.requirePaymentAccess(paymentId);
        log.debug("Getting payment status: paymentId={}", paymentId);

        PaymentResponse response = paymentProcessingService.getPaymentStatus(paymentId);
        if (response == null) {
            log.warn("Payment not found: paymentId={}", paymentId);
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(response);
    }

    @GetMapping("/reservation/{reservationId}")
    public ResponseEntity<PaymentResponse> getPaymentByReservation(@PathVariable String reservationId) {
        authorizationGuard.requireReservationAccess(reservationId);
        log.debug("Getting payment by reservation: reservationId={}", reservationId);

        PaymentResponse response = paymentProcessingService.getPaymentStatusByReservationId(reservationId);
        if (response == null) {
            log.warn("Payment not found for reservation: reservationId={}", reservationId);
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{paymentId}/retry")
    public ResponseEntity<PaymentResponse> retryPayment(@PathVariable String paymentId,
                                                        @Valid @RequestBody PaymentProcessRequest request) {

        if (!legacyApiEnabled) {
            return legacyPaymentGone(paymentId, request.getReservationId(), request.getAmount(), request.getCurrency());
        }
        authorizationGuard.requireCustomerAccess(request.getCustomerId());

        log.info("Payment retry requested: paymentId={}, reservationId={}",
                paymentId, request.getReservationId());

        PaymentResponse existingPayment = paymentProcessingService.getPaymentStatus(paymentId);
        if (existingPayment != null && "COMPLETED".equals(existingPayment.getStatus())) {
            log.warn("Payment already completed, cannot retry: paymentId={}", paymentId);
            return ResponseEntity.badRequest().body(
                    PaymentResponse.builder()
                            .paymentId(paymentId)
                            .status("ERROR")
                            .message("Payment is already completed and cannot be retried.")
                            .build()
            );
        }

        return processReservationPayment(request);
    }

    @PostMapping("/{paymentId}/refund")
    public ResponseEntity<String> refundPayment(@PathVariable String paymentId,
                                                @RequestParam String customerId,
                                                @RequestParam(required = false) String reason) {

        if (!legacyApiEnabled) {
            return ResponseEntity.status(HttpStatus.GONE)
                    .body("Legacy refund API is disabled. Use the admin refund API after authorization is enabled.");
        }
        authorizationGuard.requireAdmin();

        log.info("Payment refund requested: paymentId={}, customerId={}, reason={}",
                paymentId, customerId, reason);

        try {
            boolean refunded = paymentProcessingService.refundPayment(paymentId);
            if (refunded) {
                log.info("Payment refunded successfully: paymentId={}", paymentId);
                return ResponseEntity.ok("Payment refunded successfully.");
            }

            log.warn("Failed to refund payment: paymentId={}", paymentId);
            return ResponseEntity.badRequest().body(
                    "Payment refund failed. It may already be refunded or cannot be refunded."
            );

        } catch (Exception e) {
            log.error("Error refunding payment: paymentId={}", paymentId, e);
            return ResponseEntity.internalServerError().body("System error while refunding payment.");
        }
    }

    @GetMapping("/health")
    public ResponseEntity<java.util.Map<String, Object>> healthCheck() {
        try {
            boolean healthy = paymentProcessingService.isPaymentGatewayHealthy();
            return ResponseEntity.ok(java.util.Map.of(
                    "status", healthy ? "UP" : "DOWN",
                    "service", "PaymentProcessingService",
                    "gatewayHealthy", healthy,
                    "gateway", "TOSS_PAYMENTS",
                    "tossMode", tossPaymentsProperties.getMode(),
                    "tossClientKeyConfigured", hasText(tossPaymentsProperties.getClientKey()),
                    "tossSecretKeyConfigured", hasText(tossPaymentsProperties.getSecretKey()),
                    "timestamp", System.currentTimeMillis()
            ));

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

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private ResponseEntity<PaymentResponse> legacyPaymentGone(String paymentId,
                                                              String reservationId,
                                                              java.math.BigDecimal amount,
                                                              String currency) {
        return ResponseEntity.status(HttpStatus.GONE).body(
                PaymentResponse.failed(
                        paymentId,
                        reservationId,
                        amount,
                        currency,
                        "LEGACY_PAYMENT_API_DISABLED",
                        "Legacy payment API is disabled. Use Toss Payments intent/confirm checkout."
                )
        );
    }
}
