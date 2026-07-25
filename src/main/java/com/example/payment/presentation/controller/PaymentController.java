package com.example.payment.presentation.controller;

import com.example.payment.application.service.PaymentProcessingService;
import com.example.payment.infrastructure.gateway.TossPaymentsProperties;
import com.example.payment.infrastructure.security.AuthorizationGuard;
import com.example.payment.presentation.dto.response.PaymentResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
@Slf4j
@Validated
public class PaymentController {

    private final PaymentProcessingService paymentProcessingService;
    private final TossPaymentsProperties tossPaymentsProperties;
    private final AuthorizationGuard authorizationGuard;

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

}
